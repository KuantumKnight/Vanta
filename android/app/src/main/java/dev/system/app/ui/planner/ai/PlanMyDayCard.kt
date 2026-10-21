package dev.system.app.ui.planner.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.system.app.planner.PlanProposal
import dev.system.app.planner.PlanningContext
import dev.system.app.planner.ai.PlanValidationResult
import dev.system.app.planner.ai.validatePlanProposal
import dev.system.app.ui.planner.ai.PlanMyDayUiState.Preview
import java.time.format.DateTimeFormatter

internal sealed interface PlanMyDayUiState {
    data object Ready : PlanMyDayUiState
    data object Requesting : PlanMyDayUiState
    data class Preview(val result: PlanValidationResult) : PlanMyDayUiState
    data class Unavailable(val message: String) : PlanMyDayUiState
}

/**
 * Optional AI planning surface. Request and apply are callbacks so the caller owns network and
 * local-first mutation behavior. This component never writes a schedule itself.
 */
@Composable
internal fun PlanMyDayCard(
    context: PlanningContext,
    state: PlanMyDayUiState,
    freeMinutes: Long,
    onRequestPlan: () -> Unit,
    onApplyPlan: (PlanProposal) -> Unit,
    onAdjust: (PlanProposal) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val taskById = context.flexibleTasks.associateBy { it.occurrenceId }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Plan my day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${context.flexibleTasks.size} flexible tasks · ${freeMinutes.coerceAtLeast(0)} min free",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (state) {
                PlanMyDayUiState.Ready -> Button(
                    onClick = onRequestPlan,
                    enabled = context.flexibleTasks.isNotEmpty(),
                ) { Text("✨ Plan my day") }

                PlanMyDayUiState.Requesting -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Preparing a proposal…", style = MaterialTheme.typography.bodyMedium)
                }

                is Preview -> {
                    val currentResult = remember(context, state.result) {
                        validatePlanProposal(context, state.result.proposal)
                    }
                    val proposal = currentResult.proposal
                    Text("Proposed ${context.date}", style = MaterialTheme.typography.titleSmall)
                    if (proposal.placements.isEmpty() && proposal.unscheduled.isEmpty()) {
                        Text("There are no flexible tasks to schedule.", style = MaterialTheme.typography.bodyMedium)
                    }
                    proposal.placements.forEach { placement ->
                        val title = taskById[placement.occurrenceId]?.title ?: "Task"
                        val start = placement.start.atZone(context.zoneId).format(timeFormatter)
                        val end = placement.end.atZone(context.zoneId).format(timeFormatter)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("$start–$end  $title", style = MaterialTheme.typography.bodyMedium)
                            if (placement.reason.isNotBlank()) {
                                Text(placement.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (proposal.unscheduled.isNotEmpty()) {
                        Text("Unscheduled", style = MaterialTheme.typography.titleSmall)
                        proposal.unscheduled.forEach { item ->
                            val title = taskById[item.occurrenceId]?.title ?: "Task"
                            Text("• $title — ${item.reason}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    currentResult.issues.forEach { issue ->
                        Text(
                            issue.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onApplyPlan(proposal) },
                            enabled = currentResult.isApplicable,
                        ) { Text("Apply plan") }
                        OutlinedButton(onClick = { onAdjust(proposal) }) { Text("Adjust") }
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    }
                    Text(
                        "Review each time before applying. No changes are made until you choose Apply plan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is PlanMyDayUiState.Unavailable -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRequestPlan) { Text("Try again") }
                }
            }
            if (state == PlanMyDayUiState.Ready || state is PlanMyDayUiState.Unavailable) {
                Text(
                    "Task titles and schedule times are shared for planning. Notes, locations, attendee lists, and calendar IDs are not.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
