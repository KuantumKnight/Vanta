package dev.system.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SystemDaoOfflineQueueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "system-offline-${UUID.randomUUID()}.db"
    private lateinit var database: SystemDatabase

    @Before
    fun openDatabase() {
        database = newDatabase()
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized && database.isOpen) database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun queuedCompletionAndKeySurviveReopenAndHidePendingRefreshRows() = runBlocking {
        val occurrence = occurrence()
        val queued = PendingCompletion(
            idempotencyKey = "stable-operation-key",
            occurrenceId = occurrence.id,
            queuedAt = 1_790_280_000_000L,
            userId = USER_A,
        )
        val dao = database.systemDao()
        dao.replaceOccurrences(listOf(occurrence))
        assertTrue(dao.storeQueuedCompletion(queued, occurrence.copy(status = "completed", completedAt = "2026-09-24T12:00:00Z")))
        assertFalse(
            dao.storeQueuedCompletion(
                PendingCompletion("duplicate-key", occurrence.id, userId = USER_A),
                occurrence.copy(status = "completed", completedAt = "2026-09-24T12:01:00Z"),
            ),
        )

        assertTrue(dao.pendingSnapshot(USER_A).isEmpty())
        dao.replaceOccurrencesSnapshot(USER_A, listOf(occurrence))
        assertTrue(dao.pendingSnapshot(USER_A).isEmpty())

        database.close()
        database = newDatabase()
        val reopened = database.systemDao()
        assertEquals(listOf(queued), reopened.pendingCompletions(USER_A))
        assertTrue(reopened.pendingSnapshot(USER_A).isEmpty())
        assertEquals("stable-operation-key", reopened.pendingCompletions(USER_A).single().idempotencyKey)
    }

    @Test
    fun permanentFailureIsDeadLetteredAndDoesNotBlockLaterSuccess() = runBlocking {
        val dao = queuedDao(occurrence("permanent-a"), occurrence("valid-b"))
        val replayed = mutableListOf<String>()
        CompletionQueueReplayer(dao, completeOnServer = { item ->
            replayed += item.idempotencyKey
            if (item.occurrenceId == "permanent-a") {
                ReplayResult(ReplayOutcome.PERMANENT_FAILURE, "NOT_FOUND", "Occurrence was removed.")
            } else ReplayResult(ReplayOutcome.SUCCESS)
        }).replay(USER_A)

        assertEquals(listOf("key-permanent-a", "key-valid-b"), replayed)
        val retained = dao.pendingCompletions(USER_A).single()
        assertEquals("key-permanent-a", retained.idempotencyKey)
        assertEquals("dead_letter", retained.state)
        assertEquals("NOT_FOUND", retained.lastErrorCode)
    }

    @Test
    fun retryableFailureSchedulesBackoffAndDoesNotBlockLaterSuccess() = runBlocking {
        val dao = queuedDao(occurrence("retry-a"), occurrence("valid-b"))
        val replayed = mutableListOf<String>()
        val summary = CompletionQueueReplayer(
            dao,
            completeOnServer = { item ->
                replayed += item.idempotencyKey
                if (item.occurrenceId == "retry-a") throw IOException("network disconnected")
                ReplayResult(ReplayOutcome.SUCCESS)
            },
            now = { 1_000L },
        ).replay(USER_A)

        assertEquals(listOf("key-retry-a", "key-valid-b"), replayed)
        val retained = dao.pendingCompletions(USER_A).single()
        assertEquals("key-retry-a", retained.idempotencyKey)
        assertEquals("queued", retained.state)
        assertEquals(1, retained.retryCount)
        assertEquals(31_000L, retained.nextAttemptAt)
        assertEquals(31_000L, summary.nextRetryAt)
    }

    @Test
    fun alreadyAppliedCompletionClearsQueueEntry() = runBlocking {
        val dao = queuedDao(occurrence("already-done"))
        CompletionQueueReplayer(dao, completeOnServer = { ReplayResult(ReplayOutcome.ALREADY_APPLIED) }).replay(USER_A)
        assertTrue(dao.pendingCompletions(USER_A).isEmpty())
    }

    @Test
    fun authRequiredStopsReplayAndRetainsLaterItems() = runBlocking {
        val dao = queuedDao(occurrence("auth-a"), occurrence("auth-b"))
        val firstPass = mutableListOf<String>()
        CompletionQueueReplayer(dao, completeOnServer = { item ->
            firstPass += item.idempotencyKey
            ReplayResult(ReplayOutcome.AUTH_REQUIRED, "AUTH_REQUIRED", "Sign in again.")
        }).replay(USER_A)
        assertEquals(listOf("key-auth-a"), firstPass)
        assertEquals(2, dao.pendingCompletions(USER_A).size)
        assertEquals("AUTH_REQUIRED", dao.pendingCompletions(USER_A).first().lastErrorCode)

        dao.resetAuthRequiredCompletions(USER_A)
        CompletionQueueReplayer(dao, completeOnServer = { ReplayResult(ReplayOutcome.SUCCESS) }).replay(USER_A)
        assertTrue(dao.pendingCompletions(USER_A).isEmpty())
    }

    @Test
    fun serverCommitThenLostResponseRetriesWithTheSameIdempotencyKey() = runBlocking {
        val dao = queuedDao(occurrence("commit-lost"))
        val serverApplied = mutableSetOf<String>()
        val keysSeen = mutableListOf<String>()
        CompletionQueueReplayer(
            dao,
            completeOnServer = { item ->
                keysSeen += item.idempotencyKey
                if (serverApplied.add(item.idempotencyKey)) throw IOException("response timed out after commit")
                ReplayResult(ReplayOutcome.ALREADY_APPLIED)
            },
            now = { 1_000L },
        ).replay(USER_A)

        CompletionQueueReplayer(
            dao,
            completeOnServer = { item ->
                keysSeen += item.idempotencyKey
                if (serverApplied.contains(item.idempotencyKey)) ReplayResult(ReplayOutcome.ALREADY_APPLIED)
                else ReplayResult(ReplayOutcome.SUCCESS)
            },
            now = { 31_001L },
        ).replay(USER_A)

        assertEquals(listOf("key-commit-lost", "key-commit-lost"), keysSeen)
        assertTrue(dao.pendingCompletions(USER_A).isEmpty())
    }

    @Test
    fun userSnapshotsAndQueuesStayIsolated() = runBlocking {
        val dao = database.systemDao()
        val accountA = occurrence("account-a", USER_A)
        val accountB = occurrence("account-b", USER_B)
        dao.replaceOccurrences(listOf(accountA, accountB))
        dao.storeQueuedCompletion(
            PendingCompletion("account-a-key", accountA.id, userId = USER_A),
            accountA.copy(status = "completed", completedAt = "2026-09-24T12:00:00Z"),
        )

        assertTrue(dao.pendingSnapshot(USER_A).isEmpty())
        assertEquals(listOf("account-b"), dao.pendingSnapshot(USER_B).map { it.id })
        assertEquals(listOf(USER_A), dao.pendingCompletions(USER_A).map { it.userId })
        assertTrue(dao.pendingCompletions(USER_B).isEmpty())
    }

    @Test
    fun widgetSnapshotIsBoundedAndKeepsCurrentTodayCompletedAndNextRows() = runBlocking {
        val zone = ZoneId.of("Asia/Kolkata")
        val today = LocalDate.of(2026, 10, 21)
        val now = today.atTime(15, 30).atZone(zone).toInstant()
        val todayRows = (0 until 140).map { index ->
            occurrence("today-$index").copy(
                scheduledFor = today.atTime(16, 0).atZone(zone).toInstant().plusSeconds(index.toLong()).toString(),
                allDay = false,
                durationMinutes = 30,
            )
        }
        val previousCurrent = occurrence("previous-current").copy(
            scheduledFor = today.minusDays(1).atTime(18, 0).atZone(zone).toInstant().toString(),
            allDay = false,
            durationMinutes = 1_440,
        )
        val completedToday = occurrence("completed-today").copy(
            scheduledFor = today.atTime(15, 0).atZone(zone).toInstant().toString(),
            status = "completed",
            completedAt = today.atTime(15, 10).atZone(zone).toInstant().toString(),
            allDay = false,
            durationMinutes = 15,
        )
        val nextDay = occurrence("next-day").copy(
            scheduledFor = today.plusDays(3).atTime(9, 0).atZone(zone).toInstant().toString(),
        )
        val old = occurrence("old").copy(
            scheduledFor = today.minusDays(3).atTime(12, 0).atZone(zone).toInstant().toString(),
        )
        val dao = database.systemDao()
        dao.replaceOccurrences(todayRows + previousCurrent + completedToday + nextDay + old)

        val snapshot = dao.widgetSnapshot(
            userId = USER_A,
            activeSince = now.minusSeconds(24 * 60 * 60).toString(),
            nowEpochSeconds = now.epochSecond,
            dayStart = today.atStartOfDay(zone).toInstant().toString(),
            nextDayStart = today.plusDays(1).atStartOfDay(zone).toInstant().toString(),
        )

        assertEquals(128, snapshot.occurrences.size)
        assertTrue(snapshot.occurrences.any { it.id == "previous-current" })
        assertTrue(snapshot.occurrences.any { it.id == "completed-today" })
        assertTrue(snapshot.occurrences.any { it.id == "next-day" })
        assertFalse(snapshot.occurrences.any { it.id == "old" })
        assertEquals(140, snapshot.todayPendingCount)
        assertEquals(1, snapshot.todayCompletedCount)
        assertEquals(4_215L, snapshot.plannedMinutes)
    }

    @Test
    fun createMutationAndTemporaryOccurrenceSurviveReopenWithStableOperationId() = runBlocking {
        val operationId = UUID.randomUUID().toString()
        val localId = "local:$operationId"
        val local = occurrence(localId).copy(taskId = localId, title = "Offline capture")
        val mutation = PendingMutation(
            operationId = operationId,
            userId = USER_A,
            operationType = "create_task",
            localOccurrenceId = localId,
            title = local.title,
            scheduledFor = local.scheduledFor,
            allDay = local.allDay,
            durationMinutes = 30,
        )

        assertTrue(database.systemDao().storeCreateMutation(mutation, local))
        database.close()
        database = newDatabase()

        val reopened = database.systemDao()
        assertEquals(local, reopened.occurrence(USER_A, localId))
        assertEquals(operationId, reopened.pendingMutations(USER_A).single().operationId)
        assertEquals(localId, reopened.nextDuePendingMutation(USER_A, System.currentTimeMillis())?.localOccurrenceId)
    }

    @Test
    fun incomingDeltaKeepsOptimisticScheduleAndStillAdvancesItsCheckpoint() = runBlocking {
        val original = occurrence("rescheduled")
        val optimisticSchedule = "2026-09-25T12:30:00Z"
        val dao = database.systemDao()
        dao.replaceOccurrences(listOf(original))
        assertTrue(
            dao.storeRescheduleMutation(
                PendingMutation(
                    operationId = "reschedule-operation",
                    userId = USER_A,
                    operationType = "reschedule_occurrence",
                    targetOccurrenceId = original.id,
                    scheduledFor = optimisticSchedule,
                ),
                original.copy(scheduledFor = optimisticSchedule),
            ),
        )

        val staleRemote = original.copy(scheduledFor = "2026-09-25T10:00:00Z")
        val delta = OccurrenceDelta(1, original.id, isDeleted = false, occurrence = staleRemote)
        dao.applyDeltaPage(USER_A, listOf(delta), revision = 1)

        assertEquals(optimisticSchedule, dao.occurrence(USER_A, original.id)?.scheduledFor)
        assertEquals(1L, dao.syncCheckpoint(USER_A)?.revision)
        assertEquals(1, dao.pendingMutations(USER_A).size)
    }

    @Test
    fun taskUpdateMutationAndOptimisticImageSurviveReopenAndReconcileFromCanonicalRow() = runBlocking {
        val original = occurrence("update-target").copy(
            allDay = false,
            scheduleType = "SOFT",
            notes = "Old notes",
            deadlineAt = "2026-09-27T10:00:00Z",
        )
        val update = TaskOccurrenceUpdate(
            title = FieldChange.Set("Offline edit"),
            notes = FieldChange.Set<String?>(null),
            deadlineAt = FieldChange.Set<Instant?>(null),
        )
        val optimistic = update.applyTo(original)
        val mutation = update.toMutation("update-operation", USER_A, original)
        val dao = database.systemDao()
        dao.replaceOccurrences(listOf(original))

        assertTrue(dao.storeTaskUpdateMutation(mutation, original, optimistic))
        dao.markMutationRetry(
            userId = USER_A,
            operationId = mutation.operationId,
            retryCount = 1,
            attemptAt = 50L,
            nextAttemptAt = 5_000L,
            errorCode = "TEMPORARY_FAILURE",
            message = "retry later",
        )
        database.close()
        database = newDatabase()

        val reopened = database.systemDao()
        assertEquals(optimistic, reopened.occurrence(USER_A, original.id))
        val persistedMutation = reopened.pendingMutations(USER_A).single()
        assertEquals("update-operation", persistedMutation.operationId)
        assertEquals("update_task", persistedMutation.operationType)
        assertEquals("task-update-target", persistedMutation.targetTaskId)
        assertTrue(persistedMutation.updateTitle)
        assertTrue(persistedMutation.updateNotes)
        assertTrue(persistedMutation.updateDeadlineAt)
        assertEquals(null, persistedMutation.notes)
        assertEquals(mutation.toUpdateTaskParams(), persistedMutation.toUpdateTaskParams())
        assertEquals("update-operation", persistedMutation.operationId)
        assertEquals(5_000L, persistedMutation.nextAttemptAt)
        assertEquals(null, reopened.nextDuePendingMutation(USER_A, 4_999L))
        assertEquals("update-operation", reopened.nextDuePendingMutation(USER_A, 5_000L)?.operationId)

        reopened.applyDeltaPage(
            USER_A,
            listOf(OccurrenceDelta(2, original.id, false, original.copy(title = "Stale delta"))),
            revision = 2,
        )
        assertEquals(optimistic, reopened.occurrence(USER_A, original.id))
        assertEquals(2L, reopened.syncCheckpoint(USER_A)?.revision)

        val canonical = optimistic.copy(title = "Canonical server title", priority = "HIGH")
        assertTrue(reopened.reconcileTaskUpdatedOccurrence(USER_A, "update-operation", canonical))
        assertEquals(canonical, reopened.occurrence(USER_A, original.id))
        assertTrue(reopened.pendingMutations(USER_A).isEmpty())
    }

    @Test
    fun taskUpdateQueueRejectsWrongKindLocalIdsAndExistingWork() = runBlocking {
        val dao = database.systemDao()
        val ordinary = occurrence("ordinary").copy(allDay = false, scheduleType = "SOFT")
        val update = TaskOccurrenceUpdate(title = FieldChange.Set("Updated"))
        val optimistic = update.applyTo(ordinary)
        val mutation = update.toMutation("ordinary-update", USER_A, ordinary)
        dao.replaceOccurrences(listOf(ordinary))

        assertFalse(dao.storeTaskUpdateMutation(mutation.copy(userId = USER_B), ordinary, optimistic))
        val habit = ordinary.copy(id = "habit-update", taskId = "habit-task", kind = "habit")
        dao.replaceOccurrences(listOf(habit))
        assertFalse(dao.storeTaskUpdateMutation(
            mutation.copy(operationId = "habit-update-op", targetOccurrenceId = habit.id, targetTaskId = habit.taskId),
            habit,
            habit.copy(title = "Updated"),
        ))
        assertFalse(
            dao.storeTaskUpdateMutation(
                mutation.copy(targetOccurrenceId = "local:task", targetTaskId = "local:task"),
                ordinary.copy(id = "local:task", taskId = "local:task"),
                optimistic.copy(id = "local:task", taskId = "local:task"),
            ),
        )
        assertTrue(dao.storeRescheduleMutation(
            PendingMutation(
                operationId = "earlier-reschedule",
                userId = USER_A,
                operationType = "reschedule_occurrence",
                targetOccurrenceId = ordinary.id,
                scheduledFor = "2026-09-25T12:30:00Z",
            ),
            ordinary.copy(scheduledFor = "2026-09-25T12:30:00Z"),
        ))
        val rescheduled = dao.occurrence(USER_A, ordinary.id)!!
        val afterReschedule = update.applyTo(rescheduled)
        assertFalse(dao.storeTaskUpdateMutation(
            update.toMutation("update-after-reschedule", USER_A, rescheduled),
            rescheduled,
            afterReschedule,
        ))

        val completed = occurrence("completed-update").copy(allDay = false, scheduleType = "SOFT")
        dao.replaceOccurrences(listOf(completed))
        dao.addPendingCompletion(PendingCompletion("completion-before-update", completed.id, userId = USER_A))
        val completedUpdate = update.toMutation("update-after-completion", USER_A, completed)
        assertFalse(dao.storeTaskUpdateMutation(completedUpdate, completed, update.applyTo(completed)))
    }

    private suspend fun queuedDao(vararg occurrences: CachedOccurrence): SystemDao {
        val dao = database.systemDao()
        occurrences.forEach { occurrence ->
            dao.replaceOccurrences(listOf(occurrence))
            dao.storeQueuedCompletion(
                PendingCompletion("key-${occurrence.id}", occurrence.id, userId = USER_A),
                occurrence.copy(status = "completed", completedAt = "2026-09-24T12:00:00Z"),
            )
        }
        return dao
    }

    private fun newDatabase() = Room.databaseBuilder(context, SystemDatabase::class.java, databaseName)
        .allowMainThreadQueries()
        .addMigrations(SystemDatabase.MIGRATION_1_2, SystemDatabase.MIGRATION_2_3, SystemDatabase.MIGRATION_3_4, SystemDatabase.MIGRATION_4_5)
        .build()

    private fun occurrence(id: String = "occurrence-1", userId: String = USER_A) = CachedOccurrence(
        id = id,
        taskId = "task-$id",
        title = "50 push-ups",
        notes = null,
        scheduledFor = "2026-09-25T00:00:00Z",
        allDay = true,
        kind = "task",
        status = "pending",
        completedAt = null,
        userId = userId,
    )

    companion object {
        private const val USER_A = "user-a"
        private const val USER_B = "user-b"
    }
}
