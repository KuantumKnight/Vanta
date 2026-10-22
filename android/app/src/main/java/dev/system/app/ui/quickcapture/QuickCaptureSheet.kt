package dev.system.app.ui.quickcapture

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.system.app.ui.components.TaskTimeZone
import dev.system.app.ui.components.VantaSectionHeader
import dev.system.app.planner.SchedulePriority
import dev.system.app.planner.ScheduleType
import dev.system.app.ui.theme.VantaIconSize
import dev.system.app.ui.theme.VantaLayout
import dev.system.app.ui.theme.VantaSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** A single create payload shared by structured fields and the small phrase parser. */
internal data class QuickCaptureSubmission(
    val title: String,
    val notes: String?,
    val scheduledFor: Instant?,
    val allDay: Boolean,
    val durationMinutes: Int?,
    val scheduleType: ScheduleType,
    val priority: SchedulePriority,
    val deadlineAt: Instant?,
    val preferredStart: Instant?,
)

internal enum class QuickCaptureValidationError {
    EMPTY_TITLE,
    TITLE_TOO_LONG,
    NOTES_TOO_LONG,
    INVALID_DURATION,
    INVALID_SCHEDULE,
    INVALID_SCHEDULING,
}

internal data class QuickCaptureValidation(
    val submission: QuickCaptureSubmission? = null,
    val error: QuickCaptureValidationError? = null,
)

/** Structured fields are authoritative; untimed tasks are supported by turning scheduling off. */
internal fun validateQuickCapture(
    title: String,
    notes: String,
    scheduleEnabled: Boolean,
    date: LocalDate?,
    startTime: LocalTime?,
    allDay: Boolean,
    durationText: String,
    scheduleType: ScheduleType = if (scheduleEnabled) ScheduleType.SOFT else ScheduleType.FLEXIBLE,
    priority: SchedulePriority = SchedulePriority.NORMAL,
    deadlineAt: Instant? = null,
): QuickCaptureValidation {
    val normalizedTitle = title.trim()
    if (normalizedTitle.isEmpty()) return QuickCaptureValidation(error = QuickCaptureValidationError.EMPTY_TITLE)
    if (normalizedTitle.length > MAX_TITLE_LENGTH) return QuickCaptureValidation(error = QuickCaptureValidationError.TITLE_TOO_LONG)
    if (notes.length > MAX_NOTES_LENGTH) return QuickCaptureValidation(error = QuickCaptureValidationError.NOTES_TOO_LONG)

    val duration = if (durationText.isBlank()) null else durationText.trim().toIntOrNull()
        ?: return QuickCaptureValidation(error = QuickCaptureValidationError.INVALID_DURATION)
    if (duration != null && duration !in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES) {
        return QuickCaptureValidation(error = QuickCaptureValidationError.INVALID_DURATION)
    }

    val scheduledFor = if (!scheduleEnabled) {
        null
    } else {
        val selectedDate = date ?: return QuickCaptureValidation(error = QuickCaptureValidationError.INVALID_SCHEDULE)
        when {
            allDay -> selectedDate.atStartOfDay(TaskTimeZone).toInstant()
            startTime != null -> selectedDate.atTime(startTime).atZone(TaskTimeZone).toInstant()
            else -> return QuickCaptureValidation(error = QuickCaptureValidationError.INVALID_SCHEDULE)
        }
    }
    val effectiveType = if (scheduleEnabled) scheduleType else ScheduleType.FLEXIBLE
    if ((scheduledFor == null) != (effectiveType == ScheduleType.FLEXIBLE) ||
        (allDay && effectiveType != ScheduleType.HARD)
    ) return QuickCaptureValidation(error = QuickCaptureValidationError.INVALID_SCHEDULING)

    return QuickCaptureValidation(
        submission = QuickCaptureSubmission(
            title = normalizedTitle,
            notes = notes.trim().ifBlank { null },
            scheduledFor = scheduledFor,
            allDay = scheduleEnabled && allDay,
            durationMinutes = duration,
            scheduleType = effectiveType,
            priority = priority,
            deadlineAt = deadlineAt,
            preferredStart = if (effectiveType == ScheduleType.SOFT) scheduledFor else null,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickCaptureSheet(
    initialDate: LocalDate,
    source: String?,
    onDismiss: () -> Unit,
    onCreateTask: (QuickCaptureSubmission) -> Unit,
    defaultDurationMinutes: Int? = null,
) {
    val context = LocalContext.current
    var title by remember(initialDate, source, defaultDurationMinutes) { mutableStateOf("") }
    var notes by remember(initialDate, source, defaultDurationMinutes) { mutableStateOf("") }
    var date by remember(initialDate, source, defaultDurationMinutes) { mutableStateOf<LocalDate?>(initialDate) }
    var startTime by remember(initialDate, source, defaultDurationMinutes) { mutableStateOf<LocalTime?>(null) }
    var durationText by remember(initialDate, source, defaultDurationMinutes) {
        mutableStateOf(defaultDurationMinutes?.takeIf { it in 5..1440 }?.toString().orEmpty())
    }
    var scheduleEnabled by remember(initialDate, source, defaultDurationMinutes) { mutableStateOf(true) }
    var allDay by remember(initialDate, source, defaultDurationMinutes) { mutableStateOf(false) }
    var phraseMessage by remember(initialDate, source, defaultDurationMinutes) { mutableStateOf<String?>(null) }
    var scheduleType by remember(initialDate, source) { mutableStateOf(ScheduleType.SOFT) }
    var priority by remember(initialDate, source) { mutableStateOf(SchedulePriority.NORMAL) }
    var deadlineAt by remember(initialDate, source) { mutableStateOf<Instant?>(null) }
    var scheduleTypeMenu by remember { mutableStateOf(false) }
    var priorityMenu by remember { mutableStateOf(false) }

    val validation = validateQuickCapture(
        title = title,
        notes = notes,
        scheduleEnabled = scheduleEnabled,
        date = date,
        startTime = startTime,
        allDay = allDay,
        durationText = durationText,
        scheduleType = scheduleType,
        priority = priority,
        deadlineAt = deadlineAt,
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = VantaLayout.formMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = VantaSpacing.md)
                    .padding(bottom = VantaSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(VantaSpacing.sm),
            ) {
                Text("Quick Capture", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (!source.isNullOrBlank()) {
                    Text(
                        if (source.contains("widget")) "Add to your day from Vanta" else "Capture a task quickly",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                VantaSectionHeader("Details")
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; phraseMessage = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title or quick phrase") },
                    placeholder = { Text("Study OS tomorrow 6 PM for 90 min") },
                    supportingText = { Text("Understands dates, times, and durations.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = {
                            val parsed = parseQuickCapturePhrase(title, initialDate)
                            if (parsed.recognizedSchedule) {
                                title = parsed.title
                                if (parsed.date != null) date = parsed.date
                                if (parsed.startTime != null) startTime = parsed.startTime
                                if (parsed.durationMinutes != null) durationText = parsed.durationMinutes.toString()
                                scheduleEnabled = parsed.date != null || parsed.startTime != null
                                phraseMessage = buildString {
                                    append("Detected")
                                    parsed.date?.let { append(" · ${it.format(DATE_FORMATTER)}") }
                                    parsed.startTime?.let { append(" · ${it.format(TIME_FORMATTER)}") }
                                    parsed.durationMinutes?.let { append(" · ${it} min") }
                                    append(". Review the fields below.")
                                }
                            } else {
                                phraseMessage = "No clear date, time, or duration found. Use the fields below."
                            }
                        },
                    ) { Text("Parse phrase") }
                }
                phraseMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Notes · optional") },
                    minLines = 2,
                    maxLines = 4,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Schedule this task", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Text(
                            if (scheduleEnabled) "Choose a date and start time, or mark all day."
                            else "This task will stay in your anytime list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = scheduleEnabled,
                        onCheckedChange = { enabled ->
                            scheduleEnabled = enabled
                            if (enabled && scheduleType == ScheduleType.FLEXIBLE) scheduleType = ScheduleType.SOFT
                            if (!enabled) allDay = false
                        },
                        modifier = Modifier.semantics { contentDescription = "Schedule this task" },
                    )
                }

                VantaSectionHeader("Timing")
                Column {
                    OutlinedButton(
                        onClick = { scheduleTypeMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (scheduleEnabled) "Schedule type · ${scheduleType.displayLabel()}" else "Anytime")
                    }
                    DropdownMenu(expanded = scheduleTypeMenu, onDismissRequest = { scheduleTypeMenu = false }) {
                        (if (scheduleEnabled) listOf(ScheduleType.HARD, ScheduleType.SOFT) else listOf(ScheduleType.FLEXIBLE))
                            .forEach { type ->
                                DropdownMenuItem(text = { Text(type.displayLabel()) }, onClick = {
                                    scheduleType = type
                                    scheduleTypeMenu = false
                                    if (type != ScheduleType.HARD && allDay) allDay = false
                                })
                            }
                    }
                }
                Column {
                    OutlinedButton(
                        onClick = { priorityMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Priority · ${priority.displayLabel()}") }
                    DropdownMenu(expanded = priorityMenu, onDismissRequest = { priorityMenu = false }) {
                        SchedulePriority.values().forEach { selected ->
                            DropdownMenuItem(text = { Text(selected.displayLabel()) }, onClick = {
                                priority = selected
                                priorityMenu = false
                            })
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        val currentDate = deadlineAt?.atZone(TaskTimeZone)?.toLocalDate() ?: initialDate
                        val currentTime = deadlineAt?.atZone(TaskTimeZone)?.toLocalTime() ?: LocalTime.of(20, 0)
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                val selectedDate = LocalDate.of(year, month + 1, day)
                                TimePickerDialog(context, { _, hour, minute ->
                                    deadlineAt = selectedDate.atTime(hour, minute).atZone(TaskTimeZone).toInstant()
                                }, currentTime.hour, currentTime.minute, false).show()
                            },
                            currentDate.year,
                            currentDate.monthValue - 1,
                            currentDate.dayOfMonth,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(deadlineAt?.let {
                        "Deadline · ${it.atZone(TaskTimeZone).format(DATE_FORMATTER)} · ${it.atZone(TaskTimeZone).format(TIME_FORMATTER)}"
                    } ?: "Add deadline · optional")
                }
                if (deadlineAt != null) TextButton(onClick = { deadlineAt = null }) { Text("Clear deadline") }

                if (scheduleEnabled) {
                    VantaSectionHeader("Schedule")
                    OutlinedButton(
                        onClick = {
                            val current = date ?: initialDate
                            DatePickerDialog(
                                context,
                                { _, year, month, day -> date = LocalDate.of(year, month + 1, day) },
                                current.year,
                                current.monthValue - 1,
                                current.dayOfMonth,
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Date · ${date?.format(DATE_FORMATTER) ?: "Choose"}")
                    }
                    if (!allDay) {
                        OutlinedButton(
                            onClick = {
                                val current = startTime ?: LocalTime.now(TaskTimeZone).withSecond(0).withNano(0)
                                TimePickerDialog(
                                    context,
                                    { _, hour, minute -> startTime = LocalTime.of(hour, minute) },
                                    current.hour,
                                    current.minute,
                                    false,
                                ).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(startTime?.format(TIME_FORMATTER) ?: "Choose a start time")
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = allDay,
                            onCheckedChange = {
                                allDay = it
                                if (it) startTime = null
                            },
                            modifier = Modifier.semantics { contentDescription = "All day" },
                        )
                        Text("All day", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                OutlinedTextField(
                    value = durationText,
                    onValueChange = { value -> durationText = value.filter(Char::isDigit).take(5) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Duration · optional") },
                    placeholder = { Text("Minutes") },
                    supportingText = { Text("Leave blank when you do not want to set a duration.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                )

                val validationMessage = validation.error?.let(::validationMessage)
                if (validationMessage != null) {
                    Text(validationMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                Button(
                    onClick = {
                        val submission = validation.submission ?: return@Button
                        onCreateTask(submission)
                        onDismiss()
                    },
                    enabled = validation.submission != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = VantaIconSize.touchTarget),
                ) {
                    Text("Save task")
                }
                Spacer(Modifier.height(VantaSpacing.xxs))
            }
        }
    }
}

private fun ScheduleType.displayLabel(): String = when (this) {
    ScheduleType.HARD -> "Fixed"
    ScheduleType.SOFT -> "Planned"
    ScheduleType.FLEXIBLE -> "Anytime"
}

private fun SchedulePriority.displayLabel(): String = when (this) {
    SchedulePriority.LOW -> "Low"
    SchedulePriority.NORMAL -> "Normal"
    SchedulePriority.HIGH -> "High"
}

private fun validationMessage(error: QuickCaptureValidationError): String = when (error) {
    QuickCaptureValidationError.EMPTY_TITLE -> "Add a title to save this task."
    QuickCaptureValidationError.TITLE_TOO_LONG -> "Keep the title under 200 characters."
    QuickCaptureValidationError.NOTES_TOO_LONG -> "Keep notes under 4,000 characters."
    QuickCaptureValidationError.INVALID_DURATION -> "Duration must be between 5 and 1,440 minutes."
    QuickCaptureValidationError.INVALID_SCHEDULE -> "Choose a start time or turn on All day. Turn off Schedule for an anytime task."
    QuickCaptureValidationError.INVALID_SCHEDULING -> "Choose HARD or SOFT for a scheduled task. Flexible tasks stay unscheduled, and all-day entries must be HARD."
}

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

private const val MAX_TITLE_LENGTH = 200
private const val MAX_NOTES_LENGTH = 4_000
private const val MIN_DURATION_MINUTES = 5
private const val MAX_DURATION_MINUTES = 1_440
