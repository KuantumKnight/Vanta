package dev.system.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemRepositoryResponseTest {
    @Test
    fun occurrenceProjectionIncludesEveryRequiredTaskField() {
        val projectedTaskFields = OCCURRENCE_SELECT.substringAfter("task:tasks!inner(").substringBefore(")")
        assertTrue(projectedTaskFields.split(",").containsAll(listOf("title", "notes", "kind", "archived_at")))

        val response = Json.decodeFromString<OccurrenceResponse>(
            """{"id":"occ-1","task_id":"task-1","scheduled_for":null,"all_day":false,"status":"pending","completed_at":null,"task":{"title":"Walk","notes":null,"kind":"habit","archived_at":null}}""",
        )
        assertEquals("habit", response.task.kind)
    }
}
