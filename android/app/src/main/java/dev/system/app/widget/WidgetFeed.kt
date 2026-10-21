package dev.system.app.widget

import dev.system.app.data.CachedOccurrence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Kept for the original feed calculations and regression coverage. */
internal data class WidgetFeedItem(
    val occurrence: CachedOccurrence,
    val localDate: LocalDate,
)

internal data class WidgetFeed(
    val overdue: List<WidgetFeedItem>,
    val today: List<WidgetFeedItem>,
    val upcoming: List<WidgetFeedItem>,
    val unscheduled: List<CachedOccurrence>,
) {
    val isEmpty: Boolean get() = overdue.isEmpty() && today.isEmpty() && upcoming.isEmpty() && unscheduled.isEmpty()
}

internal fun buildWidgetFeed(
    rows: List<CachedOccurrence>,
    today: LocalDate,
    zone: ZoneId = ZoneId.of("Asia/Kolkata"),
): WidgetFeed {
    val dated = mutableListOf<WidgetFeedItem>()
    val unscheduled = mutableListOf<CachedOccurrence>()
    rows.filter { it.status == "pending" }.forEach { row ->
        val instant = row.scheduledFor?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
        if (instant == null) {
            unscheduled += row
        } else {
            dated += WidgetFeedItem(row, instant.atZone(zone).toLocalDate())
        }
    }
    val ordered = dated.sortedWith(compareBy<WidgetFeedItem> { it.localDate }.thenBy { it.occurrence.scheduledFor })
    return WidgetFeed(
        overdue = ordered.filter { it.localDate < today },
        today = ordered.filter { it.localDate == today },
        upcoming = ordered.filter { it.localDate > today },
        unscheduled = unscheduled.sortedBy { it.title.lowercase() },
    )
}
