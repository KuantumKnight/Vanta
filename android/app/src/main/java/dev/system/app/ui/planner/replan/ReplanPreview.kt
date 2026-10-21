package dev.system.app.ui.planner.replan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.system.app.planner.CandidateSlot
import dev.system.app.planner.PlanProposal
import dev.system.app.planner.ScheduleConflict
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One conflict and its deterministic choices. Choosing a slot is delegated to the caller. */
@Composable
internal fun ConflictResolutionCard(
    conflict: ScheduleConflict,
    alternatives: List<CandidateSlot>,
    zoneId: ZoneId,
    onSelectAlternative: (CandidateSlot) -> Unit,
    onKeepSchedule: () -> Unit,
    onChooseAnotherTime: () -> Unit,
    canChooseAnotherTime: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(conflict.type.name.replace('_', ' '), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            Text(conflict.message, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (alternatives.isEmpty()) {
                Text(
                    "No safe alternative is available in the remaining planning window.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                alternatives.forEachIndexed { index, slot ->
                    val start = slot.start.atZone(zoneId).format(timeFormatter)
                    val end = slot.end.atZone(zoneId).format(timeFormatter)
                    OutlinedButton(
                        onClick = { onSelectAlternative(slot) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Move to $start–$end  ·  option ${index + 1}")
                    }
                    Text(
                        slot.scoreReasons.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onKeepSchedule) { Text("Leave as is") }
                if (canChooseAnotherTime) {
                    OutlinedButton(onClick = onChooseAnotherTime) { Text("Choose another time") }
                }
            }
        }
    }
}

/** Shows the entire replan before the caller queues any local-first schedule mutations. */
@Composable
internal fun ReplanPreviewCard(
    proposal: PlanProposal,
    taskTitles: Map<String, String>,
    zoneId: ZoneId,
    onApply: (PlanProposal) -> Unit,
    onAdjust: () -> Unit,
    onKeepSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Proposed remaining-day plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (proposal.placements.isEmpty() && proposal.unscheduled.isEmpty()) {
                Text("Nothing needs to move.", style = MaterialTheme.typography.bodyMedium)
            }
            proposal.placements.forEach { placement ->
                val start = placement.start.atZone(zoneId).format(timeFormatter)
                val end = placement.end.atZone(zoneId).format(timeFormatter)
                val title = taskTitles[placement.occurrenceId] ?: "Task"
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("$start–$end  $title", style = MaterialTheme.typography.bodyMedium)
                    if (placement.reason.isNotBlank()) {
                        Text(placement.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (proposal.unscheduled.isNotEmpty()) {
                Text("Needs another time", style = MaterialTheme.typography.titleSmall)
                proposal.unscheduled.forEach { item ->
                    val title = taskTitles[item.occurrenceId] ?: "Task"
                    Text("$title — ${item.reason}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onApply(proposal) },
                    enabled = proposal.placements.isNotEmpty(),
                ) { Text("Apply changes") }
                OutlinedButton(onClick = onAdjust) { Text("Adjust") }
                OutlinedButton(onClick = onKeepSchedule) { Text("Keep schedule") }
            }
            Text(
                "No schedule changes are made until you choose Apply changes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
