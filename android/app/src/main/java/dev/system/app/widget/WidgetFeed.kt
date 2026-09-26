package dev.system.app.widget

import dev.system.app.data.CachedOccurrence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val systemZone = ZoneId.of("Asia/Kolkata")

internal data class WidgetDatedOccurrence(
    val date: LocalDate,
    val occurrence: CachedOccurrence,
)

internal data class WidgetFeed(
    val todayAndOverdue: List<WidgetDatedOccurrence>,
    val anytime: List<CachedOccurrence>,
    val upcoming: List<WidgetDatedOccurrence>,
)

internal fun buildWidgetFeed(rows: List<CachedOccurrence>, today: LocalDate): WidgetFeed {
    val todayAndOverdue = mutableListOf<WidgetDatedOccurrence>()
    val anytime = mutableListOf<CachedOccurrence>()
    val upcoming = mutableListOf<WidgetDatedOccurrence>()

    rows.filter { it.status == "pending" }.forEach { row ->
        val date = row.scheduledFor?.let { value ->
            runCatching { Instant.parse(value).atZone(systemZone).toLocalDate() }.getOrNull()
        }
        if (date == null) {
            anytime += row
        } else {
            val item = WidgetDatedOccurrence(date, row)
            if (date <= today) todayAndOverdue += item else upcoming += item
        }
    }

    val datedOrder = compareBy<WidgetDatedOccurrence>(
        { it.date },
        { it.occurrence.title.lowercase(Locale.ROOT) },
        { it.occurrence.id },
    )
    return WidgetFeed(
        todayAndOverdue = todayAndOverdue.sortedWith(datedOrder),
        anytime = anytime.sortedWith(compareBy({ it.title.lowercase(Locale.ROOT) }, { it.id })),
        upcoming = upcoming.sortedWith(datedOrder),
    )
}
