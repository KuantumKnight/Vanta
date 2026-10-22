package dev.system.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.system.app.data.CachedOccurrence
import dev.system.app.planner.ScheduleType
import dev.system.app.ui.theme.LocalVantaColors
import dev.system.app.ui.theme.VantaSpacing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
internal fun TaskCard(row: CachedOccurrence, onComplete: (CachedOccurrence) -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val scheduleType = remember(row.scheduleType) {
        runCatching { ScheduleType.valueOf(row.scheduleType) }.getOrDefault(ScheduleType.FLEXIBLE)
    }
    val semanticColors = LocalVantaColors.current
    val (typeLabel, chipBackground, chipForeground) = when (scheduleType) {
        ScheduleType.HARD -> Triple("Fixed", semanticColors.timelineHard, semanticColors.onTimelineHard)
        ScheduleType.SOFT -> Triple("Planned", semanticColors.timelineSoft, semanticColors.onTimelineSoft)
        ScheduleType.FLEXIBLE -> Triple("Anytime", semanticColors.timelineFlexible, semanticColors.onTimelineFlexible)
    }

    VantaCard(
        modifier = Modifier.fillMaxWidth(),
        tone = VantaCardTone.Standard,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                start = VantaSpacing.md,
                end = VantaSpacing.xs,
                top = VantaSpacing.sm,
                bottom = VantaSpacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VantaSpacing.xs),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(VantaSpacing.xxs),
            ) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!row.notes.isNullOrBlank()) {
                    Text(
                        row.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                val localDate = row.taskDate()
                val dateLabel = localDate?.let {
                    if (row.scheduledFor != null && !row.allDay) {
                        val time = runCatching {
                            Instant.parse(row.scheduledFor).atZone(TaskTimeZone).format(DateTimeFormatter.ofPattern("h:mm a"))
                        }.getOrNull()
                        it.format(DateTimeFormatter.ofPattern("EEE, d MMM")) + (time?.let { value -> " · $value" } ?: "")
                    } else it.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
                } ?: if (scheduleType == ScheduleType.FLEXIBLE) "No date" else null
                if (dateLabel != null) {
                    Text(
                        dateLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(VantaSpacing.xs),
                ) {
                    VantaChip(
                        label = typeLabel,
                        containerColor = chipBackground,
                        contentColor = chipForeground,
                    )
                    if (row.kind == "habit") {
                        VantaChip(
                            label = "Habit",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            Checkbox(
                checked = false,
                onCheckedChange = { checked ->
                    if (checked) {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        scope.launch { onComplete(row) }
                    }
                },
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = "Mark ${row.title} complete"
                },
            )
        }
    }
}

@Composable
internal fun SectionHeading(title: String, detail: String) {
    VantaSectionHeader(title = title, detail = detail)
}

@Composable
internal fun EmptyState(
    modifier: Modifier,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    VantaEmptyState(
        title = title,
        body = body,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}
