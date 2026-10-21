package dev.system.app.ui.navigation

import dev.system.app.analytics.AnalyticsPeriod
import dev.system.app.data.FocusSessionHistory
import dev.system.app.data.OccurrenceHistory
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchFourDashboardDataTest {
    private val today = LocalDate.of(2026, 10, 21)

    @Test
    fun habitsUseExpectedDatesSkippedIsNeutralAndWeekStartIsPersonalized() {
        val result = buildHabitDashboardData(
            history = listOf(
                habit("h1", "2026-10-18", "completed"),
                habit("h2", "2026-10-19", "skipped"),
                habit("h3", "2026-10-20", "completed"),
                habit("h4", "2026-10-21", "pending"),
            ),
            today = today,
            weekStartDay = DayOfWeek.SUNDAY,
        )

        val summary = result.summaries.single()
        val detail = result.details.getValue("habit-1")
        assertEquals(2, summary.currentStreak)
        assertEquals(2, summary.bestStreak)
        assertEquals("expected occurrences", summary.streakUnitLabel)
        assertEquals(LocalDate.of(2026, 10, 18), detail.thisWeek.first().date)
        assertEquals(today, detail.thisWeek.last().date)
        assertEquals(365, detail.history.size)
        assertTrue(detail.scheduleDescription.contains("Skipped occurrences are neutral"))
    }

    @Test
    fun historyProjectionDropsFutureAndUnscheduledRows() {
        val result = buildHabitDashboardData(
            history = listOf(
                habit("past", "2026-10-20", "completed"),
                habit("future", "2026-10-22", "completed"),
                habit("undated", null, "pending"),
            ),
            today = today,
            weekStartDay = DayOfWeek.MONDAY,
        )

        assertEquals(0, result.summaries.single().todayOccurrences.size)
        assertEquals(1, result.details.getValue("habit-1").history.count { it.status != null })
    }

    @Test
    fun analyticsCombinesFocusOnlyDaysAndUsesActualSessionElapsedTime() {
        val occurrences = listOf(
            task("t1", "2026-10-21", "completed", 60),
            task("t2", "2026-10-21", "completed", 30),
            task("t3", "2026-10-20", "pending", 60),
        )
        val sessions = listOf(
            focus("t1", "2026-10-21T09:00:00Z", 75),
            focus("t2", "2026-10-21T10:00:00Z", 30),
            focus("t3", "2026-10-20T11:00:00Z", 75),
            focus("orphan", "2026-10-19T10:00:00Z", 12),
        )

        val summary = buildAnalyticsSummary(today, AnalyticsPeriod.SEVEN_DAYS, occurrences, sessions)

        assertEquals(150, summary.plannedMinutes)
        assertEquals(192, summary.focusedMinutes)
        assertEquals(3, summary.estimationSampleCount)
        assertEquals(20.0, summary.estimationDifferencePercent!!, 0.001)
        assertEquals(12, summary.days.first { it.date == LocalDate.of(2026, 10, 19) }.focusedMinutes)
    }

    @Test
    fun analyticsDoesNotInventFocusForActiveSessionsOrSkippedTasks() {
        val skippedTask = task("skip", "2026-10-21", "skipped", 45)
        val activeSession = FocusSessionHistory(
            sessionId = "active",
            userId = "user-1",
            occurrenceId = "skip",
            occurrenceTitle = "Skipped task",
            plannedDurationSeconds = 2_700,
            startedAtEpochMillis = 1_792_573_200_000,
            finishedAtEpochMillis = null,
            status = "ACTIVE",
        )

        val summary = buildAnalyticsSummary(today, AnalyticsPeriod.SEVEN_DAYS, listOf(skippedTask), listOf(activeSession))

        assertEquals(0, summary.plannedTaskCount)
        assertNull(summary.focusedMinutes)
        assertNull(summary.estimationDifferencePercent)
    }

    @Test
    fun optionalEveningReviewMetricsKeepMissedAndSkippedDistinct() {
        val review = buildEveningReviewSummary(
            date = today,
            occurrences = listOf(
                task("done", "2026-10-21", "completed", 30),
                task("open", "2026-10-21", "pending", 30),
                task("skip", "2026-10-21", "skipped", 30),
            ),
            focusSessions = emptyList(),
        )

        assertEquals(3, review.plannedCount)
        assertEquals(1, review.completedCount)
        assertEquals(1, review.remainingCount)
        assertEquals(1, review.skippedCount)
        assertNull(review.focusMinutes)
    }

    private fun habit(id: String, date: String?, status: String): OccurrenceHistory = row(
        id = id,
        taskId = "habit-1",
        kind = "habit",
        date = date,
        status = status,
        duration = null,
    )

    private fun task(id: String, date: String, status: String, duration: Int): OccurrenceHistory = row(
        id = id,
        taskId = "task-$id",
        kind = "task",
        date = date,
        status = status,
        duration = duration,
    )

    private fun row(
        id: String,
        taskId: String,
        kind: String,
        date: String?,
        status: String,
        duration: Int?,
    ) = OccurrenceHistory(
        userId = "user-1",
        occurrenceId = id,
        taskId = taskId,
        title = id,
        scheduledDate = date,
        scheduledFor = date?.let { "${it}T09:00:00Z" },
        allDay = false,
        kind = kind,
        status = status,
        completedAt = null,
        durationMinutes = duration,
    )

    private fun focus(id: String, startedAt: String, minutes: Int): FocusSessionHistory {
        val start = java.time.Instant.parse(startedAt).toEpochMilli()
        return FocusSessionHistory(
            sessionId = "session-$id",
            userId = "user-1",
            occurrenceId = id,
            occurrenceTitle = id,
            plannedDurationSeconds = minutes * 60L,
            startedAtEpochMillis = start,
            finishedAtEpochMillis = start + minutes * 60_000L,
            status = "COMPLETED",
        )
    }
}
