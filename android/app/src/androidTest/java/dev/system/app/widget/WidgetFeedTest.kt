package dev.system.app.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.system.app.data.CachedOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class WidgetFeedTest {
    @Test
    fun groupsOverdueTodayUpcomingAndUnscheduledTasksInKolkataTime() {
        val rows = listOf(
            occurrence("tomorrow", "Tomorrow", "2026-09-25T18:30:00Z"),
            occurrence("today", "Today", "2026-09-24T18:30:00Z"),
            occurrence("overdue", "Overdue", "2026-09-23T18:30:00Z"),
            occurrence("anytime", "Anytime", null),
            occurrence("bad-date", "Bad date", "not-a-date"),
            occurrence("done", "Completed", "2026-09-26T18:30:00Z", status = "completed"),
        )

        val feed = buildWidgetFeed(rows, LocalDate.parse("2026-09-25"))

        assertEquals(listOf("overdue"), feed.overdue.map { it.occurrence.id })
        assertEquals(listOf("today"), feed.today.map { it.occurrence.id })
        assertEquals(listOf("tomorrow"), feed.upcoming.map { it.occurrence.id })
        assertEquals(listOf("anytime", "bad-date"), feed.unscheduled.map { it.id })
        assertTrue(!feed.isEmpty)
    }

    @Test
    fun emptyFeedOnlyIncludesPendingItems() {
        val feed = buildWidgetFeed(
            listOf(occurrence("done", "Completed", "2026-09-25T18:30:00Z", status = "completed")),
            LocalDate.parse("2026-09-25"),
        )

        assertTrue(feed.isEmpty)
    }

    private fun occurrence(
        id: String,
        title: String,
        scheduledFor: String?,
        status: String = "pending",
    ) = CachedOccurrence(
        id = id,
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
