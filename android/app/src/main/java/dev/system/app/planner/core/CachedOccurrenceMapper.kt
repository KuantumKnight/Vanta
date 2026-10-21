package dev.system.app.planner.core

import dev.system.app.data.CachedOccurrence
import dev.system.app.planner.FlexibleTask
import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.SchedulePriority
import dev.system.app.planner.ScheduleSource
import dev.system.app.planner.ScheduleType
import java.time.Instant
import java.time.ZoneId

/** Maps a cached occurrence with a chosen start to a display/planning block. */
fun CachedOccurrence.toScheduleBlock(
    zoneId: ZoneId,
    scheduleType: ScheduleType? = null,
    now: Instant = Instant.now(),
    priority: SchedulePriority = SchedulePriority.NORMAL,
    deadlineAt: Instant? = null,
    earliestStart: Instant? = null,
    latestEnd: Instant? = null,
    preferredStart: Instant? = null,
): ScheduleBlock? {
    val scheduledStart = scheduledFor?.let(::parseInstantOrNull) ?: return null
    val start: Instant
    val end: Instant
    if (allDay) {
        val localDate = scheduledStart.atZone(zoneId).toLocalDate()
        start = localDate.atStartOfDay(zoneId).toInstant()
        end = localDate.plusDays(1).atStartOfDay(zoneId).toInstant()
    } else {
        val duration = durationMinutes?.takeIf { it in MIN_TASK_DURATION..MAX_TASK_DURATION } ?: return null
        start = scheduledStart
        end = runCatching { start.plusSeconds(duration * 60L) }.getOrNull() ?: return null
    }
    val isPending = status == PENDING_STATUS
    val effectiveScheduleType = scheduleType ?: if (scheduledFor == null) ScheduleType.FLEXIBLE else ScheduleType.SOFT
    return ScheduleBlock(
        id = id,
        source = if (kind == HABIT_KIND) ScheduleSource.VANTA_HABIT else ScheduleSource.VANTA_TASK,
        title = title,
        start = start,
        end = end,
        scheduleType = effectiveScheduleType,
        editable = isPending && effectiveScheduleType == ScheduleType.SOFT && end > now,
        allDay = allDay,
        priority = priority,
        deadlineAt = deadlineAt,
        earliestStart = earliestStart,
        latestEnd = latestEnd,
        preferredStart = preferredStart,
    )
}

/** Maps only an unscheduled pending flexible occurrence into the scheduling tray. */
fun CachedOccurrence.toFlexibleTask(
    scheduleType: ScheduleType? = null,
    priority: SchedulePriority = SchedulePriority.NORMAL,
    deadlineAt: Instant? = null,
    earliestStart: Instant? = null,
    latestEnd: Instant? = null,
    preferredStart: Instant? = null,
): FlexibleTask? {
    val effectiveScheduleType = scheduleType ?: if (scheduledFor == null) ScheduleType.FLEXIBLE else ScheduleType.SOFT
    if (status != PENDING_STATUS || effectiveScheduleType != ScheduleType.FLEXIBLE || scheduledFor != null) return null
    val duration = durationMinutes?.takeIf { it in MIN_TASK_DURATION..MAX_TASK_DURATION } ?: return null
    return FlexibleTask(
        occurrenceId = id,
        title = title,
        durationMinutes = duration,
        priority = priority,
        deadlineAt = deadlineAt,
        earliestStart = earliestStart,
        latestEnd = latestEnd,
        preferredStart = preferredStart,
    )
}

private fun parseInstantOrNull(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

private const val PENDING_STATUS = "pending"
private const val HABIT_KIND = "habit"
private const val MIN_TASK_DURATION = 5
private const val MAX_TASK_DURATION = 24 * 60
