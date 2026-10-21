package dev.system.app.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.system.app.data.CachedOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class WidgetDayStateTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.parse("2026-10-21")

    @Test
    fun calculatesCurrentAndNextWithPlannedDuration() {
        val now = local("10:28")
        val state = buildWidgetDayState(
            rows = listOf(
                occurrence("current", "Research", "10:00", duration = 60),
                occurrence("next", "Gym", "11:30", duration = 45),
            ),
            today = day,
            now = now,
            zone = zone,
        )

        assertEquals("current", state.currentOccurrence?.id)
        assertEquals("next", state.nextOccurrence?.id)
        assertEquals(105, state.plannedMinutes)
        assertEquals(2, state.totalCount)
        assertEquals(local("11:00").toEpochMilli(), state.nextBoundaryEpochMillis)
    }

    @Test
    fun selectsNextAndShowsFreeDayGapWhenNoTaskIsActive() {
        val state = buildWidgetDayState(
            rows = listOf(occurrence("next", "Gym", "16:30", duration = 60)),
            today = day,
            now = local("15:20"),
            zone = zone,
        )

        assertNull(state.currentOccurrence)
        assertEquals("next", state.nextOccurrence?.id)
        assertEquals(local("16:30").toEpochMilli(), state.nextBoundaryEpochMillis)
    }

    @Test
    fun overlappingTasksUseStableStartOrderAndKeepUpcomingOverlapVisible() {
        val state = buildWidgetDayState(
            rows = listOf(
                occurrence("later-start", "Gym", "10:30", duration = 60),
                occurrence("earlier-start", "Research", "10:00", duration = 90),
            ),
            today = day,
            now = local("10:45"),
            zone = zone,
        )

        assertEquals("earlier-start", state.currentOccurrence?.id)
        assertEquals("later-start", state.nextOccurrence?.id)
        assertEquals(local("11:30").toEpochMilli(), state.nextBoundaryEpochMillis)
    }

    @Test
    fun activeTaskCanCrossMidnightAndDateBoundaryIsStillScheduled() {
        val start = local("23:30").toString()
        val state = buildWidgetDayState(
            rows = listOf(occurrence("overnight", "Overnight", start, duration = 90)),
            today = day,
            now = local("23:50"),
            zone = zone,
        )

        assertEquals("overnight", state.currentOccurrence?.id)
        assertEquals(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), state.nextBoundaryEpochMillis)
    }

    @Test
    fun completedTasksAppearCheckedAndDoNotBecomeCurrent() {
        val state = buildWidgetDayState(
            rows = listOf(
                occurrence("done", "Read", "10:00", duration = 30, status = "completed"),
                occurrence("pending", "Write", "11:00", duration = 60),
            ),
            today = day,
            now = local("10:15"),
            zone = zone,
        )

        assertNull(state.currentOccurrence)
        assertEquals("pending", state.nextOccurrence?.id)
        assertEquals(1, state.completedCount)
        assertEquals(2, state.totalCount)
        assertTrue(state.todayItems.first { it.id == "done" }.completed)
    }

    @Test
    fun boundedRowsCanUseExactRoomDayTotals() {
        val state = buildWidgetDayState(
            rows = listOf(occurrence("visible", "Visible", "10:00", duration = 30)),
            today = day,
            now = local("09:00"),
            zone = zone,
            todayPendingCountOverride = 140,
            todayCompletedCountOverride = 1,
            plannedMinutesOverride = 4_215L,
        )

        assertEquals(141, state.totalCount)
        assertEquals(1, state.completedCount)
        assertEquals(4_215, state.plannedMinutes)
    }

    @Test
    fun rescheduledOccurrenceUsesItsNewLocalTime() {
        val moved = occurrence("task", "Moved task", "14:00", duration = 30)
        val state = buildWidgetDayState(listOf(moved), day, local("13:00"), zone)

        assertEquals("task", state.nextOccurrence?.id)
        assertEquals(local("14:00").toEpochMilli(), state.nextBoundaryEpochMillis)
    }

    @Test
    fun emptyDayHasNoOccurrencesAndSchedulesOnlyTheNextDateBoundary() {
        val state = buildWidgetDayState(emptyList(), day, local("15:00"), zone)

        assertTrue(state.isEmpty)
        assertTrue(state.todayItems.isEmpty())
        assertNull(state.currentOccurrence)
        assertNull(state.nextOccurrence)
        assertEquals(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), state.nextBoundaryEpochMillis)
    }

    @Test
    fun persistedProjectionRoundTripsWithoutRoomEntities() {
        val state = buildWidgetDayState(
            rows = listOf(occurrence("task", "Research", "10:00", duration = 45)),
            today = day,
            now = local("09:00"),
            zone = zone,
        )

        assertEquals(state, WidgetDayStateStore.decode(WidgetDayStateStore.encode(state)))
        assertNull(WidgetDayStateStore.decode("not json"))
    }

    private fun local(time: String): Instant = LocalDateTime.parse("${day}T$time").atZone(zone).toInstant()

    private fun occurrence(
        id: String,
        title: String,
        time: String,
        duration: Int,
        status: String = "pending",
    ): CachedOccurrence {
        val scheduledFor = if ('T' in time) time else local(time).toString()
        return CachedOccurrence(
            id = id,
            taskId = "task-$id",
            title = title,
            notes = null,
            scheduledFor = scheduledFor,
            allDay = false,
            kind = "task",
            status = status,
            completedAt = null,
            durationMinutes = duration,
        )
    }
}
