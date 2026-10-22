package dev.system.app.ui.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.ColumnScope
import dev.system.app.data.CachedOccurrence
import dev.system.app.planner.ScheduleBlock
import dev.system.app.ui.today.timeline.DEFAULT_TIME_SLOT_MINUTES
import dev.system.app.ui.today.timeline.TodayTimeline
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import dev.system.app.ui.today.timeline.DEFAULT_PLANNING_END
import dev.system.app.ui.today.timeline.DEFAULT_PLANNING_START

@Composable
internal fun TodayScreen(
    modifier: Modifier,
    rows: List<CachedOccurrence>,
    unscheduledRows: List<CachedOccurrence>,
    today: LocalDate,
    emptyTitle: String,
    emptyBody: String,
    onComplete: (CachedOccurrence) -> Unit,
    onOpenTask: (CachedOccurrence) -> Unit = {},
    onReschedule: (CachedOccurrence, Instant) -> Unit = { _, _ -> },
    onStartFocus: (CachedOccurrence) -> Unit = {},
    snapIntervalMinutes: Int = DEFAULT_TIME_SLOT_MINUTES,
    planningStart: LocalTime = DEFAULT_PLANNING_START,
    planningEnd: LocalTime = DEFAULT_PLANNING_END,
    calendarBlocks: List<ScheduleBlock> = emptyList(),
    plannerContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    TodayTimeline(
        modifier = modifier,
        rows = rows,
        unscheduledRows = unscheduledRows,
        today = today,
        emptyTitle = emptyTitle,
        emptyBody = emptyBody,
        onComplete = onComplete,
        onOpenTask = onOpenTask,
        onReschedule = onReschedule,
        onStartFocus = onStartFocus,
        snapIntervalMinutes = snapIntervalMinutes,
        planningStart = planningStart,
        planningEnd = planningEnd,
        calendarBlocks = calendarBlocks,
        plannerContent = plannerContent,
    )
}
