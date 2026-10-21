package dev.system.app.planner.core

import dev.system.app.data.CachedOccurrence
import dev.system.app.planner.SchedulePriority
import dev.system.app.planner.ScheduleType
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedOccurrenceMapperTest {
    @Test
    fun scheduledOccurrenceMapsToDurationBlockAndCompletedOrPastWorkIsReadOnly() {
        val now = Instant.parse("2026-10-21T08:00:00Z")
        val pending = occurrence(scheduledFor = "2026-10-21T09:00:00Z", durationMinutes = 30)
        val block = pending.toScheduleBlock(ZoneId.of("UTC"), ScheduleType.SOFT, now)!!
        assertEquals(Instant.parse("2026-10-21T09:00:00Z"), block.start)
        assertEquals(Instant.parse("2026-10-21T09:30:00Z"), block.end)
        assertTrue(block.editable)

        val completed = pending.copy(status = "completed").toScheduleBlock(ZoneId.of("UTC"), now = now)!!
        assertFalse(completed.editable)
        val past = pending.toScheduleBlock(ZoneId.of("UTC"), now = Instant.parse("2026-10-21T10:00:00Z"))!!
        assertFalse(past.editable)
    }

    @Test
    fun allDayBlockUsesActualZonedMidnightBoundariesAcrossDst() {
        val occurrence = occurrence(scheduledFor = "2026-03-29T00:00:00Z", allDay = true, durationMinutes = null)
        val block = occurrence.toScheduleBlock(ZoneId.of("Europe/Berlin"), now = Instant.EPOCH)!!
        assertEquals(Instant.parse("2026-03-28T23:00:00Z"), block.start)
        assertEquals(Instant.parse("2026-03-29T22:00:00Z"), block.end)
        assertEquals(23 * 60L, java.time.Duration.between(block.start, block.end).toMinutes())
    }

    @Test
    fun flexibleMapperKeepsDeadlineSeparateAndRejectsIneligibleRows() {
        val flexible = occurrence(scheduledFor = null, durationMinutes = 90)
        val deadline = Instant.parse("2026-10-23T18:00:00Z")
        val mapped = flexible.toFlexibleTask(
            scheduleType = ScheduleType.FLEXIBLE,
            priority = SchedulePriority.HIGH,
            deadlineAt = deadline,
        )!!
        assertEquals(deadline, mapped.deadlineAt)
        assertEquals(null, mapped.preferredStart)
        assertEquals(SchedulePriority.HIGH, mapped.priority)
        assertNull(flexible.toFlexibleTask(scheduleType = ScheduleType.SOFT))
        assertNull(flexible.copy(status = "completed").toFlexibleTask())
        assertNull(flexible.copy(durationMinutes = 4).toFlexibleTask())
        assertNull(occurrence(scheduledFor = "2026-10-21T09:00:00Z", durationMinutes = 30).toFlexibleTask())
    }

    private fun occurrence(
        scheduledFor: String?,
        allDay: Boolean = false,
        durationMinutes: Int?,
        status: String = "pending",
    ) = CachedOccurrence(
        id = "occurrence-1",
        taskId = "task-1",
        title = "Study",
        notes = null,
        scheduledFor = scheduledFor,
        allDay = allDay,
        kind = "task",
        status = status,
        completedAt = null,
        userId = "owner-1",
        durationMinutes = durationMinutes,
    )
}
