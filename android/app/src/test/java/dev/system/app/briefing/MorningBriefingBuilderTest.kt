package dev.system.app.briefing

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MorningBriefingBuilderTest {
    private val today = LocalDate.parse("2026-10-21")

    @Test
    fun emptyDayHasNoFirstItemOrPlannedDuration() {
        val briefing = MorningBriefingBuilder.build(today, emptyList())

        assertEquals(0, briefing.scheduledItemCount)
        assertEquals(0, briefing.habitCount)
        assertNull(briefing.plannedMinutes)
        assertNull(briefing.firstItem)
        assertNull(briefing.importantTaskCount)
    }

    @Test
    fun busyDayCountsItemsAndSelectsTheEarliestTime() {
        val briefing = MorningBriefingBuilder.build(
            today,
            listOf(
                occurrence("later", "Later", "2026-10-21T09:30:00Z", duration = 60),
                occurrence("first", "First", "2026-10-21T03:30:00Z", duration = 45),
                occurrence("tomorrow", "Tomorrow", "2026-10-21T20:00:00Z", duration = 25),
            ),
        )

        assertEquals(2, briefing.scheduledItemCount)
        assertEquals("First", briefing.firstItem?.title)
        assertEquals("09:00", briefing.firstItem?.time?.toString())
        assertEquals(105L, briefing.plannedMinutes)
    }

    @Test
    fun habitCountCountsDistinctHabitTasksScheduledToday() {
        val briefing = MorningBriefingBuilder.build(
            today,
            listOf(
                occurrence("water-1", "Drink water", "2026-10-21T02:00:00Z", kind = "habit", taskId = "water"),
                occurrence("water-2", "Drink water", "2026-10-21T05:00:00Z", kind = "habit", taskId = "water"),
                occurrence("read", "Read", "2026-10-21T06:00:00Z", kind = "habit", taskId = "read"),
                occurrence("task", "Write", "2026-10-21T07:00:00Z"),
            ),
        )

        assertEquals(4, briefing.scheduledItemCount)
        assertEquals(2, briefing.habitCount)
    }

    @Test
    fun durationIsOmittedWhenAnyTimedItemHasNoDuration() {
        val briefing = MorningBriefingBuilder.build(
            today,
            listOf(
                occurrence("timed", "Timed", "2026-10-21T02:00:00Z", duration = 30),
                occurrence("unknown", "Unknown", "2026-10-21T03:00:00Z"),
                occurrence("all-day", "All day", "2026-10-21T00:00:00Z", allDay = true),
            ),
        )

        assertEquals(3, briefing.scheduledItemCount)
        assertNull(briefing.plannedMinutes)
    }

    @Test
    fun timezoneDeterminesWhichLocalDayContainsAnOccurrence() {
        val india = MorningBriefingBuilder.build(
            LocalDate.parse("2026-10-22"),
            listOf(occurrence("india", "After midnight in India", "2026-10-21T20:00:00Z", duration = 20)),
        )

        assertEquals(1, india.scheduledItemCount)
        assertEquals("01:30", india.firstItem?.time?.toString())

        val newYorkZone = ZoneId.of("America/New_York")
        val newYork = MorningBriefingBuilder.build(
            LocalDate.parse("2026-03-08"),
            listOf(occurrence("dst", "DST morning", "2026-03-08T07:30:00Z", duration = 20)),
            zone = newYorkZone,
        )
        assertEquals("03:30", newYork.firstItem?.time?.toString())
    }

    @Test
    fun exposesPriorityCountOnlyWhenProvidedAndClampsInvalidNegativeValue() {
        val noPriorityData = MorningBriefingBuilder.build(today, emptyList())
        val withPriorityData = MorningBriefingBuilder.build(today, emptyList(), importantTaskCount = 2)
        val invalidPriorityData = MorningBriefingBuilder.build(today, emptyList(), importantTaskCount = -1)

        assertNull(noPriorityData.importantTaskCount)
        assertEquals(2, withPriorityData.importantTaskCount)
        assertEquals(0, invalidPriorityData.importantTaskCount)
    }

    @Test
    fun highPriorityTaskCountUsesAvailableOccurrenceData() {
        val briefing = MorningBriefingBuilder.build(
            today,
            listOf(
                occurrence("important", "Important", "2026-10-21T09:00:00Z", priority = "HIGH"),
                occurrence("ordinary", "Ordinary", "2026-10-21T10:00:00Z", priority = "NORMAL"),
                occurrence("important-habit", "Habit", "2026-10-21T11:00:00Z", kind = "habit", priority = "HIGH"),
            ),
        )

        assertEquals(1, briefing.importantTaskCount)
    }

    private fun occurrence(
        id: String,
        title: String,
        scheduledFor: String,
        duration: Int? = null,
        kind: String = "task",
        taskId: String = id,
        allDay: Boolean = false,
        priority: String? = null,
    ) = BriefingOccurrence(
        taskId = taskId,
        title = title,
        scheduledFor = scheduledFor,
        allDay = allDay,
        kind = kind,
        durationMinutes = duration,
        priority = priority,
    )
}
