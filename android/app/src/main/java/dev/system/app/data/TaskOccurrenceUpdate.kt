package dev.system.app.data

import dev.system.app.planner.SchedulePriority
import dev.system.app.planner.ScheduleType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** A patch value that distinguishes keeping a field from setting it to null. */
sealed interface FieldChange<out T> {
    data object Unchanged : FieldChange<Nothing>
    data class Set<T>(val value: T) : FieldChange<T>
}

/** Schedule edits keep the date and all-day flag coherent as one operation. */
data class TaskOccurrenceSchedule(
    val scheduledFor: Instant,
    val allDay: Boolean,
)

/**
 * Editable values supported by the existing one-off update RPC. Title and notes belong to `tasks`;
 * schedule, duration, priority, and timing bounds belong to the selected `task_occurrences` row.
 * The RPC mirrors schedule type, priority, and duration back to the task's defaults where applicable.
 */
data class TaskOccurrenceUpdate(
    val title: FieldChange<String> = FieldChange.Unchanged,
    val notes: FieldChange<String?> = FieldChange.Unchanged,
    val schedule: FieldChange<TaskOccurrenceSchedule> = FieldChange.Unchanged,
    val durationMinutes: FieldChange<Int?> = FieldChange.Unchanged,
    val scheduleType: FieldChange<ScheduleType> = FieldChange.Unchanged,
    val priority: FieldChange<SchedulePriority> = FieldChange.Unchanged,
    val deadlineAt: FieldChange<Instant?> = FieldChange.Unchanged,
    val earliestStart: FieldChange<Instant?> = FieldChange.Unchanged,
    val latestEnd: FieldChange<Instant?> = FieldChange.Unchanged,
    val preferredStart: FieldChange<Instant?> = FieldChange.Unchanged,
) {
    internal fun applyTo(current: CachedOccurrence): CachedOccurrence {
        validateTaskOccurrenceEligibility(current)
        require(listOf(
            title, notes, schedule, durationMinutes, scheduleType, priority,
            deadlineAt, earliestStart, latestEnd, preferredStart,
        ).any { it !is FieldChange.Unchanged }) { "Choose at least one task field to update." }

        val normalizedTitle = when (val change = title) {
            FieldChange.Unchanged -> current.title
            is FieldChange.Set<*> -> (change.value as String).trim().also {
                require(it.isNotEmpty() && it.length <= 200) { "Task title must be between 1 and 200 characters." }
            }
        }
        val updatedNotes = when (val change = notes) {
            FieldChange.Unchanged -> current.notes
            is FieldChange.Set<*> -> (change.value as String?).also {
                require(it == null || it.length <= 4000) { "Task notes must be 4,000 characters or fewer." }
            }
        }
        val updatedSchedule = when (val change = schedule) {
            FieldChange.Unchanged -> current.scheduledFor to current.allDay
            is FieldChange.Set<*> -> (change.value as TaskOccurrenceSchedule).let {
                it.scheduledFor.toString() to it.allDay
            }
        }
        val updatedDuration = when (val change = durationMinutes) {
            FieldChange.Unchanged -> current.durationMinutes
            is FieldChange.Set<*> -> (change.value as Int?).also {
                require(it == null || it in 5..1440) { "Duration must be between 5 and 1,440 minutes." }
            }
        }
        val requestedType = (scheduleType as? FieldChange.Set<ScheduleType>)?.value
        val currentType = runCatching { ScheduleType.valueOf(current.scheduleType) }
            .getOrElse { throw TaskOccurrenceUpdateValidationException("The task has an unsupported schedule type.") }
        val updatedType = requestedType ?: if (
            schedule !is FieldChange.Unchanged && currentType == ScheduleType.FLEXIBLE
        ) ScheduleType.SOFT else currentType
        val updatedPriority = when (val change = priority) {
            FieldChange.Unchanged -> current.priority
            is FieldChange.Set<*> -> (change.value as SchedulePriority).name
        }
        val updatedDeadline = timestamp(deadlineAt, current.deadlineAt)
        val updatedEarliest = timestamp(earliestStart, current.earliestStart)
        val updatedLatest = timestamp(latestEnd, current.latestEnd)
        val updatedPreferred = timestamp(preferredStart, current.preferredStart)

        require(updatedType == ScheduleType.FLEXIBLE && updatedSchedule.first == null ||
            updatedType != ScheduleType.FLEXIBLE && updatedSchedule.first != null) {
            "Flexible tasks stay unscheduled; hard and soft commitments need a start time."
        }
        require(!updatedSchedule.second || updatedType == ScheduleType.HARD) {
            "Only hard commitments can be all-day."
        }
        val earliestInstant = updatedEarliest?.let(Instant::parse)
        val latestInstant = updatedLatest?.let(Instant::parse)
        val deadlineInstant = updatedDeadline?.let(Instant::parse)
        require(earliestInstant == null || latestInstant == null || latestInstant >= earliestInstant) {
            "The allowed scheduling window ends before it begins."
        }
        require(earliestInstant == null || deadlineInstant == null || deadlineInstant >= earliestInstant) {
            "The deadline must be at or after the earliest start."
        }

        return current.copy(
            title = normalizedTitle,
            notes = updatedNotes,
            scheduledFor = updatedSchedule.first,
            allDay = updatedSchedule.second,
            durationMinutes = updatedDuration,
            scheduleType = updatedType.name,
            priority = updatedPriority,
            deadlineAt = updatedDeadline,
            earliestStart = updatedEarliest,
            latestEnd = updatedLatest,
            preferredStart = updatedPreferred,
        )
    }

    internal fun toMutation(
        operationId: String,
        userId: String,
        occurrence: CachedOccurrence,
    ): PendingMutation {
        // Scheduling an unscheduled FLEXIBLE task implicitly turns it into a SOFT commitment.
        // Persist that inferred type along with the date so server and optimistic states agree.
        val persistedScheduleType = when {
            scheduleType is FieldChange.Set<*> -> scheduleType.value as ScheduleType
            schedule !is FieldChange.Unchanged && occurrence.scheduleType == ScheduleType.FLEXIBLE.name ->
                ScheduleType.SOFT
            else -> null
        }
        return PendingMutation(
            operationId = operationId,
            userId = userId,
            operationType = "update_task",
            targetOccurrenceId = occurrence.id,
            targetTaskId = occurrence.taskId,
            updateTitle = title is FieldChange.Set<*>,
            updateNotes = notes is FieldChange.Set<*>,
            updateSchedule = schedule is FieldChange.Set<*>,
            updateDuration = durationMinutes is FieldChange.Set<*>,
            updateScheduleType = persistedScheduleType != null,
            updatePriority = priority is FieldChange.Set<*>,
            updateDeadlineAt = deadlineAt is FieldChange.Set<*>,
            updateEarliestStart = earliestStart is FieldChange.Set<*>,
            updateLatestEnd = latestEnd is FieldChange.Set<*>,
            updatePreferredStart = preferredStart is FieldChange.Set<*>,
            title = (title as? FieldChange.Set<String>)?.value?.trim(),
            notes = (notes as? FieldChange.Set<String?>)?.value,
            scheduledFor = (schedule as? FieldChange.Set<TaskOccurrenceSchedule>)?.value?.scheduledFor?.toString(),
            allDay = (schedule as? FieldChange.Set<TaskOccurrenceSchedule>)?.value?.allDay,
            durationMinutes = (durationMinutes as? FieldChange.Set<Int?>)?.value,
            scheduleType = persistedScheduleType?.name,
            priority = (priority as? FieldChange.Set<SchedulePriority>)?.value?.name,
            deadlineAt = timestampPayload(deadlineAt),
            earliestStart = timestampPayload(earliestStart),
            latestEnd = timestampPayload(latestEnd),
            preferredStart = timestampPayload(preferredStart),
        )
    }

    private fun timestamp(change: FieldChange<Instant?>, current: String?): String? = when (change) {
        FieldChange.Unchanged -> current
        is FieldChange.Set<*> -> (change.value as Instant?)?.toString()
    }

    private fun timestampPayload(change: FieldChange<Instant?>): String? =
        (change as? FieldChange.Set<*>)?.value?.let { it as Instant? }?.toString()
}

internal fun validateTaskOccurrenceEligibility(occurrence: CachedOccurrence) {
    if (occurrence.id.startsWith("local:") || occurrence.taskId.startsWith("local:")) {
        throw TaskOccurrenceUpdateValidationException("This offline-created task cannot be edited until its create syncs.")
    }
    if (occurrence.kind != "task") {
        throw TaskOccurrenceUpdateValidationException("Only one-off tasks can be edited here.")
    }
    if (occurrence.status != "pending") {
        throw TaskOccurrenceUpdateValidationException("Completed tasks cannot be edited.")
    }
}

class TaskOccurrenceUpdateValidationException(message: String) : IllegalArgumentException(message)

/** Build the existing RPC's sparse scheduling object, retaining explicit nulls for field clearing. */
internal fun PendingMutation.schedulingPayload(): JsonObject = buildJsonObject {
    if (updateScheduleType) put("schedule_type", requireNotNull(scheduleType))
    if (updatePriority) put("priority", requireNotNull(priority))
    if (updateDeadlineAt) putNullable("deadline_at", deadlineAt)
    if (updateEarliestStart) putNullable("earliest_start", earliestStart)
    if (updateLatestEnd) putNullable("latest_end", latestEnd)
    if (updatePreferredStart) putNullable("preferred_start", preferredStart)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
    if (value == null) put(key, JsonNull) else put(key, value)
}

@Serializable
internal data class UpdateTaskParams(
    @SerialName("p_operation_id") val operationId: String,
    @SerialName("p_task_id") val taskId: String,
    @SerialName("p_title") val title: String,
    @SerialName("p_update_title") val updateTitle: Boolean,
    @SerialName("p_notes") val notes: String?,
    @SerialName("p_update_notes") val updateNotes: Boolean,
    @SerialName("p_scheduled_for") val scheduledFor: String?,
    @SerialName("p_update_schedule") val updateSchedule: Boolean,
    @SerialName("p_all_day") val allDay: Boolean,
    @SerialName("p_reminder_at") val reminderAt: String?,
    @SerialName("p_update_reminder") val updateReminder: Boolean,
    @SerialName("p_archived") val archived: Boolean,
    @SerialName("p_update_archived") val updateArchived: Boolean,
    @SerialName("p_duration_minutes") val durationMinutes: Int?,
    @SerialName("p_update_duration") val updateDuration: Boolean,
    @SerialName("p_scheduling") val scheduling: JsonObject,
)

internal fun PendingMutation.toUpdateTaskParams() = UpdateTaskParams(
    operationId = operationId,
    taskId = requireNotNull(targetTaskId),
    title = title.orEmpty(),
    updateTitle = updateTitle,
    notes = notes,
    updateNotes = updateNotes,
    scheduledFor = scheduledFor,
    updateSchedule = updateSchedule,
    allDay = allDay ?: false,
    reminderAt = null,
    updateReminder = false,
    archived = false,
    updateArchived = false,
    durationMinutes = durationMinutes,
    updateDuration = updateDuration,
    scheduling = schedulingPayload(),
)
