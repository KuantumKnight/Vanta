package dev.system.app.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SystemDaoOfflineQueueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "system-offline-${UUID.randomUUID()}.db"
    private lateinit var database: SystemDatabase

    @Before
    fun createDatabase() {
        database = openDatabase()
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized && database.isOpen) database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun queuedCompletionAndStableKeySurviveDatabaseReopen() = runBlocking {
        val occurrence = CachedOccurrence(
            id = "occurrence-1",
            userId = "user-a",
            taskId = "task-1",
            title = "50 push-ups",
            notes = null,
            scheduledFor = "2026-09-25T00:00:00Z",
            allDay = true,
            kind = "task",
            status = "pending",
            completedAt = null,
        )
        val request = PendingCompletion(
            idempotencyKey = "stable-operation-key",
            userId = "user-a",
            occurrenceId = occurrence.id,
            queuedAt = 1_790_280_000_000L,
        )

        database.systemDao().replaceOccurrences(listOf(occurrence))
        database.systemDao().queueCompletion(
            request,
            occurrence.copy(status = "completed", completedAt = "2026-09-24T12:00:00Z"),
        )
        database.systemDao().markCompletionAttempt("user-a", request.idempotencyKey, 1_790_280_000_100L)
        database.close()

        database = openDatabase()
        val dao = database.systemDao()
        assertEquals("completed", dao.occurrence("user-a", occurrence.id)?.status)
        val restored = dao.pendingCompletions("user-a").single()
        assertEquals(request.idempotencyKey, restored.idempotencyKey)
        assertEquals("PENDING", restored.state)
        assertEquals(1_790_280_000_100L, restored.lastAttemptAt)
        assertTrue(dao.pendingCompletions("user-b").isEmpty())

        dao.removePendingCompletion("user-a", request.idempotencyKey)
        assertTrue(dao.pendingCompletions("user-a").isEmpty())
    }

    @Test
    fun snapshotRefreshKeepsQueuedCompletionOptimisticAndIsolatesUsers() = runBlocking {
        val dao = database.systemDao()
        val queued = occurrence("queued", "user-a")
        val removed = occurrence("removed", "user-a")
        val otherUser = occurrence("other-user", "user-b")
        dao.replaceOccurrences(listOf(queued, removed, otherUser))
        val completion = PendingCompletion("operation-a", "user-a", queued.id)
        dao.queueCompletion(completion, queued.copy(status = "completed", completedAt = "2026-09-24T12:00:00Z"))

        dao.replaceOccurrencesSnapshot(
            "user-a",
            listOf(queued, occurrence("new", "user-a"), occurrence("injected", "user-b")),
        )

        assertEquals("completed", dao.occurrence("user-a", queued.id)?.status)
        assertEquals(null, dao.occurrence("user-a", removed.id))
        assertEquals("pending", dao.occurrence("user-a", "new")?.status)
        assertEquals("pending", dao.occurrence("user-b", otherUser.id)?.status)
        assertEquals(null, dao.occurrence("user-a", "injected"))
        assertEquals(listOf(completion), dao.pendingCompletions("user-a"))
    }

    @Test
    fun duplicateQueueForOccurrenceKeepsOriginalIdempotencyKey() = runBlocking {
        val dao = database.systemDao()
        val occurrence = occurrence("same-occurrence", "user-a")
        val first = PendingCompletion("first-operation", "user-a", occurrence.id, queuedAt = 1)
        val duplicate = PendingCompletion("second-operation", "user-a", occurrence.id, queuedAt = 2)

        assertTrue(dao.queueCompletion(first, occurrence.copy(status = "completed")))
        assertEquals(false, dao.queueCompletion(duplicate, occurrence.copy(status = "completed")))
        assertEquals(listOf(first), dao.pendingCompletions("user-a"))
    }

    @Test
    fun permanentFailureAIsDeadLetteredAndDoesNotBlockValidB() = runBlocking {
        val dao = database.systemDao()
        val a = occurrence("permanent-a", "user-a")
        val b = occurrence("valid-b", "user-a")
        val itemA = PendingCompletion("stable-a", "user-a", a.id, queuedAt = 1)
        val itemB = PendingCompletion("stable-b", "user-a", b.id, queuedAt = 2)
        dao.queueCompletion(itemA, a.copy(status = "completed"))
        dao.queueCompletion(itemB, b.copy(status = "completed"))

        dao.setCompletionOutcome(
            userId = "user-a",
            key = itemA.idempotencyKey,
            state = "PERMANENT_FAILURE",
            retryCount = 0,
            attemptedAt = 10,
            nextAttemptAt = null,
            errorCode = "P0002",
            message = "Task occurrence not found",
        )

        assertEquals(listOf(itemB), dao.eligibleCompletions("user-a", now = 10))
        assertEquals("PERMANENT_FAILURE", dao.pendingCompletions("user-a").first().state)
        assertEquals("stable-a", dao.pendingCompletions("user-a").first().idempotencyKey)
    }

    @Test
    fun retryableFailureTracksBackoffAndKeepsOperationKey() = runBlocking {
        val dao = database.systemDao()
        val row = occurrence("retryable", "user-a")
        val item = PendingCompletion("stable-retry-key", "user-a", row.id)
        dao.queueCompletion(item, row.copy(status = "completed"))
        dao.setCompletionOutcome(
            userId = "user-a",
            key = item.idempotencyKey,
            state = "RETRYABLE",
            retryCount = 1,
            attemptedAt = 100,
            nextAttemptAt = 200,
            errorCode = "503",
            message = "Temporary sync problem",
        )

        assertTrue(dao.eligibleCompletions("user-a", now = 199).isEmpty())
        val due = dao.eligibleCompletions("user-a", now = 200).single()
        assertEquals(item.idempotencyKey, due.idempotencyKey)
        assertEquals(1, due.retryCount)
        assertEquals("RETRYABLE", due.state)
    }

    @Test
    fun authFailureRetainsQueueUntilLaterAuthenticatedReplay() = runBlocking {
        val dao = database.systemDao()
        val row = occurrence("auth-required", "user-a")
        val item = PendingCompletion("stable-auth-key", "user-a", row.id)
        dao.queueCompletion(item, row.copy(status = "completed"))

        dao.markCompletionsAuthRequired("user-a", 50, "Sign in again to sync this saved completion.")

        val retained = dao.pendingCompletions("user-a").single()
        assertEquals(item.idempotencyKey, retained.idempotencyKey)
        assertEquals("AUTH_REQUIRED", retained.state)
        // The repository does not request eligible queue items until a remote
        // authenticated identity is available again.
        assertEquals("stable-auth-key", dao.eligibleCompletions("user-a", now = 100).single().idempotencyKey)
    }

    @Test
    fun legacyRoomRowsMigrateWithoutAssumingAccountOwnership() = runBlocking {
        database.close()
        val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null)
        legacy.execSQL("CREATE TABLE cached_occurrences (id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, title TEXT NOT NULL, notes TEXT, scheduledFor TEXT, allDay INTEGER NOT NULL, status TEXT NOT NULL, completedAt TEXT)")
        legacy.execSQL("CREATE TABLE pending_completions (idempotencyKey TEXT NOT NULL PRIMARY KEY, occurrenceId TEXT NOT NULL, queuedAt INTEGER NOT NULL, lastError TEXT)")
        legacy.insertOrThrow(
            "cached_occurrences",
            null,
            ContentValues().apply {
                put("id", "legacy-occurrence")
                put("taskId", "legacy-task")
                put("title", "Legacy task")
                put("allDay", 1)
                put("status", "completed")
                put("completedAt", "2026-09-24T12:00:00Z")
            },
        )
        legacy.insertOrThrow(
            "pending_completions",
            null,
            ContentValues().apply {
                put("idempotencyKey", "legacy-key")
                put("occurrenceId", "legacy-occurrence")
                put("queuedAt", 1_790_280_000_000L)
            },
        )
        legacy.insertOrThrow(
            "pending_completions",
            null,
            ContentValues().apply {
                put("idempotencyKey", "duplicate-legacy-key")
                put("occurrenceId", "legacy-occurrence")
                put("queuedAt", 1_790_280_000_001L)
            },
        )
        legacy.version = 1
        legacy.close()

        database = openDatabase()
        val dao = database.systemDao()
        assertEquals(null, dao.occurrence("user-a", "legacy-occurrence"))
        database.openHelper.writableDatabase.query(
            "SELECT kind FROM cached_occurrences WHERE id = ?",
            arrayOf("legacy-occurrence"),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("task", cursor.getString(0))
        }
        assertEquals("", dao.legacyPendingCompletions().single().userId)
        assertEquals("legacy-key", dao.legacyPendingCompletions().single().idempotencyKey)
        assertEquals("PENDING", dao.legacyPendingCompletions().single().state)

        // Production claims a legacy queue item only after its occurrence is
        // visible through the currently authenticated user's RLS policy.
        dao.adoptLegacyCompletion("user-a", "legacy-key")
        assertEquals("user-a", dao.pendingCompletions("user-a").single().userId)
        assertTrue(dao.pendingCompletions("user-b").isEmpty())
    }

    private fun occurrence(id: String, userId: String) = CachedOccurrence(
        id = id,
        userId = userId,
        taskId = "task-$id",
        title = id,
        notes = null,
        scheduledFor = null,
        allDay = false,
        kind = "task",
        status = "pending",
        completedAt = null,
    )

    private fun openDatabase() = Room.databaseBuilder(context, SystemDatabase::class.java, databaseName)
        .allowMainThreadQueries()
        .addMigrations(
            SystemDatabase.MIGRATION_1_2,
            SystemDatabase.MIGRATION_2_3,
            SystemDatabase.MIGRATION_3_4,
            SystemDatabase.MIGRATION_4_5,
        )
        .build()
}
