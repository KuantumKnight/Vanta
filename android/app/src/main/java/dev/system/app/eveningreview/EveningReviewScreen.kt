package dev.system.app.eveningreview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.system.app.data.CachedOccurrence
import java.time.format.DateTimeFormatter

/**
 * A calm close-out surface. The host owns persistence and all task mutations; selecting an
 * action only invokes its callback and never rolls tasks automatically.
 */
@Composable
internal fun EveningReviewScreen(
    summary: EveningReviewSummary,
    note: String,
    reviewFinished: Boolean,
    resolvedOccurrenceIds: Set<String> = emptySet(),
    onMoveToTomorrow: (CachedOccurrence) -> Unit,
    onKeepDeadline: (CachedOccurrence) -> Unit,
    onReschedule: (CachedOccurrence) -> Unit,
    onSkip: (CachedOccurrence) -> Unit,
    onNoteChange: (String) -> Unit,
    onFinishDay: (dateText: String, note: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateLabel = remember(summary.date) {
        summary.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM"))
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 20.dp),
    ) {
        item {
            Column {
                Text(dateLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Today", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${summary.plannedCount} planned · ${summary.completedCount} completed · ${summary.remainingCount} remaining",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                summary.focusMinutes?.let { minutes ->
                    if (minutes > 0L) {
                        Text(
                            "${minutes}m recorded focus",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        if (summary.completed.isNotEmpty()) {
            item { SectionTitle("Completed") }
            items(summary.completed, key = { "done-${it.id}" }) { row ->
                OccurrenceRow(row = row, status = "✓", modifier = Modifier.fillMaxWidth())
            }
        }

        if (summary.remaining.isNotEmpty()) {
            item { SectionTitle("Remaining") }
            items(summary.remaining, key = { "remaining-${it.id}" }) { row ->
                RemainingOccurrenceRow(
                    row = row,
                    resolved = row.id in resolvedOccurrenceIds,
                    onMoveToTomorrow = { onMoveToTomorrow(row) },
                    onKeepDeadline = { onKeepDeadline(row) },
                    onReschedule = { onReschedule(row) },
                    onSkip = { onSkip(row) },
                )
            }
        }

        if (summary.skipped.isNotEmpty()) {
            item { SectionTitle("Skipped") }
            items(summary.skipped, key = { "skipped-${it.id}" }) { row ->
                OccurrenceRow(
                    row = row,
                    status = "Skipped",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (summary.planned.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
                    Text(
                        "No scheduled tasks to review today. You can finish your day whenever you’re ready.",
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = note,
                onValueChange = onNoteChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("How did today go? (optional)") },
                placeholder = { Text("A short note for yourself") },
                minLines = 2,
                maxLines = 4,
            )
        }

        item {
            Button(
                onClick = { onFinishDay(summary.date.toString(), note.trim()) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text(if (reviewFinished) "Update review" else "Finish day")
            }
            if (reviewFinished) {
                Text(
                    "Review saved. You can still change your note or task choices.",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun OccurrenceRow(row: CachedOccurrence, status: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
            Text(row.title, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun RemainingOccurrenceRow(
    row: CachedOccurrence,
    resolved: Boolean,
    onMoveToTomorrow: () -> Unit,
    onKeepDeadline: () -> Unit,
    onReschedule: () -> Unit,
    onSkip: () -> Unit,
) {
    var menuExpanded by remember(row.id) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("○", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge)
                if (resolved) {
                    Text(
                        "Choice noted for this review",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { menuExpanded = true }) {
                    Text(if (resolved) "Review" else "Choose")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Move to tomorrow") },
                        onClick = { menuExpanded = false; onMoveToTomorrow() },
                    )
                    DropdownMenuItem(
                        text = { Text("Keep current deadline") },
                        onClick = { menuExpanded = false; onKeepDeadline() },
                    )
                    DropdownMenuItem(
                        text = { Text("Reschedule") },
                        onClick = { menuExpanded = false; onReschedule() },
                    )
                    if (row.kind == "habit") {
                        DropdownMenuItem(
                            text = { Text("Skip this occurrence") },
                            onClick = { menuExpanded = false; onSkip() },
                        )
                    }
                }
            }
        }
    }
}
