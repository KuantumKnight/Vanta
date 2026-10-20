package dev.system.app.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.system.app.data.CachedOccurrence
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class WidgetFeedTest {
    @Test
    fun groupsPendingItemsByKolkataDateAndKeepsAnytimeTasksVisible() {
        val rows = listOf(
            occurrence("overdue", "Overdue", "2026-09-24T18:30:00Z"),
            occurrence("today", "Today", "2026-09-25T18:30:00Z"),
            occurrence("tomorrow", "Tomorrow", "2026-09-26T18:30:00Z"),
            occurrence("anytime", "Anytime", null),
            occurrence("bad-date", "Bad date", "not-a-date"),
            occurrence("done", "Completed", "2026-09-26T18:30:00Z", status = "completed"),
        )

        val feed = buildWidgetFeed(rows, LocalDate.parse("2026-09-26"))

        assertEquals(listOf("overdue", "today"), feed.todayAndOverdue.map { it.occurrence.id })
        assertEquals(listOf("anytime", "bad-date"), feed.anytime.map { it.id })
        assertEquals(listOf("tomorrow"), feed.upcoming.map { it.occurrence.id })
    }

    private fun occurrence(
        id: String,
        title: String,
        scheduledFor: String?,
        status: String = "pending",
    ) = CachedOccurrence(
        id = id,
        userId = "user-a",
        taskId = "task-$id",
        title = title,
        notes = null,
        scheduledFor = scheduledFor,
        allDay = true,
        kind = "task",
        status = status,
        completedAt = null,
    )
}
