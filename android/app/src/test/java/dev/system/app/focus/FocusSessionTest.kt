package dev.system.app.focus

import dev.system.app.data.CachedOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusSessionTest {
    @Test
    fun derivesElapsedAndRemainingFromTimestamps() {
        val session = session(start = 10_000L, plannedSeconds = 60L)

        assertEquals(0L, session.actualElapsedMillis(nowEpochMillis = 10_000L))
        assertEquals(30_000L, session.remainingMillis(nowEpochMillis = 40_000L))
        assertEquals(60_000L, session.actualElapsedMillis(nowEpochMillis = 70_000L))
        assertEquals(0L, session.remainingMillis(nowEpochMillis = 80_000L))
    }

    @Test
    fun completedSessionRecordsActualDurationAndMayExceedPlan() {
        val completed = session(start = 10_000L, plannedSeconds = 60L).complete(100_000L)

        assertEquals(FocusSessionStatus.COMPLETED, completed.status)
        assertEquals(90_000L, completed.actualElapsedMillis(nowEpochMillis = 200_000L))
        assertEquals(0L, completed.remainingMillis(nowEpochMillis = 100_000L))
        assertTrue(completed.finishedAtEpochMillis == 100_000L)
    }

    @Test
    fun endEarlyRecordsElapsedDurationAndClampsClockRegression() {
        val ended = session(start = 10_000L, plannedSeconds = 60L).endEarly(5_000L)

        assertEquals(FocusSessionStatus.ENDED_EARLY, ended.status)
        assertEquals(0L, ended.actualElapsedMillis(nowEpochMillis = 50_000L))
        assertEquals(10_000L, ended.finishedAtEpochMillis)
    }

    @Test
    fun derivesDefaultOrValidOccurrenceDuration() {
        assertEquals(50L * 60L, FocusSession.plannedDurationSeconds(occurrence(durationMinutes = 50)))
        assertEquals(
            FocusSession.DEFAULT_PLANNED_DURATION_SECONDS,
            FocusSession.plannedDurationSeconds(occurrence(durationMinutes = 4)),
        )
        assertEquals(
            FocusSession.DEFAULT_PLANNED_DURATION_SECONDS,
            FocusSession.plannedDurationSeconds(occurrence(durationMinutes = null)),
        )
    }

    @Test
    fun finishedSessionCannotBeFinishedAgain() {
        val completed = session().complete(20_000L)

        val canFinishAgain = runCatching { completed.endEarly(30_000L) }.isSuccess

        assertFalse(canFinishAgain)
    }

    private fun session(start: Long = 10_000L, plannedSeconds: Long = 60L) = FocusSession(
        userId = "user-1",
        occurrenceId = "occ-1",
        occurrenceTitle = "Study",
        plannedDurationSeconds = plannedSeconds,
        startedAtEpochMillis = start,
    )

    private fun occurrence(durationMinutes: Int?) = CachedOccurrence(
        id = "occ-1",
        taskId = "task-1",
        title = "Study",
        notes = null,
        scheduledFor = null,
        allDay = false,
        kind = "task",
        status = "pending",
        completedAt = null,
        userId = "user-1",
        durationMinutes = durationMinutes,
    )
}
