package dev.system.app.planner.conflicts

import dev.system.app.planner.ConflictType
import dev.system.app.planner.DaySchedule
import dev.system.app.planner.FlexibleTask
import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.SchedulePriority
import dev.system.app.planner.ScheduleSource
import dev.system.app.planner.ScheduleType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictEngineTest {
    private val start = Instant.parse("2026-10-21T07:00:00Z")
    private val end = Instant.parse("2026-10-21T20:00:00Z")
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test
    fun classifiesOverlapsAndTreatsNewCalendarEventsAsHardBlocks() {
        val schedule = day(
            blocks = listOf(
                block("class", start, start.plusSeconds(2 * 3600), ScheduleType.HARD),
                block("study", start.plusSeconds(3600), start.plusSeconds(3 * 3600), ScheduleType.SOFT),
                block(
                    "calendar-meeting",
                    start.plusSeconds(90 * 60),
                    start.plusSeconds(150 * 60),
                    ScheduleType.HARD,
                    source = ScheduleSource.GOOGLE_CALENDAR,
                ),
                block("study-two", start.plusSeconds(150 * 60), start.plusSeconds(210 * 60), ScheduleType.SOFT),
            ),
        )

        val conflicts = detectScheduleConflicts(schedule)

        assertEquals(2, conflicts.count { it.type == ConflictType.HARD_SOFT })
        assertEquals(1, conflicts.count { it.type == ConflictType.HARD_HARD })
        assertEquals(1, conflicts.count { it.type == ConflictType.SOFT_SOFT })
        assertTrue(conflicts.any { "calendar-meeting" in it.blockIds })
    }

    @Test
    fun detectsInsufficientSpaceAndDeadlineRiskLocally() {
        val task = FlexibleTask(
            occurrenceId = "assignment",
            title = "Assignment",
            durationMinutes = 60,
            priority = SchedulePriority.HIGH,
            deadlineAt = start.plusSeconds(30 * 60),
        )
        val schedule = day(flexibleTasks = listOf(task))

        val conflicts = detectScheduleConflicts(schedule, now = start)

        assertTrue(conflicts.any { it.type == ConflictType.DEADLINE_RISK && it.blockIds == listOf("assignment") })
        assertFalse(conflicts.any { it.type == ConflictType.INSUFFICIENT_SPACE && it.blockIds == listOf("assignment") })

        val noSpace = day(
            blocks = listOf(block("class", start, end, ScheduleType.HARD)),
            flexibleTasks = listOf(task.copy(deadlineAt = null)),
        )
        assertTrue(detectScheduleConflicts(noSpace, now = start).any { it.type == ConflictType.INSUFFICIENT_SPACE })
    }

    @Test
    fun overrunOnlyListsFutureBlocksInsideTheDelayWindow() {
        val plannedEnd = start.plusSeconds(3 * 3600)
        val actualEnd = plannedEnd.plusSeconds(30 * 60)
        val schedule = day(
            blocks = listOf(
                block("affected", plannedEnd.plusSeconds(10 * 60), plannedEnd.plusSeconds(70 * 60), ScheduleType.SOFT),
                block("already-running", plannedEnd.minusSeconds(10 * 60), plannedEnd.plusSeconds(20 * 60), ScheduleType.SOFT),
                block("unaffected", actualEnd.plusSeconds(60), actualEnd.plusSeconds(3600), ScheduleType.SOFT),
            ),
        )

        val conflict = detectFocusOverrun(schedule, "focus-task", plannedEnd, actualEnd)

        assertEquals(ConflictType.OVERRUN, conflict?.type)
        assertEquals(listOf("focus-task", "affected", "already-running"), conflict?.blockIds)
        assertTrue(conflict?.message?.contains("30 min") == true)
        assertEquals(null, detectFocusOverrun(schedule, "focus-task", actualEnd, actualEnd))
    }

    @Test
    fun newExternalEventMakesTheBestAlternativeMoveTheSoftTask() {
        val originalStart = start.plusSeconds(3 * 3600)
        val soft = block("gym", originalStart, originalStart.plusSeconds(60 * 60), ScheduleType.SOFT)
        val meeting = block(
            "calendar-meeting",
            originalStart,
            originalStart.plusSeconds(60 * 60),
            ScheduleType.HARD,
            source = ScheduleSource.GOOGLE_CALENDAR,
        )
        val schedule = day(blocks = listOf(soft, meeting))

        val alternatives = findConflictAlternatives(schedule, "gym", now = start, maxCandidates = 3)

        assertTrue(alternatives.isNotEmpty())
        assertTrue(alternatives.none { it.start == originalStart })
        assertTrue(alternatives.zipWithNext().all { (left, right) -> left.score >= right.score })
        assertTrue(alternatives.all { it.end == it.start.plusSeconds(3600) })
        assertTrue(findConflictAlternatives(day(blocks = listOf(soft, meeting), completed = setOf("gym")), "gym", now = start).isEmpty())
    }

    @Test
    fun softTaskAlternativesRespectItsStoredDeadlineAndPlacementWindow() {
        val originalStart = start.plusSeconds(3 * 3600)
        val earliest = start.plusSeconds(5 * 3600)
        val deadline = start.plusSeconds(7 * 3600)
        val soft = block("gym", originalStart, originalStart.plusSeconds(3600), ScheduleType.SOFT).copy(
            priority = SchedulePriority.HIGH,
            deadlineAt = deadline,
            earliestStart = earliest,
            latestEnd = deadline,
            preferredStart = originalStart,
        )
        val calendar = block(
            "calendar-meeting",
            originalStart,
            originalStart.plusSeconds(3600),
            ScheduleType.HARD,
            source = ScheduleSource.GOOGLE_CALENDAR,
        )

        val alternatives = findConflictAlternatives(day(blocks = listOf(soft, calendar)), "gym", now = start, maxCandidates = 5)

        assertTrue(alternatives.isNotEmpty())
        assertTrue(alternatives.all { it.start >= earliest })
        assertTrue(alternatives.all { it.end <= deadline })
    }

    @Test
    fun noAlternativeExistsWhenHardBlocksFillTheRemainingWindow() {
        val originalStart = start.plusSeconds(2 * 3600)
        val soft = block("gym", originalStart, originalStart.plusSeconds(3600), ScheduleType.SOFT)
        val hard = block("all-day-class", start, end, ScheduleType.HARD)

        assertTrue(findConflictAlternatives(day(blocks = listOf(soft, hard)), "gym", now = start).isEmpty())
    }

    @Test
    fun replanMovesOnlyFutureUnfinishedWorkAndPreservesHardPastAndCompletedBlocks() {
        val now = start.plusSeconds(2 * 3600)
        val past = block("past", start, now, ScheduleType.SOFT)
        val complete = block("complete", now.plusSeconds(3600), now.plusSeconds(2 * 3600), ScheduleType.SOFT)
        val hard = block("appointment", now.plusSeconds(3 * 3600), now.plusSeconds(4 * 3600), ScheduleType.HARD)
        val future = block("gym", now.plusSeconds(30 * 60), now.plusSeconds(90 * 60), ScheduleType.SOFT)
        val flexible = FlexibleTask("revision", "Revision", 60, preferredStart = now.plusSeconds(5 * 3600))
        val schedule = day(
            blocks = listOf(past, complete, hard, future),
            flexibleTasks = listOf(
                flexible,
                FlexibleTask("past", "Past", 60),
                FlexibleTask("complete", "Complete", 60),
                FlexibleTask("appointment", "Appointment", 60),
            ),
            completed = setOf("complete"),
        )

        val proposal = proposeRemainingDayReplan(schedule, now)

        assertEquals(setOf("gym", "revision"), proposal.placements.map { it.occurrenceId }.toSet())
        assertFalse(proposal.placements.any { it.occurrenceId in setOf("past", "complete", "appointment") })
        assertTrue(proposal.placements.all { it.start >= now })
        assertTrue(proposal.placements.none { item -> item.start < hard.end && hard.start < item.end })
    }

    @Test
    fun replanExplainsTasksWithNoAvailableFutureSlot() {
        val now = start.plusSeconds(2 * 3600)
        val hard = block("meeting", now, end, ScheduleType.HARD)
        val task = FlexibleTask("reading", "Reading", 60, deadlineAt = end)

        val proposal = proposeRemainingDayReplan(day(blocks = listOf(hard), flexibleTasks = listOf(task)), now)

        assertTrue(proposal.placements.isEmpty())
        assertEquals("reading", proposal.unscheduled.single().occurrenceId)
        assertTrue(proposal.unscheduled.single().reason.contains("No available slot"))
    }

    private fun day(
        blocks: List<ScheduleBlock> = emptyList(),
        flexibleTasks: List<FlexibleTask> = emptyList(),
        completed: Set<String> = emptySet(),
    ) = DaySchedule(
        date = LocalDate.parse("2026-10-21"),
        zoneId = zone,
        planningStart = start,
        planningEnd = end,
        blocks = blocks,
        flexibleTasks = flexibleTasks,
        completedOccurrenceIds = completed,
    )

    private fun block(
        id: String,
        blockStart: Instant,
        blockEnd: Instant,
        type: ScheduleType,
        source: ScheduleSource = ScheduleSource.VANTA_TASK,
    ) = ScheduleBlock(
        id = id,
        source = source,
        title = id,
        start = blockStart,
        end = blockEnd,
        scheduleType = type,
        editable = type == ScheduleType.SOFT,
    )
}
