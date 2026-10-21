package dev.system.app.planner.core

import dev.system.app.data.CachedOccurrence
import dev.system.app.planner.DaySchedule
import dev.system.app.planner.FlexibleTask
import dev.system.app.planner.PlanningContext
import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.SchedulePriority
import dev.system.app.planner.ScheduleType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Builds one local-day schedule from the bounded Vanta cache and separately cached calendar blocks. */
fun buildDaySchedule(
    date: LocalDate,
    zoneId: ZoneId,
    planningStartTime: LocalTime,
    planningEndTime: LocalTime,
    occurrences: List<CachedOccurrence>,
    calendarBlocks: List<ScheduleBlock> = emptyList(),
    now: Instant = Instant.now(),
    includeUnboundedFlexibleTasks: Boolean = true,
): DaySchedule {
    val planningStart = date.atTime(planningStartTime).atZone(zoneId).toInstant()
    val planningEnd = date.atTime(planningEndTime).atZone(zoneId).toInstant()
    require(planningEnd > planningStart) { "Planning hours must have a positive length." }

    val dayStart = date.atStartOfDay(zoneId).toInstant()
    val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant()
    val vantaBlocks = occurrences.mapNotNull { occurrence ->
        val type = enumValueOr(ScheduleType.values(), occurrence.scheduleType, ScheduleType.SOFT)
        occurrence.toScheduleBlock(
            zoneId = zoneId,
            scheduleType = type,
            now = now,
            priority = enumValueOr(SchedulePriority.values(), occurrence.priority, SchedulePriority.NORMAL),
            deadlineAt = occurrence.deadlineAt.toInstantOrNull(),
            earliestStart = occurrence.earliestStart.toInstantOrNull(),
            latestEnd = occurrence.latestEnd.toInstantOrNull(),
            preferredStart = occurrence.preferredStart.toInstantOrNull(),
        )?.takeIf { it.start < dayEnd && it.end > dayStart }
    }
    val externalBlocks = calendarBlocks.filter { it.start < dayEnd && it.end > dayStart }
    val flexible = occurrences.mapNotNull(CachedOccurrence::toPlannerFlexibleTask)
        .filter { task ->
            val hasConstraint = task.deadlineAt != null || task.earliestStart != null ||
                task.latestEnd != null || task.preferredStart != null
            val windowRelevant = (task.earliestStart == null || task.earliestStart < dayEnd) &&
                (task.latestEnd == null || task.latestEnd >= dayStart)
            val deadlineRelevant = task.deadlineAt?.let { it >= dayStart && it < dayEnd } == true
            val preferredRelevant = task.preferredStart?.let { it >= dayStart && it < dayEnd } == true
            if (!hasConstraint) includeUnboundedFlexibleTasks
            else windowRelevant || deadlineRelevant || preferredRelevant
        }
    return DaySchedule(
        date = date,
        zoneId = zoneId,
        planningStart = planningStart,
        planningEnd = planningEnd,
        blocks = (vantaBlocks + externalBlocks).sortedWith(compareBy<ScheduleBlock> { it.start }.thenBy { it.id }),
        flexibleTasks = flexible,
        completedOccurrenceIds = occurrences.asSequence()
            .filter { it.status == "completed" && it.scheduledFor.toInstantOrNull()?.atZone(zoneId)?.toLocalDate() == date }
            .map(CachedOccurrence::id)
            .toSet(),
    )
}

fun CachedOccurrence.toPlannerFlexibleTask(): FlexibleTask? = toFlexibleTask(
    scheduleType = enumValueOr(ScheduleType.values(), scheduleType, ScheduleType.SOFT),
    priority = enumValueOr(SchedulePriority.values(), priority, SchedulePriority.NORMAL),
    deadlineAt = deadlineAt.toInstantOrNull(),
    earliestStart = earliestStart.toInstantOrNull(),
    latestEnd = latestEnd.toInstantOrNull(),
    preferredStart = preferredStart.toInstantOrNull(),
)

fun DaySchedule.toPlanningContext(): PlanningContext = PlanningContext(
    date = date,
    zoneId = zoneId,
    planningStart = planningStart,
    planningEnd = planningEnd,
    hardBlocks = blocks.filter { it.scheduleType == ScheduleType.HARD && it.source != dev.system.app.planner.ScheduleSource.GOOGLE_CALENDAR },
    calendarCommitments = blocks.filter { it.source == dev.system.app.planner.ScheduleSource.GOOGLE_CALENDAR },
    softBlocks = blocks.filter { it.scheduleType == ScheduleType.SOFT && it.source != dev.system.app.planner.ScheduleSource.GOOGLE_CALENDAR },
    flexibleTasks = flexibleTasks,
    completedOccurrenceIds = completedOccurrenceIds,
)

private fun String?.toInstantOrNull(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }

private inline fun <reified T : Enum<T>> enumValueOr(values: Array<T>, value: String, fallback: T): T =
    values.firstOrNull { it.name == value } ?: fallback
