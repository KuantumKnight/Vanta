package dev.system.app.ui.upcoming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.system.app.data.CachedOccurrence
import dev.system.app.ui.components.EmptyState
import dev.system.app.ui.components.SectionHeading
import dev.system.app.ui.components.TaskCard
import dev.system.app.ui.components.taskDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun UpcomingScreen(
    modifier: Modifier,
    dated: List<CachedOccurrence>,
    unscheduled: List<CachedOccurrence>,
    today: LocalDate,
    searchActive: Boolean,
    onComplete: (CachedOccurrence) -> Unit,
) {
    if (dated.isEmpty() && unscheduled.isEmpty()) {
        EmptyState(
            modifier = modifier,
            title = if (searchActive) "No matching tasks" else "Nothing coming up",
            body = if (searchActive) "Try a different word." else "Tasks and habits you add in ChatGPT will appear here.",
        )
        return
    }
    val grouped = dated.groupBy { it.taskDate() }.toSortedMap(compareBy<LocalDate?> { it })
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grouped.forEach { (date, tasks) ->
            if (date != null) {
                item(key = "heading-" + date.toString()) {
                    val label = if (date == today.plusDays(1)) "TOMORROW"
                    else date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")).uppercase()
                    SectionHeading(label, tasks.size.toString() + " task" + if (tasks.size == 1) "" else "s")
                }
                items(tasks, key = { it.id }) { TaskCard(it, onComplete) }
            }
        }
        if (unscheduled.isNotEmpty()) {
            item(key = "no-date-heading") { SectionHeading("NO DATE", unscheduled.size.toString() + " task" + if (unscheduled.size == 1) "" else "s") }
            items(unscheduled, key = { it.id }) { TaskCard(it, onComplete) }
        }
    }
}
