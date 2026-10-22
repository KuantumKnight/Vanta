package dev.system.app.data

import dev.system.app.planner.SchedulePriority
import dev.system.app.planner.ScheduleType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TaskOccurrenceUpdateTest {
    @Test
    fun patchesNullableFieldsAndPreservesUnchangedFields() {
        val current = occurrence().copy(
            notes = "Keep this unless explicitly cleared",
            deadlineAt = "2026-10-25T12:00:00Z",
            earliestStart = "2026-10-23T09:00:00Z",
            latestEnd = "2026-10-23T17:00:00Z",
            preferredStart = "2026-10-23T10:00:00Z",
        )

        val updated = TaskOccurrenceUpdate(
            title = FieldChange.Set("  Updated  "),
            notes = FieldChange.Set<String?>(null),
            deadlineAt = FieldChange.Set<Instant?>(null),
        ).applyTo(current)

        assertEquals("Updated", updated.title)
        assertEquals(null, updated.notes)
        assertEquals(null, updated.deadlineAt)
        assertEquals(current.scheduledFor, updated.scheduledFor)
        assertEquals(current.earliestStart, updated.earliestStart)
        assertEquals(current.latestEnd, updated.latestEnd)
        assertEquals(current.preferredStart, updated.preferredStart)
    }

    @Test
    fun rejectsEditsThatViolateTheCombinedExistingWindowAndDeadline() {
        val invalidWindow = occurrence().copy(
            earliestStart = "2026-10-23T17:00:00Z",
            latestEnd = "2026-10-23T09:00:00Z",
        )
        val invalidDeadline = occurrence().copy(
            earliestStart = "2026-10-23T10:00:00Z",
            deadlineAt = "2026-10-23T09:00:00Z",
        )

        assertFalse(runCatching { TaskOccurrenceUpdate(title = FieldChange.Set("Edited")).applyTo(invalidWindow) }.isSuccess)
        assertFalse(runCatching { TaskOccurrenceUpdate(notes = FieldChange.Set("Edited")).applyTo(invalidDeadline) }.isSuccess)
    }

    @Test
    fun flexibleTaskBecomesSoftWhenScheduledAndRejectsUnsupportedUnscheduledTransition() {
        val flexible = occurrence().copy(scheduledFor = null, allDay = false, scheduleType = ScheduleType.FLEXIBLE.name)
        val scheduleUpdate = TaskOccurrenceUpdate(
            schedule = FieldChange.Set(TaskOccurrenceSchedule(Instant.parse("2026-10-23T10:00:00Z"), allDay = false)),
        )
        val scheduled = scheduleUpdate.applyTo(flexible)
        assertEquals(ScheduleType.SOFT.name, scheduled.scheduleType)
        val mutation = scheduleUpdate.toMutation("schedule-flexible", "user-a", flexible)
        assertTrue(mutation.updateScheduleType)
        assertEquals(ScheduleType.SOFT.name, mutation.scheduleType)
        assertEquals(JsonPrimitive("SOFT"), mutation.schedulingPayload()["schedule_type"])

        val rejected = runCatching {
            TaskOccurrenceUpdate(scheduleType = FieldChange.Set(ScheduleType.FLEXIBLE)).applyTo(occurrence())
        }
        assertTrue(rejected.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun allDayAndPriorityConstraintsAreValidatedTogether() {
        val rejected = runCatching {
            TaskOccurrenceUpdate(
                schedule = FieldChange.Set(TaskOccurrenceSchedule(Instant.parse("2026-10-23T10:00:00Z"), allDay = true)),
                scheduleType = FieldChange.Set(ScheduleType.SOFT),
                priority = FieldChange.Set(SchedulePriority.HIGH),
            ).applyTo(occurrence())
        }

        assertTrue(rejected.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun rejectsLocalCreatedRowsAndEmptyPatches() {
        assertTrue(
            runCatching { TaskOccurrenceUpdate(title = FieldChange.Set("Edited")).applyTo(occurrence("local:pending")) }
                .exceptionOrNull() is TaskOccurrenceUpdateValidationException,
        )
        assertFalse(runCatching { TaskOccurrenceUpdate().applyTo(occurrence()) }.isSuccess)
    }

    @Test
    fun explicitNullSchedulingValueSurvivesJsonSerialization() {
        val mutation = PendingMutation(
            operationId = "update-op",
            userId = "user-a",
            operationType = "update_task",
            targetOccurrenceId = "occurrence-1",
            targetTaskId = "task-1",
            updateNotes = true,
            updateDeadlineAt = true,
        )
        val payload = mutation.schedulingPayload()
        val rpcParams = mutation.toUpdateTaskParams()
        val serialized = Json.encodeToString(rpcParams)

        assertEquals(JsonNull, payload["deadline_at"])
        assertEquals("{\"deadline_at\":null}", Json.encodeToString(JsonElement.serializer(), payload))
        assertEquals("update-op", rpcParams.operationId)
        assertTrue(rpcParams.updateNotes)
        assertTrue(serialized.contains("\"p_operation_id\":\"update-op\""))
        assertTrue(serialized.contains("\"p_update_notes\":true"))
        assertTrue(serialized.contains("\"p_notes\":null"))
        assertTrue(serialized.contains("\"p_scheduling\":{\"deadline_at\":null}"))
    }

    private fun occurrence(id: String = "occurrence-1") = CachedOccurrence(
        id = id,
        taskId = if (id.startsWith("local:")) id else "task-1",
        title = "Task",
        notes = null,
        scheduledFor = "2026-10-23T10:00:00Z",
        allDay = false,
        kind = "task",
        status = "pending",
        completedAt = null,
        userId = "user-a",
        scheduleType = ScheduleType.SOFT.name,
        priority = SchedulePriority.NORMAL.name,
    )
}
