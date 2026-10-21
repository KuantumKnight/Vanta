package dev.system.app.data

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OccurrenceTimingTest {
    @Test
    fun missingDurationKeepsEndTimeUnknown() {
        val occurrence = occurrence(durationMinutes = null)

        assertEquals(Instant.parse("2026-10-22T12:30:00Z"), occurrence.startInstant())
        assertNull(occurrence.duration())
        assertNull(occurrence.endInstant())
    }

    @Test
    fun validDurationCalculatesEndInstant() {
        val occurrence = occurrence(durationMinutes = 90)

        assertEquals(Duration.ofMinutes(90), occurrence.duration())
        assertEquals(Instant.parse("2026-10-22T14:00:00Z"), occurrence.endInstant())
    }

    @Test
    fun minimumAndMaximumDurationsAreAccepted() {
        assertEquals(Duration.ofMinutes(5), occurrence(durationMinutes = 5).duration())
        assertEquals(Duration.ofHours(24), occurrence(durationMinutes = 1440).duration())
    }

    @Test
    fun outOfRangeDurationDoesNotCreateAnInterval() {
        assertNull(occurrence(durationMinutes = 4).duration())
        assertNull(occurrence(durationMinutes = 1441).duration())
    }

    @Test
    fun allDayTaskHasNoCalculatedTimedEnd() {
        assertNull(occurrence(durationMinutes = 60, allDay = true).endInstant())
    }

    private fun occurrence(durationMinutes: Int?, allDay: Boolean = false) = CachedOccurrence(
        id = "occ-1",
        taskId = "task-1",
        title = "Study",
        notes = null,
        scheduledFor = "2026-10-22T12:30:00Z",
        allDay = allDay,
        kind = "task",
        status = "pending",
        completedAt = null,
        durationMinutes = durationMinutes,
    )
}
