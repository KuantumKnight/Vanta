package dev.system.app.eveningreview

import dev.system.app.data.CachedOccurrence
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EveningReviewTest {
    private val today = LocalDate.of(2026, 10, 21)

    @Test
    fun allCompleteSummaryHasNoRemainingOccurrences() {
        val result = EveningReviewCalculator.calculate(
            today,
            listOf(
                occurrence("a", "completed", "2026-10-21T03:30:00Z"),
                occurrence("b", "completed", "2026-10-21T05:00:00Z"),
            ),
            focusMinutes = 45,
        )

        assertEquals(2, result.plannedCount)
        assertEquals(2, result.completedCount)
        assertTrue(result.remaining.isEmpty())
        assertEquals(45L, result.focusMinutes)
    }

    @Test
    fun partialDaySeparatesCompletedPendingAndSkipped() {
        val result = EveningReviewCalculator.calculate(
            today,
            listOf(
                occurrence("done", "completed", "2026-10-21T03:00:00Z"),
                occurrence("open", "pending", "2026-10-21T04:00:00Z"),
                occurrence("skip", "skipped", "2026-10-21T05:00:00Z"),
            ),
        )

        assertEquals(3, result.plannedCount)
        assertEquals(listOf("done"), result.completed.map { it.id })
        assertEquals(listOf("open"), result.remaining.map { it.id })
        assertEquals(listOf("skip"), result.skipped.map { it.id })
    }

    @Test
    fun noTasksAndRowsOutsideLocalDayProduceEmptyReview() {
        val result = EveningReviewCalculator.calculate(
            today,
            listOf(
                occurrence("yesterday", "pending", "2026-10-20T18:29:59Z"),
                occurrence("tomorrow", "pending", "2026-10-21T18:30:00Z"),
                occurrence("undated", "pending", null),
            ),
        )

        assertTrue(result.planned.isEmpty())
        assertEquals(0, result.plannedCount)
    }

    @Test
    fun eachExplicitDispositionCanBeKeptInReviewState() {
        val actions = listOf(
            ReviewAction.MOVE_TO_TOMORROW,
            ReviewAction.KEEP_CURRENT_DEADLINE,
            ReviewAction.RESCHEDULE,
            ReviewAction.SKIP,
        )
        val decisions = actions.fold(ReviewDecisions()) { state, action ->
            state.choose(action.name, action)
        }

        actions.forEach { action -> assertEquals(action, decisions.actionFor(action.name)) }
    }

    @Test
    fun keepingDeadlineIsAnExplicitDecisionAndDoesNotChangeOccurrence() {
        val row = occurrence("deadline", "pending", "2026-10-21T03:00:00Z")
        val kept = ReviewDecisions().choose(row.id, ReviewAction.KEEP_CURRENT_DEADLINE)

        assertEquals(ReviewAction.KEEP_CURRENT_DEADLINE, kept.actionFor(row.id))
        assertEquals("2026-10-21T03:00:00Z", row.scheduledFor)
    }

    @Test
    fun locallyCreatedOccurrenceRemainsEligibleForOfflineReview() {
        val row = occurrence("local:offline-create", "pending", "2026-10-21T03:00:00Z")
        val result = EveningReviewCalculator.calculate(today, listOf(row))

        assertEquals(listOf("local:offline-create"), result.remaining.map { it.id })
    }

    private fun occurrence(id: String, status: String, scheduledFor: String?) = CachedOccurrence(
        id = id,
        taskId = "task-$id",
        title = "Task $id",
        notes = null,
        scheduledFor = scheduledFor,
        allDay = false,
        kind = "task",
        status = status,
        completedAt = if (status == "completed") "2026-10-21T06:00:00Z" else null,
    )
}
