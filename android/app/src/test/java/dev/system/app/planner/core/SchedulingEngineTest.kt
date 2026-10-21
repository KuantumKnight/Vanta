package dev.system.app.planner.core

import dev.system.app.planner.FlexibleTask
import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.SchedulePriority
import dev.system.app.planner.ScheduleSource
import dev.system.app.planner.ScheduleType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulingEngineTest {
    private val start = Instant.parse("2026-10-21T07:00:00Z")
    private val end = Instant.parse("2026-10-21T20:00:00Z")

    @Test
    fun freeIntervalsClipBoundsMergeOverlapsAndSkipZeroLengthBlocks() {
        val free = findFreeIntervals(
            start,
            end,
            listOf(
                block("outside-left", start.minusSeconds(3600), start.plusSeconds(3600)),
                block("class", start.plusSeconds(3600), start.plusSeconds(3 * 3600)),
                block("overlap", start.plusSeconds(2 * 3600), start.plusSeconds(4 * 3600)),
                block("zero", start.plusSeconds(5 * 3600), start.plusSeconds(5 * 3600)),
                block("outside-right", end, end.plusSeconds(3600)),
            ),
        )

        assertEquals(
            listOf(
                start.plusSeconds(4 * 3600) to end,
            ),
            free.map { it.start to it.end },
        )
    }

    @Test
    fun emptyAndFullyOccupiedBoundsAreHandled() {
        assertEquals(listOf(start to end), findFreeIntervals(start, end, emptyList()).map { it.start to it.end })
        assertTrue(findFreeIntervals(start, end, listOf(block("busy", start.minusSeconds(60), end))).isEmpty())
        assertTrue(findFreeIntervals(end, start, emptyList()).isEmpty())
    }

    @Test
    fun canPlaceRespectsDurationEarliestLatestDeadlineAndOverlap() {
        val task = FlexibleTask(
            occurrenceId = "task-a",
            title = "Study",
            durationMinutes = 90,
            earliestStart = start.plusSeconds(3600),
            latestEnd = start.plusSeconds(5 * 3600),
            deadlineAt = start.plusSeconds(5 * 3600),
        )
        val blockers = listOf(block("class", start.plusSeconds(2 * 3600), start.plusSeconds(3 * 3600)))

        assertFalse(canPlace(task, start, start, end, blockers))
        assertFalse(canPlace(task, start.plusSeconds(90 * 60), start, end, blockers))
        assertFalse(canPlace(task, start.plusSeconds(4 * 3600), start, end, blockers))
        assertFalse(canPlace(task.copy(deadlineAt = start.plusSeconds(2 * 3600)), start.plusSeconds(90 * 60), start, end, emptyList()))
        assertTrue(canPlace(task, start.plusSeconds(3 * 3600), start, end, blockers))
    }

    @Test
    fun candidateSlotsKeepDurationAndRankPriorityPreferenceAndDeadline() {
        val preference = start.plusSeconds(5 * 3600)
        val high = FlexibleTask(
            occurrenceId = "high",
            title = "Exam prep",
            durationMinutes = 60,
            priority = SchedulePriority.HIGH,
            preferredStart = preference,
            deadlineAt = start.plusSeconds(7 * 3600),
        )
        val blockers = listOf(block("class", start, start.plusSeconds(2 * 3600)))
        val candidates = findCandidateSlots(
            task = high,
            planningStart = start,
            planningEnd = start.plusSeconds(8 * 3600),
            blocks = blockers,
            now = start,
            snapMinutes = 15,
        )

        assertTrue(candidates.isNotEmpty())
        assertEquals(preference, candidates.first().start)
        assertEquals(preference.plusSeconds(3600), candidates.first().end)
        assertTrue(candidates.first().scoreReasons.any { it.startsWith("High priority") })
        assertTrue(candidates.first().scoreReasons.any { it.startsWith("Preferred-time") })
        assertTrue(candidates.first().scoreReasons.any { it.startsWith("Deadline urgency") })
    }

    @Test
    fun candidateSlotsReturnEmptyWhenNoGapFitsOrDeadlineIsImpossible() {
        val task = FlexibleTask("task-a", "Long study", durationMinutes = 120)
        val blocker = block("meeting", start, end)
        assertTrue(findCandidateSlots(task, start, end, listOf(blocker)).isEmpty())
        assertTrue(
            findCandidateSlots(
                task.copy(deadlineAt = start.plusSeconds(60 * 60)),
                start,
                end,
                emptyList(),
            ).isEmpty(),
        )
    }

    @Test
    fun timezonePlanningWindowUsesActualZonedDayBoundaries() {
        val zone = ZoneId.of("Europe/Berlin")
        val springForward = localPlanningWindow(
            date = LocalDate.parse("2026-03-29"),
            zoneId = zone,
            startTime = LocalTime.MIDNIGHT,
            endTime = LocalTime.of(4, 0),
        )
        val fallBack = localPlanningWindow(
            date = LocalDate.parse("2026-10-25"),
            zoneId = zone,
            startTime = LocalTime.MIDNIGHT,
            endTime = LocalTime.of(4, 0),
        )

        assertEquals(180, springForward.durationMinutes)
        assertEquals(300, fallBack.durationMinutes)
    }

    @Test
    fun candidatesRespectWindowEndExactlyAndDoNotTreatTouchingBlocksAsOverlap() {
        val task = FlexibleTask("task-a", "Review", durationMinutes = 60)
        val block = block("class", start.plusSeconds(60 * 60), start.plusSeconds(2 * 60 * 60))
        assertTrue(canPlace(task, block.end, start, end, listOf(block)))
        assertTrue(canPlace(task, end.minusSeconds(60 * 60), start, end, emptyList()))
        assertFalse(canPlace(task, end.minusSeconds(59 * 60), start, end, emptyList()))
    }

    @Test
    fun durationBoundariesMatchTaskContract() {
        val fiveMinuteTask = FlexibleTask("five", "Five", durationMinutes = 5)
        val shortTask = fiveMinuteTask.copy(durationMinutes = 4)
        assertTrue(canPlace(fiveMinuteTask, start, start, end, emptyList()))
        assertFalse(canPlace(shortTask, start, start, end, emptyList()))
        assertTrue(findCandidateSlots(fiveMinuteTask, start, start.plusSeconds(5 * 60), emptyList()).isNotEmpty())
        assertTrue(findCandidateSlots(shortTask, start, end, emptyList()).isEmpty())

        val fullDay = FlexibleTask("day", "Full day", durationMinutes = 1440)
        val tooLong = fullDay.copy(durationMinutes = 1441)
        val oneDayEnd = start.plusSeconds(24 * 60 * 60)
        assertTrue(canPlace(fullDay, start, start, oneDayEnd, emptyList()))
        assertFalse(canPlace(tooLong, start, start, end, emptyList()))
        assertTrue(findCandidateSlots(fullDay, start, oneDayEnd, emptyList()).isNotEmpty())
        assertTrue(findCandidateSlots(tooLong, start, oneDayEnd, emptyList()).isEmpty())
    }

    @Test
    fun priorityAndDeadlineAffectCandidateScoreAndADeadlineNeverActsAsAStart() {
        val targetStart = start.plusSeconds(2 * 3600)
        val interval = FreeInterval(start, end)
        val low = FlexibleTask("low", "Low", 60, SchedulePriority.LOW, deadlineAt = targetStart.plusSeconds(3600))
        val high = low.copy(occurrenceId = "high", priority = SchedulePriority.HIGH)
        val lowScore = scoreCandidate(low, targetStart, targetStart.plusSeconds(3600), interval).score
        val highScore = scoreCandidate(high, targetStart, targetStart.plusSeconds(3600), interval).score
        assertTrue(highScore > lowScore)

        val deadlineOnlyTask = FlexibleTask("deadline-only", "Due", 60, deadlineAt = targetStart)
        val slots = findCandidateSlots(deadlineOnlyTask, start, end, emptyList(), now = start)
        assertTrue(slots.all { it.end <= targetStart })
        assertTrue(slots.none { it.start == targetStart })
    }

    private fun block(id: String, from: Instant, until: Instant) = ScheduleBlock(
        id = id,
        source = ScheduleSource.VANTA_TASK,
        title = id,
        start = from,
        end = until,
        scheduleType = ScheduleType.HARD,
        editable = false,
    )
}
