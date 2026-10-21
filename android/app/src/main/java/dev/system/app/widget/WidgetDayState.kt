package dev.system.app.widget

import dev.system.app.data.CachedOccurrence
import dev.system.app.data.MAX_TASK_DURATION_MINUTES
import dev.system.app.data.MIN_TASK_DURATION_MINUTES
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A small, serializable projection used by Glance; it never contains Room entities. */
internal data class WidgetDayItem(
    val id: String,
    val title: String,
    val scheduledFor: String?,
    val durationMinutes: Int?,
    val allDay: Boolean,
    val completed: Boolean,
) {
    val start: Instant? get() = scheduledFor?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val end: Instant? get() = start?.plusMinutesOrNull(durationMinutes, allDay)
}

internal data class WidgetDayState(
    val generatedAtEpochMillis: Long,
    val localDate: String,
    val currentOccurrence: WidgetDayItem?,
    val nextOccurrence: WidgetDayItem?,
    val todayItems: List<WidgetDayItem>,
    val plannedMinutes: Int,
    val completedCount: Int,
    val totalCount: Int,
    val nextBoundaryEpochMillis: Long,
    val syncWarning: Boolean,
) {
    val isEmpty: Boolean get() = totalCount == 0 && nextOccurrence == null && currentOccurrence == null
}

/**
 * Builds the compact day projection during a refresh signal. The returned state is
 * deliberately bounded so it stays small in preferences and fast to render.
 */
internal fun buildWidgetDayState(
    rows: List<CachedOccurrence>,
    today: LocalDate,
    now: Instant,
    zone: ZoneId = ZoneId.of("Asia/Kolkata"),
    todayPendingCountOverride: Int? = null,
    todayCompletedCountOverride: Int? = null,
    plannedMinutesOverride: Long? = null,
    syncWarning: Boolean = false,
): WidgetDayState {
    val distinctRows = rows.distinctBy(CachedOccurrence::id)
    val pending = distinctRows.filter { it.status == "pending" }
    val completed = distinctRows.filter { it.status == "completed" }.map(CachedOccurrence::toWidgetItem)
    val completedById = completed.associateBy(WidgetDayItem::id)

    val timedPending = pending.mapNotNull { row ->
        val item = row.toWidgetItem()
        val start = item.start ?: return@mapNotNull null
        if (item.allDay) return@mapNotNull null
        ScheduledWidgetItem(item, start)
    }.sortedWith(compareBy<ScheduledWidgetItem> { it.start }.thenBy { it.item.id })

    val current = timedPending.firstOrNull { scheduled ->
        val end = scheduled.item.end
        end != null && scheduled.start <= now && now < end
    }?.item

    val next = timedPending.firstOrNull { it.start > now }?.item
    val tomorrowStart = today.plusDays(1).atStartOfDay(zone).toInstant()

    val todayPending = pending.asSequence()
        .map(CachedOccurrence::toWidgetItem)
        .filter { item -> item.start?.atZone(zone)?.toLocalDate() == today }
        .toList()
    val completedToday = completedById.values.filter { item ->
        item.start?.atZone(zone)?.toLocalDate() == today
    }
    val allToday = (todayPending + completedToday)
        .distinctBy(WidgetDayItem::id)
        .sortedWith(compareBy<WidgetDayItem> { it.start ?: Instant.MAX }.thenBy { it.id })
    val displayedToday = chooseTodayItems(allToday, current?.id, next?.id)

    val calculatedPlannedMinutes = (todayPending + completedToday)
        .distinctBy(WidgetDayItem::id)
        .filterNot(WidgetDayItem::allDay)
        .sumOf { it.durationMinutes.validWidgetDuration() ?: 0 }
    val plannedMinutes = (plannedMinutesOverride ?: calculatedPlannedMinutes.toLong())
        .coerceIn(0L, Int.MAX_VALUE.toLong())
        .toInt()
    val completedCount = (todayCompletedCountOverride ?: completedToday.size).coerceAtLeast(0)
    val totalCount = (todayPendingCountOverride ?: todayPending.size).coerceAtLeast(0) + completedCount

    val boundaries = buildList {
        add(tomorrowStart)
        timedPending.forEach { scheduled ->
            if (scheduled.start > now) add(scheduled.start)
            scheduled.item.end?.takeIf { it > now && scheduled.start <= now }?.let(::add)
        }
    }
    val nextBoundary = boundaries.filter { it > now }.minOrNull() ?: tomorrowStart

    return WidgetDayState(
        generatedAtEpochMillis = now.toEpochMilli(),
        localDate = today.toString(),
        currentOccurrence = current,
        nextOccurrence = next,
        todayItems = displayedToday,
        plannedMinutes = plannedMinutes,
        completedCount = completedCount,
        totalCount = totalCount,
        nextBoundaryEpochMillis = nextBoundary.toEpochMilli(),
        syncWarning = syncWarning,
    )
}

private data class ScheduledWidgetItem(val item: WidgetDayItem, val start: Instant)

private fun chooseTodayItems(
    ordered: List<WidgetDayItem>,
    currentId: String?,
    nextId: String?,
): List<WidgetDayItem> {
    if (ordered.size <= MAX_TODAY_WIDGET_ITEMS) return ordered

    val selectedIds = linkedSetOf<String>()
    currentId?.let(selectedIds::add)
    nextId?.let(selectedIds::add)
    ordered.filterNot(WidgetDayItem::completed).forEach { item ->
        if (selectedIds.size < MAX_TODAY_WIDGET_ITEMS) selectedIds += item.id
    }
    ordered.filter(WidgetDayItem::completed).asReversed().forEach { item ->
        if (selectedIds.size < MAX_TODAY_WIDGET_ITEMS) selectedIds += item.id
    }
    return ordered.filter { it.id in selectedIds }.take(MAX_TODAY_WIDGET_ITEMS)
}

private fun CachedOccurrence.toWidgetItem() = WidgetDayItem(
    id = id,
    title = title,
    scheduledFor = scheduledFor,
    durationMinutes = durationMinutes,
    allDay = allDay,
    completed = status == "completed",
)

private fun Int?.validWidgetDuration(): Int? = this?.takeIf {
    it in MIN_TASK_DURATION_MINUTES..MAX_TASK_DURATION_MINUTES
}

private fun Instant.plusMinutesOrNull(minutes: Int?, allDay: Boolean): Instant? {
    if (allDay) return null
    val validMinutes = minutes.validWidgetDuration() ?: return null
    return runCatching { plus(Duration.ofMinutes(validMinutes.toLong())) }.getOrNull()
}

private const val MAX_TODAY_WIDGET_ITEMS = 12
