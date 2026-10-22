package dev.system.app.ui.taskdetail

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.system.app.data.CachedOccurrence
import dev.system.app.planner.SchedulePriority
import dev.system.app.planner.ScheduleType
import dev.system.app.ui.components.TaskTimeZone
import dev.system.app.ui.quickcapture.QuickCaptureSubmission
import dev.system.app.ui.quickcapture.QuickCaptureValidationError
import dev.system.app.ui.quickcapture.validateQuickCapture
import dev.system.app.ui.theme.VantaLayout
import dev.system.app.ui.theme.VantaSpacing
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskDetailSheet(
    row: CachedOccurrence,
    onDismiss: () -> Unit,
    onSave: suspend (CachedOccurrence, QuickCaptureSubmission) -> Unit,
    onComplete: (CachedOccurrence) -> Unit,
    onStartFocus: (CachedOccurrence) -> Unit,
    canEditTask: suspend (CachedOccurrence) -> Boolean = { candidate ->
        candidate.kind == "task" && candidate.status == "pending"
    },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialDraft = remember(row.id) { TaskDetailDraft.from(row) }
    var draft by remember(row.id) { mutableStateOf(initialDraft) }
    var editing by remember(row.id) { mutableStateOf(false) }
    var showDiscardConfirmation by remember(row.id) { mutableStateOf(false) }
    var closeAfterDiscard by remember(row.id) { mutableStateOf(false) }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var priorityMenuExpanded by remember { mutableStateOf(false) }
    var saving by remember(row.id) { mutableStateOf(false) }
    var saved by remember(row.id) { mutableStateOf(false) }
    var saveError by remember(row.id) { mutableStateOf<String?>(null) }
    var canEdit by remember(row.id) { mutableStateOf(false) }

    LaunchedEffect(row.id, row.kind, row.status) {
        canEdit = row.kind == "task" && row.status == "pending" && canEditTask(row)
    }
    val editable = canEdit
    val actionable = row.kind == "task" && row.status == "pending"
    val dirty = editable && editing && draft != initialDraft && !saved
    val validation = validateQuickCapture(
        title = draft.title,
        notes = draft.notes,
        scheduleEnabled = draft.scheduleEnabled,
        date = draft.date,
        startTime = draft.startTime,
        allDay = draft.allDay,
        durationText = draft.durationText,
        scheduleType = draft.scheduleType,
        priority = draft.priority,
        deadlineAt = draft.deadlineAt,
    )

    fun requestDismiss() {
        if (saving) return
        if (dirty && !saved) {
            closeAfterDiscard = true
            showDiscardConfirmation = true
        } else onDismiss()
    }

    fun requestCancelEdit() {
        if (dirty && !saved) {
            closeAfterDiscard = false
            showDiscardConfirmation = true
        } else {
            draft = initialDraft
            editing = false
        }
    }

    ModalBottomSheet(onDismissRequest = ::requestDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .widthIn(max = VantaLayout.formMaxWidth)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = VantaSpacing.md)
                .padding(bottom = VantaSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(VantaSpacing.sm),
        ) {
            Text(
                row.title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    editable -> "Task details · changes save on this phone first"
                    row.kind == "habit" -> "Habit occurrence · details are read-only"
                    row.status == "completed" -> "Completed task · details are read-only"
                    else -> "This task is read-only"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (editable && editing) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it); saveError = null },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Task title" },
                    label = { Text("Title") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.notes,
                    onValueChange = { draft = draft.copy(notes = it); saveError = null },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Task notes" },
                    label = { Text("Notes · optional") },
                    minLines = 2,
                )

                Text("Schedule", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                if (draft.scheduleEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(VantaSpacing.xs)) {
                        OutlinedButton(
                            onClick = { typeMenuExpanded = true },
                            modifier = Modifier.weight(1f).semantics {
                                contentDescription = "Schedule type, ${draft.scheduleType.label()}"
                            },
                        ) { Text(draft.scheduleType.label()) }
                        DropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                            listOf(ScheduleType.SOFT, ScheduleType.HARD).forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.label()) },
                                    onClick = {
                                        draft = draft.copy(
                                            scheduleType = type,
                                            allDay = draft.allDay && type == ScheduleType.HARD,
                                        )
                                        typeMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val current = draft.date ?: LocalDate.now(TaskTimeZone)
                            DatePickerDialog(
                                context,
                                { _, year, month, day -> draft = draft.copy(date = LocalDate.of(year, month + 1, day)) },
                                current.year,
                                current.monthValue - 1,
                                current.dayOfMonth,
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = "Task date, ${draft.date?.format(DATE_FORMAT) ?: "not selected"}"
                        },
                    ) { Text("Date · ${draft.date?.format(DATE_FORMAT) ?: "Choose"}") }
                    if (!draft.allDay) {
                        OutlinedButton(
                            onClick = {
                                val current = draft.startTime ?: LocalTime.now(TaskTimeZone).withSecond(0).withNano(0)
                                TimePickerDialog(
                                    context,
                                    { _, hour, minute -> draft = draft.copy(startTime = LocalTime.of(hour, minute)) },
                                    current.hour,
                                    current.minute,
                                    false,
                                ).show()
                            },
                            modifier = Modifier.fillMaxWidth().semantics {
                                contentDescription = "Task start time, ${draft.startTime?.format(TIME_FORMAT) ?: "not selected"}"
                            },
                        ) { Text(draft.startTime?.format(TIME_FORMAT) ?: "Choose a start time") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = draft.allDay,
                            onCheckedChange = { allDay ->
                                draft = draft.copy(
                                    allDay = allDay,
                                    scheduleType = if (allDay) ScheduleType.HARD else draft.scheduleType,
                                    startTime = if (allDay) null else draft.startTime,
                                )
                            },
                            modifier = Modifier.semantics { contentDescription = "All day" },
                        )
                        Text("All day")
                    }
                    if (initialDraft.scheduleEnabled) {
                        Text(
                            "A scheduled task can move between Fixed and Planned. Changing it to Anytime is not supported yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text("Anytime task", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "After adding a date and time, this task can move between Fixed and Planned, but it cannot be changed back to Anytime here. Cancel or discard to keep it unscheduled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            draft = draft.copy(
                                scheduleEnabled = true,
                                scheduleType = ScheduleType.SOFT,
                                date = LocalDate.now(TaskTimeZone),
                                startTime = LocalTime.now(TaskTimeZone).withSecond(0).withNano(0),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = "Add a date and time to this Anytime task"
                        },
                    ) { Text("Add a date and time") }
                }

                OutlinedTextField(
                    value = draft.durationText,
                    onValueChange = { draft = draft.copy(durationText = it.filter(Char::isDigit).take(5)) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Task duration in minutes" },
                    label = { Text("Duration · optional") },
                    placeholder = { Text("Minutes") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(VantaSpacing.xs)) {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { priorityMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth().semantics {
                                contentDescription = "Task priority, ${draft.priority.label()}"
                            },
                        ) { Text("Priority · ${draft.priority.label()}") }
                        DropdownMenu(expanded = priorityMenuExpanded, onDismissRequest = { priorityMenuExpanded = false }) {
                            SchedulePriority.values().forEach { priority ->
                                DropdownMenuItem(
                                    text = { Text(priority.label()) },
                                    onClick = { draft = draft.copy(priority = priority); priorityMenuExpanded = false },
                                )
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val currentDate = draft.deadlineAt?.atZone(TaskTimeZone)?.toLocalDate() ?: LocalDate.now(TaskTimeZone)
                        val currentTime = draft.deadlineAt?.atZone(TaskTimeZone)?.toLocalTime() ?: LocalTime.of(20, 0)
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                val date = LocalDate.of(year, month + 1, day)
                                TimePickerDialog(context, { _, hour, minute ->
                                    draft = draft.copy(deadlineAt = date.atTime(hour, minute).atZone(TaskTimeZone).toInstant())
                                }, currentTime.hour, currentTime.minute, false).show()
                            },
                            currentDate.year,
                            currentDate.monthValue - 1,
                            currentDate.dayOfMonth,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Task deadline, ${draft.deadlineAt?.let { DATE_TIME_FORMAT.format(it.atZone(TaskTimeZone)) } ?: "not set"}"
                    },
                ) {
                    Text(draft.deadlineAt?.let { "Deadline · ${DATE_TIME_FORMAT.format(it.atZone(TaskTimeZone))}" } ?: "Add deadline · optional")
                }
                if (draft.deadlineAt != null) TextButton(onClick = { draft = draft.copy(deadlineAt = null) }) { Text("Clear deadline") }

                validation.error?.let { error ->
                    Text(
                        validationMessage(error),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                saveError?.let {
                    Text(
                        it,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = {
                        val submission = validation.submission ?: return@Button
                        saving = true
                        saveError = null
                        scope.launch {
                            try {
                                onSave(row, submission)
                                saved = true
                                onDismiss()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                saveError = error.message ?: "Could not save this task."
                            } finally {
                                saving = false
                            }
                        }
                    },
                    enabled = dirty && !saving && validation.submission != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (saving) "Saving…" else "Save changes") }
                TextButton(onClick = ::requestCancelEdit, modifier = Modifier.fillMaxWidth(), enabled = !saving) { Text("Cancel edit") }
            } else {
                ReadOnlyDetails(row)
                if (editable) {
                    Button(
                        onClick = { editing = true; saveError = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Edit") }
                } else {
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
            }

            if (actionable && !editing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(VantaSpacing.xs),
                ) {
                    OutlinedButton(
                        onClick = { onStartFocus(row); onDismiss() },
                        modifier = Modifier.weight(1f),
                        enabled = !saving,
                    ) { Text("Start focus") }
                    Button(
                        onClick = { onComplete(row); onDismiss() },
                        modifier = Modifier.weight(1f),
                        enabled = !saving,
                    ) { Text("Complete") }
                }
            }
            Spacer(Modifier.height(VantaSpacing.xs))
        }
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits have not been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirmation = false
                    draft = initialDraft
                    if (closeAfterDiscard) onDismiss() else editing = false
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { showDiscardConfirmation = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun ReadOnlyDetails(row: CachedOccurrence) {
    val schedule = row.scheduledFor?.let { value ->
        runCatching {
            val zoned = Instant.parse(value).atZone(TaskTimeZone)
            if (row.allDay) "All day · ${DATE_FORMAT.format(zoned)}" else DATE_TIME_FORMAT.format(zoned)
        }.getOrNull()
    }
    Column(verticalArrangement = Arrangement.spacedBy(VantaSpacing.xs)) {
        Text(schedule ?: "Anytime")
        row.durationMinutes?.let { Text("$it minutes") }
        Text("${runCatching { ScheduleType.valueOf(row.scheduleType).label() }.getOrDefault("Anytime")} · ${row.priority.lowercase().replaceFirstChar(Char::uppercase)} priority")
        row.deadlineAt?.let { deadline ->
            runCatching { DATE_TIME_FORMAT.format(Instant.parse(deadline).atZone(TaskTimeZone)) }
                .getOrNull()?.let { Text("Deadline · $it") }
        }
        if (!row.notes.isNullOrBlank()) {
            Text("Notes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(row.notes)
        }
    }
}

private data class TaskDetailDraft(
    val title: String,
    val notes: String,
    val scheduleEnabled: Boolean,
    val date: LocalDate?,
    val startTime: LocalTime?,
    val allDay: Boolean,
    val durationText: String,
    val scheduleType: ScheduleType,
    val priority: SchedulePriority,
    val deadlineAt: Instant?,
) {
    companion object {
        fun from(row: CachedOccurrence): TaskDetailDraft {
            val scheduledAt = row.scheduledFor?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val zoned = scheduledAt?.atZone(TaskTimeZone)
            return TaskDetailDraft(
                title = row.title,
                notes = row.notes.orEmpty(),
                scheduleEnabled = scheduledAt != null,
                date = zoned?.toLocalDate(),
                startTime = zoned?.toLocalTime()?.takeUnless { row.allDay },
                allDay = row.allDay,
                durationText = row.durationMinutes?.toString().orEmpty(),
                scheduleType = runCatching { ScheduleType.valueOf(row.scheduleType) }.getOrDefault(ScheduleType.FLEXIBLE),
                priority = runCatching { SchedulePriority.valueOf(row.priority) }.getOrDefault(SchedulePriority.NORMAL),
                deadlineAt = row.deadlineAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }
    }
}

private fun ScheduleType.label(): String = when (this) {
    ScheduleType.HARD -> "Fixed"
    ScheduleType.SOFT -> "Planned"
    ScheduleType.FLEXIBLE -> "Anytime"
}

private fun SchedulePriority.label(): String = when (this) {
    SchedulePriority.LOW -> "Low"
    SchedulePriority.NORMAL -> "Normal"
    SchedulePriority.HIGH -> "High"
}

private fun validationMessage(error: QuickCaptureValidationError): String = when (error) {
    QuickCaptureValidationError.EMPTY_TITLE -> "Add a title to save this task."
    QuickCaptureValidationError.TITLE_TOO_LONG -> "Keep the title under 200 characters."
    QuickCaptureValidationError.NOTES_TOO_LONG -> "Keep notes under 4,000 characters."
    QuickCaptureValidationError.INVALID_DURATION -> "Duration must be between 5 and 1,440 minutes."
    QuickCaptureValidationError.INVALID_SCHEDULE -> "Choose a date and start time."
    QuickCaptureValidationError.INVALID_SCHEDULING -> "Choose a supported schedule type."
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")
private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")
