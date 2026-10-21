package dev.system.app.data

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemRepositoryResponseTest {
    @Test
    fun occurrenceHistoryRoundTripPreservesTaskKind() {
        val occurrence = CachedOccurrence(
            id = "occ-1",
            taskId = "task-1",
            title = "Walk",
            notes = null,
            scheduledFor = "2026-10-21T03:30:00Z",
            allDay = false,
            kind = "habit",
            status = "pending",
            completedAt = null,
            userId = "user-1",
        )

        val restored = OccurrenceHistory.from(occurrence, ZoneId.of("UTC")).toCachedOccurrence()

        assertEquals("habit", restored.kind)
        assertEquals("task-1", restored.taskId)
        assertEquals("Walk", restored.title)
    }
}
