package dev.system.app.ui.today.timeline

import dev.system.app.data.CachedOccurrence
import dev.system.app.planner.ScheduleBlock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal const val MIN_TASK_DURATION_MINUTES = 5
internal const val MAX_TASK_DURATION_MINUTES = 24 * 60

/**
 * Local planning window used only for the first timeline summary. It is an explicit
 * product assumption, not a claim that all users keep these hours.
 */
internal val DEFAULT_PLANNING_START: LocalTime = LocalTime.of(7, 0)
internal val DEFAULT_PLANNING_END: LocalTime = LocalTime.of(23, 0)
internal val VANTA_TIME_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

/** UI-independent input to the day timeline. */
internal data class TimelineTask(
    val id: String,
    val title: String,
    val scheduledFor: String?,
    val durationMinutes: Int?,
    val allDay: Boolean,
    val completed: Boolean,
)

internal fun CachedOccurrence.toTimelineTask(): TimelineTask = TimelineTask(
    id = id,
    title = title,
    scheduledFor = scheduledFor,
    durationMinutes = durationMinutes,
    allDay = allDay,
    completed = status == "completed",
)

internal data class TimelineBlock(
    val task: TimelineTask,
    val start: ZonedDateTime,
    val end: ZonedDateTime?,
    val overlapsAnotherTask: Boolean,
    val isCurrent: Boolean,
)

internal data class TimelineFreeGap(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
) {
    val durationMinutes: Long
        get() = Duration.between(start, end).toMinutes()
}

internal data class DayTimeline(
    val scheduled: List<TimelineBlock>,
    val unscheduled: List<TimelineTask>,
    val scheduledCount: Int,
    val plannedMinutes: Int,
    val freeGaps: List<TimelineFreeGap>,
    val currentTaskId: String?,
    val nextTaskId: String?,
) {
    val freeMinutes: Long get() = freeGaps.sumOf(TimelineFreeGap::durationMinutes)
}

/** Returns the titles of timed blocks that would overlap this proposed interval. */
internal fun overlappingTaskTitles(
    occurrenceId: String,
    start: Instant,
    durationMinutes: Int?,
    scheduled: List<TimelineBlock>,
    calendarBlocks: List<ScheduleBlock> = emptyList(),
): List<String> {
    val duration = durationMinutes?.takeIf { it in MIN_TASK_DURATION_MINUTES..MAX_TASK_DURATION_MINUTES }
        ?: return emptyList()
    val end = start.plusSeconds(duration * 60L)
    val taskTitles = scheduled.asSequence()
        .filter { it.task.id != occurrenceId }
        .filter { block ->
            block.end?.toInstant()?.let { otherEnd -> start < otherEnd && block.start.toInstant() < end } == true
        }
        .map { it.task.title }
        .distinct()
        .toList()
    val calendarTitles = calendarBlocks.asSequence()
        .filter { it.id != occurrenceId }
        .filter { start < it.end && it.start < end }
        .map(ScheduleBlock::title)
        .toList()
    return (taskTitles + calendarTitles).distinct()
}

/**
 * Builds a stable, local-time plan for one date. Tasks with no valid duration stay
 * as start-time points and do not consume calculated free time. All-day tasks are
 * shown in the unscheduled area instead of being assigned a synthetic start time.
 */
internal fun calculateDayTimeline(
    tasks: List<TimelineTask>,
    day: LocalDate,
    now: Instant,
    zone: ZoneId = VANTA_TIME_ZONE,
    planningStart: LocalTime = DEFAULT_PLANNING_START,
    planningEnd: LocalTime = DEFAULT_PLANNING_END,
    externalBlocks: List<ScheduleBlock> = emptyList(),
): DayTimeline {
    require(planningStart < planningEnd) { "Planning window must have a positive length." }

    val uniqueTasks = tasks.distinctBy(TimelineTask::id)
    val parsed = uniqueTasks.mapNotNull { task ->
        val start = task.scheduledFor?.let { value ->
            runCatching { Instant.parse(value).atZone(zone) }.getOrNull()
        }
        ParsedTask(task, start)
    }

    val scheduledTasks = parsed.asSequence()
        .filter { it.start != null && !it.task.allDay && it.start.toLocalDate() == day }
        .sortedWith(
            compareBy<ParsedTask> { it.start }
                .thenBy { it.task.title.lowercase() }
                .thenBy { it.task.id },
        )
        .toList()

    val unscheduled = parsed.asSequence()
        .filter { it.start == null || (it.task.allDay && it.start.toLocalDate() == day) }
        .map(ParsedTask::task)
        .sortedWith(compareBy<TimelineTask> { it.title.lowercase() }.thenBy { it.id })
        .toList()

    val rawIntervals = scheduledTasks.map { parsedTask ->
        val start = checkNotNull(parsedTask.start)
        val duration = parsedTask.task.durationMinutes?.takeIf { it in MIN_TASK_DURATION_MINUTES..MAX_TASK_DURATION_MINUTES }
        RawInterval(parsedTask.task, start, duration?.let { start.plusMinutes(it.toLong()) })
    }

    val overlapIds = buildSet {
        for (leftIndex in rawIntervals.indices) {
            val left = rawIntervals[leftIndex]
            val leftEnd = left.end ?: continue
            for (rightIndex in leftIndex + 1 until rawIntervals.size) {
                val right = rawIntervals[rightIndex]
                if (right.start >= leftEnd) break
                val rightEnd = right.end ?: continue
                if (left.start < rightEnd && right.start < leftEnd) {
                    add(left.task.id)
                    add(right.task.id)
                }
            }
        }
    }

    val nowZoned = now.atZone(zone)
    val currentTaskId = rawIntervals.firstOrNull { interval ->
        interval.end?.let { interval.start <= nowZoned && nowZoned < it } == true
    }?.task?.id
    val nextTaskId = rawIntervals.firstOrNull { interval -> interval.start.toInstant() > now }?.task?.id

    val scheduled = rawIntervals.map { interval ->
        TimelineBlock(
            task = interval.task,
            start = interval.start,
            end = interval.end,
            overlapsAnotherTask = interval.task.id in overlapIds,
            isCurrent = interval.task.id == currentTaskId,
        )
    }

    val planningStartInstant = day.atTime(planningStart).atZone(zone).toInstant()
    val planningEndInstant = day.atTime(planningEnd).atZone(zone).toInstant()
    val freeGaps = computeFreeGaps(rawIntervals, externalBlocks, planningStartInstant, planningEndInstant, zone)
    val plannedMinutes = rawIntervals.sumOf { interval ->
        interval.task.durationMinutes
            ?.takeIf { it in MIN_TASK_DURATION_MINUTES..MAX_TASK_DURATION_MINUTES }
            ?: 0
    }

    return DayTimeline(
        scheduled = scheduled,
        unscheduled = unscheduled,
        scheduledCount = scheduled.size,
        plannedMinutes = plannedMinutes,
        freeGaps = freeGaps,
        currentTaskId = currentTaskId,
        nextTaskId = nextTaskId,
    )
}

private data class ParsedTask(val task: TimelineTask, val start: ZonedDateTime?)

private data class RawInterval(
    val task: TimelineTask,
    val start: ZonedDateTime,
    val end: ZonedDateTime?,
)

private fun computeFreeGaps(
    intervals: List<RawInterval>,
    externalBlocks: List<ScheduleBlock>,
    windowStart: Instant,
    windowEnd: Instant,
    zone: ZoneId,
): List<TimelineFreeGap> {
    val taskBusy = intervals.mapNotNull { interval ->
        val end = interval.end?.toInstant() ?: return@mapNotNull null
        val start = maxOf(interval.start.toInstant(), windowStart)
        val clippedEnd = minOf(end, windowEnd)
        if (start < clippedEnd) start to clippedEnd else null
    }
    val calendarBusy = externalBlocks.mapNotNull { block ->
        val start = maxOf(block.start, windowStart)
        val end = minOf(block.end, windowEnd)
        if (start < end) start to end else null
    }
    val busy = (taskBusy + calendarBusy).sortedBy { it.first }

    val gaps = mutableListOf<TimelineFreeGap>()
    var cursor = windowStart
    for ((start, end) in busy) {
        if (start > cursor) gaps += TimelineFreeGap(cursor.atZone(zone), start.atZone(zone))
        if (end > cursor) cursor = end
    }
    if (cursor < windowEnd) gaps += TimelineFreeGap(cursor.atZone(zone), windowEnd.atZone(zone))
    return gaps
}
