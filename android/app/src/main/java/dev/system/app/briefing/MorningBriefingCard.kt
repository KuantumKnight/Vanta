package dev.system.app.briefing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A compact dashboard card; the host owns navigation and decides when to show it. */
@Composable
internal fun MorningBriefingCard(
    briefing: MorningBriefing,
    onViewToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Good morning", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                briefing.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val itemLabel = if (briefing.scheduledItemCount == 1) "scheduled item" else "scheduled items"
            val habitLabel = if (briefing.habitCount == 1) "habit" else "habits"
            Text("${briefing.scheduledItemCount} $itemLabel · ${briefing.habitCount} $habitLabel")

            briefing.plannedMinutes?.let { minutes ->
                Text("${formatDuration(minutes)} planned")
            }

            briefing.firstItem?.let { first ->
                val timing = if (first.allDay) "All day" else first.time?.format(
                    DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()),
                )
                if (timing != null) {
                    Text("First item: $timing · ${first.title}", style = MaterialTheme.typography.bodyMedium)
                }
            }

            briefing.importantTaskCount?.let { count ->
                val label = if (count == 1) "important task" else "important tasks"
                Text("$count $label")
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
