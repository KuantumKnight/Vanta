package dev.system.app.ui.today.timeline

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineCalculationsTest {
    private val day = LocalDate.of(2026, 10, 21)
    private val zone = VANTA_TIME_ZONE

    @Test
    fun calculatesEndAndLeavesUnknownDurationOpenEnded() {
        val plan = plan(
            task("long", "09:00", durationMinutes = 90),
            task("unknown", "10:30", durationMinutes = null),
        )

        assertEquals("10:30", plan.scheduled[0].end?.toLocalTime()?.toString()?.take(5))
        assertNull(plan.scheduled[1].end)
        assertEquals(90, plan.plannedMinutes)
    }

    @Test
    fun sortsByStartThenTitleThenId() {
        val plan = plan(
            task("z", "10:00", title = "Bravo", durationMinutes = 30),
            task("late", "11:00", title = "First chronologically", durationMinutes = 30),
            task("b", "10:00", title = "Alpha", durationMinutes = 30),
            task("a", "10:00", title = "Alpha", durationMinutes = 30),
        )

        assertEquals(listOf("a", "b", "z", "late"), plan.scheduled.map { it.task.id })
    }

    @Test
    fun unknownDurationDoesNotConsumePlannedOrFreeMinutes() {
        val plan = plan(task("point", "09:00", durationMinutes = null))

        assertEquals(1, plan.scheduledCount)
        assertEquals(0, plan.plannedMinutes)
        assertEquals(960L, plan.freeMinutes)
    }

    @Test
    fun marksOnlyActuallyOverlappingDurationBlocks() {
        val plan = plan(
            task("first", "09:00", durationMinutes = 60),
            task("overlap", "09:30", durationMinutes = 60),
            task("touching", "10:30", durationMinutes = 30),
            task("no-duration", "09:15", durationMinutes = null),
        )

        assertTrue(plan.scheduled.single { it.task.id == "first" }.overlapsAnotherTask)
        assertTrue(plan.scheduled.single { it.task.id == "overlap" }.overlapsAnotherTask)
        assertFalse(plan.scheduled.single { it.task.id == "touching" }.overlapsAnotherTask)
        assertFalse(plan.scheduled.single { it.task.id == "no-duration" }.overlapsAnotherTask)
    }

    @Test
    fun selectsCurrentAndNextTaskAtTheCurrentInstant() {
        val plan = plan(
            task("finished", "08:00", durationMinutes = 30),
            task("current", "09:00", durationMinutes = 60),
            task("next", "10:30", durationMinutes = 30),
            task("later", "12:00", durationMinutes = 30),
            now = instantAt("09:30"),
        )

        assertEquals("current", plan.currentTaskId)
        assertEquals("next", plan.nextTaskId)
        assertTrue(plan.scheduled.single { it.task.id == "current" }.isCurrent)
    }

    @Test
    fun calculatesPlannedMinutesAndFreeGapsWithinTheWakingWindow() {
        val plan = plan(
            task("one", "09:00", durationMinutes = 60),
            task("two", "11:00", durationMinutes = 45),
        )

        assertEquals(105, plan.plannedMinutes)
        assertEquals(855L, plan.freeMinutes)
        assertEquals(
            listOf("07:00–09:00", "10:00–11:00", "11:45–23:00"),
            plan.freeGaps.map { "${it.start.toLocalTime()}–${it.end.toLocalTime()}" },
        )
    }

    @Test
    fun clipsIntervalsThatCrossPlanningWindowBoundaries() {
        val plan = plan(
            task("before-window", "06:30", durationMinutes = 120),
            task("after-window", "22:30", durationMinutes = 120),
        )

        assertEquals("08:30", plan.scheduled.first().end?.toLocalTime()?.toString()?.take(5))
        assertEquals(day.plusDays(1), plan.scheduled.last().end?.toLocalDate())
        assertEquals(240, plan.plannedMinutes)
        assertEquals(840L, plan.freeMinutes)
        assertEquals(1, plan.freeGaps.size)
        assertEquals("08:30", plan.freeGaps.single().start.toLocalTime().toString().take(5))
        assertEquals("22:30", plan.freeGaps.single().end.toLocalTime().toString().take(5))
    }

    @Test
    fun allDayAndUndatedTasksStayInTheUnscheduledBucket() {
        val plan = plan(
            task("all-day", "00:00", durationMinutes = 90, allDay = true),
            TimelineTask("no-time", "Fit me in", null, null, allDay = false, completed = false),
        )

        assertTrue(plan.scheduled.isEmpty())
        assertEquals(listOf("all-day", "no-time"), plan.unscheduled.map { it.id })
        assertEquals(0, plan.plannedMinutes)
        assertEquals(960L, plan.freeMinutes)
    }

    @Test
    fun invalidAndOutOfRangeDurationsDoNotCreateIntervals() {
        val plan = plan(
            task("too-short", "09:00", durationMinutes = 4),
            task("minimum", "10:00", durationMinutes = 5),
            task("maximum", "11:00", durationMinutes = 1440),
            task("too-long", "12:00", durationMinutes = 1441),
        )

        assertEquals(listOf(null, 5, 1440, null), plan.scheduled.map { it.end?.let { end ->
            java.time.Duration.between(it.start, end).toMinutes().toInt()
        } })
        assertEquals(1445, plan.plannedMinutes)
    }

    @Test
    fun overlappingTasksAreMergedForFreeTime() {
        val plan = plan(
            task("one", "09:00", durationMinutes = 90),
            task("two", "09:30", durationMinutes = 90),
        )

        assertEquals(180, plan.plannedMinutes)
        assertEquals(840L, plan.freeMinutes)
        assertTrue(plan.scheduled.all { it.overlapsAnotherTask })
    }

    @Test
    fun proposedRescheduleNamesOnlyActuallyOverlappingTasks() {
        val blocks = plan(
            task("moving", "09:00", durationMinutes = 60),
            task("gym", "10:00", title = "Gym", durationMinutes = 45),
            task("class", "11:00", title = "Class", durationMinutes = 45),
        ).scheduled

        assertEquals(
            listOf("Gym"),
            overlappingTaskTitles("moving", instantAt("09:30"), 60, blocks),
        )
        assertTrue(overlappingTaskTitles("moving", instantAt("09:30"), null, blocks).isEmpty())
    }

    private fun plan(vararg tasks: TimelineTask, now: Instant = instantAt("08:00")) =
        calculateDayTimeline(tasks.toList(), day, now, zone)

    private fun task(
        id: String,
        start: String,
        title: String = id,
        durationMinutes: Int?,
        allDay: Boolean = false,
    ) = TimelineTask(
        id = id,
        title = title,
        scheduledFor = instantAt(start).toString(),
        durationMinutes = durationMinutes,
        allDay = allDay,
        completed = false,
    )

    private fun instantAt(time: String) = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
}
