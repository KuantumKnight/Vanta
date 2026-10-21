package dev.system.app.focus

import dev.system.app.data.CachedOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FocusSessionStoreTest {
    @Test
    fun activeSessionRecoversFromAnotherStoreInstance() {
        val persistence = InMemoryFocusSessionPersistence()
        val occurrence = occurrence()
        val firstStore = FocusSessionStore(persistence) { 1_000L }
        val started = firstStore.start("user-1", occurrence, plannedDurationSeconds = 300L)

        val recovered = FocusSessionStore(persistence).activeSession("user-1")

        assertEquals(started, recovered)
    }

    @Test
    fun onlyOneActiveSessionIsAllowedPerUserAndFinishedSessionCanBeReplaced() {
        val persistence = InMemoryFocusSessionPersistence()
        val store = FocusSessionStore(persistence) { 1_000L }
        store.start("user-1", occurrence("occ-1"), plannedDurationSeconds = 300L)

        assertThrows(IllegalStateException::class.java) {
            store.start("user-1", occurrence("occ-2"), plannedDurationSeconds = 300L)
        }

        val ended = store.endEarly("user-1", finishedAtEpochMillis = 31_000L)
        assertEquals(FocusSessionStatus.ENDED_EARLY, ended.status)
        assertNull(store.activeSession("user-1"))
        assertEquals(ended, store.start("user-1", occurrence("occ-2"), plannedDurationSeconds = 300L))
    }

    @Test
    fun sessionsAreIsolatedByUser() {
        val persistence = InMemoryFocusSessionPersistence()
        val store = FocusSessionStore(persistence) { 1_000L }
        val first = store.start("user-1", occurrence("occ-1"), plannedDurationSeconds = 300L)
        val second = store.start("user-2", occurrence("occ-2"), plannedDurationSeconds = 600L)

        assertEquals(first, store.activeSession("user-1"))
        assertEquals(second, store.activeSession("user-2"))
    }

    @Test
    fun occurrenceReconciliationPersistsAndOnlyChangesMatchingUsersSession() {
        val persistence = InMemoryFocusSessionPersistence()
        val store = FocusSessionStore(persistence) { 1_000L }
        val first = store.start("user-1", occurrence("local:operation-1"), plannedDurationSeconds = 300L)
        val second = store.start("user-2", occurrence("local:operation-1"), plannedDurationSeconds = 300L)

        assertEquals(true, store.reconcileOccurrenceId("user-1", "local:operation-1", "server-occurrence"))
        assertEquals("server-occurrence", FocusSessionStore(persistence).activeSession("user-1")?.occurrenceId)
        assertEquals(second, FocusSessionStore(persistence).activeSession("user-2"))
        assertEquals(false, store.reconcileOccurrenceId("user-1", "local:operation-1", "server-occurrence"))
        assertEquals("local:operation-1", first.occurrenceId)
    }

    @Test
    fun completionLeavesFinishedSessionAvailableWithActualElapsedTime() {
        val store = FocusSessionStore(InMemoryFocusSessionPersistence()) { 50_000L }
        store.start("user-1", occurrence(), plannedDurationSeconds = 600L, startedAtEpochMillis = 10_000L)

        val completed = store.complete("user-1")

        assertEquals(FocusSessionStatus.COMPLETED, completed.status)
        assertEquals(40_000L, completed.actualElapsedMillis(nowEpochMillis = 100_000L))
        assertEquals(completed, store.latestSession("user-1"))
        assertNull(store.activeSession("user-1"))
    }

    @Test
    fun completingWithoutActiveSessionFails() {
        val store = FocusSessionStore(InMemoryFocusSessionPersistence())

        assertThrows(IllegalStateException::class.java) { store.complete("user-1", 1_000L) }
    }

    private fun occurrence(id: String = "occ-1") = CachedOccurrence(
        id = id,
        taskId = "task-$id",
        title = "Study",
        notes = null,
        scheduledFor = null,
        allDay = false,
        kind = "task",
        status = "pending",
        completedAt = null,
        userId = "user-1",
        durationMinutes = null,
    )
}

private class InMemoryFocusSessionPersistence : FocusSessionPersistence {
    private val sessions = mutableMapOf<String, FocusSession>()

    override fun read(userId: String): FocusSession? = sessions[userId]

    override fun write(session: FocusSession) {
        sessions[session.userId] = session
    }
}
