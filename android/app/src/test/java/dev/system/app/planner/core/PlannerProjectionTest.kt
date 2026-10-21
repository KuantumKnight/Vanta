package dev.system.app.planner.core

import dev.system.app.data.CachedOccurrence
import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.SchedulePriority
import dev.system.app.planner.ScheduleSource
import dev.system.app.planner.ScheduleType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlannerProjectionTest {
    private val date = LocalDate.parse("2026-10-21")
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test
    fun projectionKeepsHardSoftCalendarAndFlexibleItemsSeparate() {
        val hard = occurrence(
            id = "hard",
            scheduledFor = "2026-10-21T03:30:00Z",
            scheduleType = "HARD",
            durationMinutes = 60,
        )
        val soft = occurrence(
            id = "soft",
            scheduledFor = "2026-10-21T05:00:00Z",
            scheduleType = "SOFT",
            durationMinutes = 45,
            priority = "HIGH",
            deadlineAt = "2026-10-21T06:00:00Z",
            earliestStart = "2026-10-21T05:00:00Z",
            latestEnd = "2026-10-21T06:00:00Z",
            preferredStart = "2026-10-21T05:15:00Z",
        )
        val flexible = occurrence(
            id = "flexible",
            scheduledFor = null,
            scheduleType = "FLEXIBLE",
            durationMinutes = 90,
            priority = "HIGH",
            deadlineAt = "2026-10-21T14:30:00Z",
        )
        val outsideFlexible = occurrence(
            id = "outside",
            scheduledFor = null,
            scheduleType = "FLEXIBLE",
            durationMinutes = 30,
            deadlineAt = "2026-10-22T14:30:00Z",
        )
        val calendar = ScheduleBlock(
            id = "google:opaque:event",
            source = ScheduleSource.GOOGLE_CALENDAR,
            title = "Class",
            start = Instant.parse("2026-10-21T04:30:00Z"),
            end = Instant.parse("2026-10-21T05:15:00Z"),
            scheduleType = ScheduleType.HARD,
            editable = false,
        )

        val schedule = buildDaySchedule(
            date = date,
            zoneId = zone,
            planningStartTime = LocalTime.of(7, 0),
            planningEndTime = LocalTime.of(23, 0),
            occurrences = listOf(hard, soft, flexible, outsideFlexible),
            calendarBlocks = listOf(calendar),
            now = Instant.parse("2026-10-21T00:00:00Z"),
            includeUnboundedFlexibleTasks = false,
        )
        val context = schedule.toPlanningContext()

        assertEquals(listOf("hard", "google:opaque:event", "soft"), schedule.blocks.map { it.id })
        assertEquals(listOf("flexible"), schedule.flexibleTasks.map { it.occurrenceId })
        assertEquals(SchedulePriority.HIGH, schedule.flexibleTasks.single().priority)
        assertEquals(Instant.parse("2026-10-21T14:30:00Z"), schedule.flexibleTasks.single().deadlineAt)
        assertNull(context.flexibleTasks.single().preferredStart)
        assertEquals(listOf("hard"), context.hardBlocks.map { it.id })
        assertEquals(listOf("google:opaque:event"), context.calendarCommitments.map { it.id })
        assertEquals(listOf("soft"), context.softBlocks.map { it.id })
        assertEquals(SchedulePriority.HIGH, context.softBlocks.single().priority)
        assertEquals(Instant.parse("2026-10-21T06:00:00Z"), context.softBlocks.single().deadlineAt)
        assertEquals(Instant.parse("2026-10-21T05:00:00Z"), context.softBlocks.single().earliestStart)
        assertEquals(Instant.parse("2026-10-21T06:00:00Z"), context.softBlocks.single().latestEnd)
        assertEquals(Instant.parse("2026-10-21T05:15:00Z"), context.softBlocks.single().preferredStart)
    }

    @Test
    fun deadlineDoesNotBecomeAScheduledStartAndUnboundedTasksCanBeExcluded() {
        val deadline = occurrence(
            id = "deadline-only",
            scheduledFor = null,
            scheduleType = "FLEXIBLE",
            durationMinutes = 30,
            deadlineAt = "2026-10-21T14:30:00Z",
        )
        val noConstraint = occurrence(
            id = "no-window",
            scheduledFor = null,
            scheduleType = "FLEXIBLE",
            durationMinutes = 30,
        )
        val schedule = buildDaySchedule(
            date = date,
            zoneId = zone,
            planningStartTime = LocalTime.of(7, 0),
            planningEndTime = LocalTime.of(23, 0),
            occurrences = listOf(deadline, noConstraint),
            now = Instant.parse("2026-10-21T00:00:00Z"),
            includeUnboundedFlexibleTasks = false,
        )

        assertNull(schedule.blocks.firstOrNull { it.id == "deadline-only" })
        assertEquals(listOf("deadline-only"), schedule.flexibleTasks.map { it.occurrenceId })
        assertEquals(Instant.parse("2026-10-21T14:30:00Z"), schedule.flexibleTasks.single().deadlineAt)
    }

    private fun occurrence(
        id: String,
        scheduledFor: String?,
        scheduleType: String,
        durationMinutes: Int,
        priority: String = "NORMAL",
        deadlineAt: String? = null,
        earliestStart: String? = null,
        latestEnd: String? = null,
        preferredStart: String? = null,
    ) = CachedOccurrence(
        id = id,
        taskId = "task-$id",
        title = id,
        notes = null,
        scheduledFor = scheduledFor,
        allDay = false,
        kind = "task",
        status = "pending",
        completedAt = null,
        userId = "owner",
        durationMinutes = durationMinutes,
        scheduleType = scheduleType,
        priority = priority,
        deadlineAt = deadlineAt,
        earliestStart = earliestStart,
        latestEnd = latestEnd,
        preferredStart = preferredStart,
    )
}
