package dev.system.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.system.app.settings.ThemePreference
import dev.system.app.settings.UserPreferences
import java.time.DayOfWeek

/**
 * Reusable settings surface. Callers own persistence and account actions, keeping this screen
 * independent of navigation and preserving the existing auth flow.
 */
@Composable
internal fun SettingsScreen(
    preferences: UserPreferences,
    onUpdate: ((UserPreferences) -> UserPreferences) -> Unit,
    onReset: () -> Unit,
    onSignOut: () -> Unit = {},
    calendarSettings: (@Composable ColumnScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 20.dp, bottom = 2.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Make Vanta fit the way you plan your day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection("Planning") {
                TimePreferenceRow("Day starts", preferences.dayStartMinutes, "day start") { minute ->
                    if (minute < preferences.dayEndMinutes) onUpdate { it.copy(dayStartMinutes = minute) }
                }
                TimePreferenceRow("Day ends", preferences.dayEndMinutes, "day end") { minute ->
                    if (minute > preferences.dayStartMinutes) onUpdate { it.copy(dayEndMinutes = minute) }
                }
                ChoicePreferenceRow(
                    title = "Default task duration",
                    value = "${preferences.defaultDurationMinutes} min",
                    choices = (listOf(15, 25, 30, 45, 60, 90, 120) + preferences.defaultDurationMinutes)
                        .distinct().sorted().map { "$it min" to it },
                    onSelected = { selected -> onUpdate { it.copy(defaultDurationMinutes = selected) } },
                )
                ChoicePreferenceRow(
                    title = "Scheduling interval",
                    value = "${preferences.snapIntervalMinutes} min",
                    choices = (listOf(5, 10, 15, 20, 30, 60) + preferences.snapIntervalMinutes)
                        .distinct().sorted().map { "$it min" to it },
                    onSelected = { selected -> onUpdate { it.copy(snapIntervalMinutes = selected) } },
                )
                ChoicePreferenceRow(
                    title = "Week starts on",
                    value = preferences.weekStartDay.displayName(),
                    choices = DayOfWeek.values().map { it.displayName() to it },
                    onSelected = { selected -> onUpdate { it.copy(weekStartDay = selected) } },
                )
            }
        }
        item {
            SettingsSection("Notifications") {
                TogglePreferenceRow(
                    title = "Task reminders",
                    description = "Allow reminders for tasks you schedule.",
                    checked = preferences.taskRemindersEnabled,
                    onCheckedChange = { checked -> onUpdate { it.copy(taskRemindersEnabled = checked) } },
                )
                TogglePreferenceRow(
                    title = "Morning briefing",
                    description = "A short summary of the day, delivered at your chosen time.",
                    checked = preferences.morningBriefingEnabled,
                    onCheckedChange = { checked -> onUpdate { it.copy(morningBriefingEnabled = checked) } },
                )
                if (preferences.morningBriefingEnabled) {
                    TimePreferenceRow("Briefing time", preferences.morningBriefingMinute, "briefing time") { minute ->
                        onUpdate { it.copy(morningBriefingMinute = minute) }
                    }
                }
                TogglePreferenceRow(
                    title = "Evening review reminder",
                    description = "A gentle reminder to close out the day.",
                    checked = preferences.eveningReviewEnabled,
                    onCheckedChange = { checked -> onUpdate { it.copy(eveningReviewEnabled = checked) } },
                )
                if (preferences.eveningReviewEnabled) {
                    TimePreferenceRow("Reminder time", preferences.eveningReviewMinute, "review reminder time") { minute ->
                        onUpdate { it.copy(eveningReviewMinute = minute) }
                    }
                }
            }
        }
        item {
            SettingsSection("Appearance") {
                ChoicePreferenceRow(
                    title = "Theme",
                    value = preferences.theme.displayName(),
                    choices = ThemePreference.values().map { it.displayName() to it },
                    onSelected = { selected -> onUpdate { it.copy(theme = selected) } },
                )
            }
        }
        item {
            SettingsSection("Analytics") {
                TogglePreferenceRow(
                    title = "Show planned and actual time",
                    description = "Include focus time alongside planned task time when available.",
                    checked = preferences.showPlannedVsActual,
                    onCheckedChange = { checked -> onUpdate { it.copy(showPlannedVsActual = checked) } },
                )
            }
        }
        if (calendarSettings != null) {
            item { SettingsSection("Calendar", content = calendarSettings) }
        }
        item {
            SettingsSection("Account and data") {
                Text(
                    "Reset Vanta preferences restores settings defaults. Your account, tasks, and sync data stay in place.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showResetConfirmation = true }) { Text("Reset preferences") }
                    TextButton(onClick = onSignOut) { Text("Sign out") }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Reset preferences?") },
            text = { Text("Vanta settings will return to their defaults. Your account and saved tasks will remain.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirmation = false
                    onReset()
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            },
        )
    }
}

@Composable
private fun TimePreferenceRow(
    title: String,
    value: Int,
    label: String,
    onValidTime: (Int) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value.asClockTime()) }
    val parsed = parseClockTime(draft)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = draft,
            onValueChange = { next ->
                if (next.length <= 5 && next.all { it.isDigit() || it == ':' }) {
                    draft = next
                    parseClockTime(next)?.let(onValidTime)
                }
            },
            modifier = Modifier.weight(0.6f),
            label = { Text("HH:mm") },
            singleLine = true,
            isError = draft.isNotEmpty() && parsed == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            supportingText = if (draft.isNotEmpty() && parsed == null) {
                { Text("Enter a time such as 08:30") }
            } else null,
        )
    }
}

@Composable
private fun <T> ChoicePreferenceRow(
    title: String,
    value: String,
    choices: List<Pair<String, T>>,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Column {
            TextButton(onClick = { expanded = true }) { Text(value) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (label, item) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onSelected(item)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TogglePreferenceRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun parseClockTime(value: String): Int? {
    val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(value) ?: return null
    val hours = match.groupValues[1].toIntOrNull() ?: return null
    val minutes = match.groupValues[2].toIntOrNull() ?: return null
    if (hours !in 0..23 || minutes !in 0..59) return null
    return hours * 60 + minutes
}

private fun Int.asClockTime(): String = "%02d:%02d".format(this / 60, this % 60)

private fun DayOfWeek.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)

private fun ThemePreference.displayName(): String = when (this) {
    ThemePreference.SYSTEM -> "System"
    ThemePreference.LIGHT -> "Light"
    ThemePreference.DARK -> "Dark"
}
