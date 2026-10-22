package dev.system.app.briefing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import dev.system.app.ui.components.VantaCard
import dev.system.app.ui.components.VantaCardTone
import dev.system.app.ui.theme.VantaSpacing
import java.time.format.DateTimeFormatter

/** A compact dashboard card; the host owns navigation and decides when to show it. */
@Composable
internal fun MorningBriefingCard(
    briefing: MorningBriefing,
    onViewToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    VantaCard(modifier = modifier.fillMaxWidth(), tone = VantaCardTone.Standard) {
        Column(
            modifier = Modifier.padding(horizontal = VantaSpacing.md, vertical = VantaSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(VantaSpacing.xxs),
        ) {
            Text(
                briefing.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )

            val itemLabel = if (briefing.scheduledItemCount == 1) "scheduled item" else "scheduled items"
            val habitLabel = if (briefing.habitCount == 1) "habit" else "habits"
            val importantTaskLabel = briefing.importantTaskCount?.let { count ->
                if (count == 1) "$count important task" else "$count important tasks"
            }
            val overview = buildList {
                add("${briefing.scheduledItemCount} $itemLabel")
                add("${briefing.habitCount} $habitLabel")
                briefing.plannedMinutes?.let { add("${formatDuration(it)} planned") }
                importantTaskLabel?.let { add(it) }
            }.joinToString(" · ")
            Text(
                overview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            briefing.firstItem?.let { first ->
                val timing = if (first.allDay) "All day" else first.time?.format(
                    DateTimeFormatter.ofPattern("h:mm a", locale),
                )
                val firstItemText = timing?.let { "First item · $it · ${first.title}" }
                    ?: "First item · ${first.title}"
                Text(firstItemText, style = MaterialTheme.typography.bodyMedium)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onViewToday) {
                    Text("View Today")
                }
            }
        }
    }
}

private fun formatDuration(totalMinutes: Long): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}
