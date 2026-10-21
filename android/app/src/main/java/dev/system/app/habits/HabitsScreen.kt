package dev.system.app.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.TextStyle

data class HabitTodayOccurrence(
    val occurrenceId: String,
    val habitId: String,
    val title: String,
    val localDate: LocalDate,
    val status: HabitOccurrenceStatus,
)

data class HabitSummary(
    val id: String,
    val title: String,
    val todayOccurrences: List<HabitTodayOccurrence>,
    val currentStreak: Int,
    val bestStreak: Int,
    val streakUnitLabel: String = "days",
)

/** A null status means no occurrence was scheduled on this calendar date. */
data class HabitHistoryDay(
    val date: LocalDate,
    val status: HabitOccurrenceStatus?,
)

data class HabitDetail(
    val id: String,
    val title: String,
    val scheduleDescription: String,
    val currentStreak: Int,
    val bestStreak: Int,
    val streakUnitLabel: String,
    /** Exactly seven entries, ordered by the user's week-start preference when available. */
    val thisWeek: List<HabitHistoryDay>,
    /** A bounded range. The UI renders only the final 28 entries. */
    val history: List<HabitHistoryDay>,
)

/**
 * A data-driven destination for today's expected habit occurrences. The caller should update its
 * Room-backed projection optimistically before invoking [onComplete], so offline completion feels
 * immediate and the same completion queue used by Today can replay it later.
 */
@Composable
internal fun HabitsScreen(
    habits: List<HabitSummary>,
    onComplete: (HabitTodayOccurrence) -> Unit,
    onOpenHabit: (HabitSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val occurrences = habits.flatMap { it.todayOccurrences }
    val completed = occurrences.count { it.status == HabitOccurrenceStatus.COMPLETED }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("TODAY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("Habits", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            Text(
                "$completed / ${occurrences.size} complete",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
        }

        if (habits.isEmpty() || occurrences.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("No habits due today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "Your routine can wait until its next scheduled day.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        } else {
            items(occurrences, key = { it.occurrenceId }) { occurrence ->
                HabitOccurrenceRow(
                    occurrence = occurrence,
                    onComplete = { onComplete(occurrence) },
                    onOpen = { habits.firstOrNull { it.id == occurrence.habitId }?.let(onOpenHabit) },
                )
            }
        }

        item {
            Text("YOUR ROUTINES", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        }
        items(habits, key = { "summary-${it.id}" }) { habit ->
            HabitSummaryRow(habit = habit, onClick = { onOpenHabit(habit) })
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
internal fun HabitDetailScreen(
    detail: HabitDetail,
    onComplete: (HabitTodayOccurrence) -> Unit,
    todayOccurrence: HabitTodayOccurrence?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(detail.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(detail.scheduleDescription, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StreakMetricCard("Current streak", detail.currentStreak, detail.streakUnitLabel, Modifier.weight(1f))
            StreakMetricCard("Best streak", detail.bestStreak, detail.streakUnitLabel, Modifier.weight(1f))
        }

        Text("THIS WEEK", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        WeekHistoryRow(detail.thisWeek)

        Text("RECENT HISTORY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        HistoryHeatmap(detail.history.sortedBy { it.date }.takeLast(28))

        val dueToday = todayOccurrence?.takeIf { it.status == HabitOccurrenceStatus.PENDING }
        if (dueToday != null) {
            TextButton(onClick = { onComplete(dueToday) }) {
                Text("Mark today complete")
            }
        }
    }
}

@Composable
private fun HabitOccurrenceRow(
    occurrence: HabitTodayOccurrence,
    onComplete: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                occurrence.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (occurrence.status == HabitOccurrenceStatus.SKIPPED) {
                Text("Skipped", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(
                checked = occurrence.status == HabitOccurrenceStatus.COMPLETED,
                enabled = occurrence.status == HabitOccurrenceStatus.PENDING,
                onCheckedChange = { checked -> if (checked) onComplete() },
                modifier = Modifier.semantics { contentDescription = "Mark ${occurrence.title} complete" },
            )
        }
    }
}

@Composable
private fun HabitSummaryRow(habit: HabitSummary, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(habit.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Current ${habit.currentStreak} expected occurrences · best ${habit.bestStreak} in last 365 days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text("Details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun StreakMetricCard(label: String, value: Int, unit: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WeekHistoryRow(days: List<HabitHistoryDay>) {
    val locale = LocalLocale.current.platformLocale
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.take(7).forEach { day ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HistoryDot(day, Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun HistoryHeatmap(days: List<HabitHistoryDay>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        days.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { day -> HistoryDot(day, Modifier.size(26.dp)) }
            }
        }
    }
}

@Composable
private fun HistoryDot(day: HabitHistoryDay, modifier: Modifier = Modifier) {
    val (background, symbol, stateLabel) = when (day.status) {
        HabitOccurrenceStatus.COMPLETED -> Triple(MaterialTheme.colorScheme.primaryContainer, "✓", "completed")
        HabitOccurrenceStatus.PENDING -> Triple(MaterialTheme.colorScheme.surfaceVariant, "·", "not completed")
        HabitOccurrenceStatus.SKIPPED -> Triple(MaterialTheme.colorScheme.surfaceVariant, "–", "skipped")
        null -> Triple(MaterialTheme.colorScheme.background, "", "not scheduled")
    }
    Box(
        modifier = modifier
            .background(background, CircleShape)
            .semantics { contentDescription = "${day.date}: $stateLabel" },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
