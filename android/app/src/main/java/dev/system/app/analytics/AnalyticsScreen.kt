package dev.system.app.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.system.app.ui.components.VantaCard
import dev.system.app.ui.components.VantaCardTone
import dev.system.app.ui.theme.VantaLayout
import dev.system.app.ui.theme.VantaSpacing
import java.time.format.DateTimeFormatter

@Composable
internal fun AnalyticsScreen(
    summary: AnalyticsSummary,
    onPeriodSelected: (AnalyticsPeriod) -> Unit,
    showPlannedVsActual: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier.widthIn(max = VantaLayout.contentMaxWidth).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = VantaSpacing.lg, vertical = VantaSpacing.md),
    ) {
        Text("Your progress", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "A clear view of the time and routines you planned.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = VantaSpacing.xxs),
        )
        Spacer(Modifier.height(VantaSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnalyticsPeriod.values().forEach { period ->
                FilterChip(
                    selected = summary.period == period,
                    onClick = { onPeriodSelected(period) },
                    label = { Text(period.label) },
                )
            }
        }
        Spacer(Modifier.height(VantaSpacing.md))
        MetricCard(title = summary.period.label) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricColumn("Planned", formatMinutes(summary.plannedMinutes), Modifier.weight(1f))
                if (showPlannedVsActual) {
                    MetricColumn(
                        "Recorded focus",
                        summary.focusedMinutes?.let(::formatMinutes) ?: "No data",
                        Modifier.weight(1f),
                    )
                }
            }
            if (showPlannedVsActual && summary.focusedMinutes != null) {
                Text(
                    "Focus recorded on ${summary.focusCoverageDays} of ${summary.period.days} days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(VantaSpacing.md))
        MetricCard(title = "Tasks") {
            val rate = summary.taskCompletionRate?.let { "${(it * 100).toInt()}%" } ?: "—"
            Text("${summary.completedTaskCount} / ${summary.plannedTaskCount} completed", style = MaterialTheme.typography.titleMedium)
            Text("Completion rate $rate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(VantaSpacing.md))
        MetricCard(title = "Habits") {
            if (summary.habitExpectedCount == 0) {
                Text("No habit occurrences in this period", style = MaterialTheme.typography.bodyMedium)
            } else {
                val rate = summary.habitCompletionRate?.let { "${(it * 100).toInt()}%" } ?: "—"
                Text("${summary.habitCompletedCount} / ${summary.habitExpectedCount} completed", style = MaterialTheme.typography.titleMedium)
                Text("Completion rate $rate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showPlannedVsActual) {
            Spacer(Modifier.height(VantaSpacing.md))
            MetricCard(title = "Focus time") {
                Legend()
                Spacer(Modifier.height(VantaSpacing.xs))
                PlannedFocusedChart(summary.days)
                Spacer(Modifier.height(VantaSpacing.xxs))
                Text("Time comes from completed Focus Mode sessions. Days with no session have no recorded bar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(VantaSpacing.md))
        MetricCard(title = "Planning estimate") {
            val difference = summary.estimationDifferencePercent
            if (difference == null) {
                Text("More completed focus data is needed for an estimate.", style = MaterialTheme.typography.bodyMedium)
                Text("At least ${AnalyticsAggregator.MIN_ESTIMATION_SAMPLES} planned and focused tasks are required.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val rounded = kotlin.math.round(kotlin.math.abs(difference)).toInt()
                val direction = when {
                    difference > 0.5 -> "longer than planned"
                    difference < -0.5 -> "shorter than planned"
                    else -> "close to plan"
                }
                Text("Recorded focus time was about $rounded% $direction", style = MaterialTheme.typography.bodyMedium)
                Text("Based on ${summary.estimationSampleCount} tasks with planned duration and Focus Mode sessions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(VantaSpacing.md))
    }
    }
}

@Composable
private fun MetricCard(title: String, content: @Composable () -> Unit) {
    VantaCard(tone = VantaCardTone.Subtle) {
        Column(Modifier.fillMaxWidth().padding(VantaSpacing.md)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(VantaSpacing.xs))
            content()
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem("Planned", MaterialTheme.colorScheme.primary)
        LegendItem("Recorded focus", MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun LegendItem(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PlannedFocusedChart(points: List<AnalyticsDayPoint>) {
    val primary = MaterialTheme.colorScheme.primary
    val focused = MaterialTheme.colorScheme.tertiary
    val muted = MaterialTheme.colorScheme.outlineVariant
    val tickCount = if (points.size <= 7) points.size else points.size / 5
    val maxMinutes = (points.maxOfOrNull { maxOf(it.plannedMinutes, it.focusedMinutes ?: 0) } ?: 0).coerceAtLeast(1)
    val chartDescription = remember(points) {
        points.joinToString(separator = ". ") { point ->
            val day = point.date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
            val planned = formatMinutes(point.plannedMinutes)
            val focused = point.focusedMinutes?.let(::formatMinutes) ?: "no focus recorded"
            day + ": " + planned + " planned, " + focused + " focused"
        }
    }
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = chartDescription },
        verticalAlignment = Alignment.Bottom,
    ) {
        points.forEachIndexed { index, point ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 1.dp)) {
                    val groupWidth = size.width / 2.8f
                    val gap = 2.dp.toPx()
                    val baseline = size.height - 2.dp.toPx()
                    val plannedHeight = baseline * point.plannedMinutes.toFloat() / maxMinutes
                    drawLine(primary, Offset(size.width / 2f - gap / 2f, baseline), Offset(size.width / 2f - gap / 2f, baseline - plannedHeight), groupWidth, cap = StrokeCap.Round)
                    point.focusedMinutes?.let { minutes ->
                        val focusedHeight = baseline * minutes.toFloat() / maxMinutes
                        drawLine(focused, Offset(size.width / 2f + gap / 2f, baseline), Offset(size.width / 2f + gap / 2f, baseline - focusedHeight), groupWidth, cap = StrokeCap.Round)
                    }
                    drawLine(muted.copy(alpha = 0.45f), Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
                }
                if (tickCount == 0 || index % (points.size / tickCount).coerceAtLeast(1) == 0 || index == points.lastIndex) {
                    Text(point.date.format(DateTimeFormatter.ofPattern("d")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(" ", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun formatMinutes(minutes: Int): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours == 0 -> "${remainder}m"
        remainder == 0 -> "${hours}h"
        else -> "${hours}h ${remainder}m"
    }
}
