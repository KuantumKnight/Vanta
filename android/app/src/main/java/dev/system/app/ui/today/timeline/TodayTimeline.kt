package dev.system.app.ui.today.timeline

import android.app.TimePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.system.app.data.CachedOccurrence
import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.ScheduleSource
import dev.system.app.ui.components.EmptyState
import dev.system.app.ui.components.SectionHeading
import dev.system.app.ui.components.TaskCard
import dev.system.app.ui.components.taskDate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlinx.coroutines.delay

private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Vanta's first day timeline. Dashboard integration is kept at the screen boundary:
 * callers provide dated Today/overdue rows and undated rows separately.
 */
@Composable
internal fun TodayTimeline(
    modifier: Modifier,
    rows: List<CachedOccurrence>,
    unscheduledRows: List<CachedOccurrence>,
    today: LocalDate,
    emptyTitle: String,
    emptyBody: String,
    onComplete: (CachedOccurrence) -> Unit,
    onReschedule: (CachedOccurrence, Instant) -> Unit = { _, _ -> },
    onStartFocus: (CachedOccurrence) -> Unit = {},
    snapIntervalMinutes: Int = DEFAULT_TIME_SLOT_MINUTES,
    planningStart: LocalTime = DEFAULT_PLANNING_START,
    planningEnd: LocalTime = DEFAULT_PLANNING_END,
    calendarBlocks: List<ScheduleBlock> = emptyList(),
    plannerContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    require(snapIntervalMinutes in 1..(24 * 60)) { "Snap interval must be between one minute and one day." }
    val sourceRows = remember(rows, unscheduledRows) {
        (rows + unscheduledRows).distinctBy(CachedOccurrence::id)
    }
    val rowsById = remember(sourceRows) { sourceRows.associateBy(CachedOccurrence::id) }
    val timelineTasks = remember(sourceRows) { sourceRows.map(CachedOccurrence::toTimelineTask) }
    val now = rememberMinuteClock()
    val dayCalendarBlocks = remember(calendarBlocks, today) {
        val dayStart = today.atStartOfDay(VANTA_TIME_ZONE).toInstant()
        val dayEnd = today.plusDays(1).atStartOfDay(VANTA_TIME_ZONE).toInstant()
        calendarBlocks.mapNotNull { block ->
            if (block.start >= dayEnd || block.end <= dayStart) null
            else block.copy(start = maxOf(block.start, dayStart), end = minOf(block.end, dayEnd))
        }
    }
    val displayEntries = remember(dayCalendarBlocks, timelineTasks, today, now, planningStart, planningEnd) {
        val taskEntries = calculateDayTimeline(
            timelineTasks,
            today,
            now,
            planningStart = planningStart,
            planningEnd = planningEnd,
            externalBlocks = dayCalendarBlocks,
        ).scheduled.map { TimelineDisplayEntry(it.start.toInstant(), task = it) }
        val calendarEntries = dayCalendarBlocks.map { TimelineDisplayEntry(it.start, calendar = it) }
        (taskEntries + calendarEntries).sortedWith(compareBy<TimelineDisplayEntry> { it.start }.thenBy { it.key })
    }
    val plan = remember(timelineTasks, today, now, planningStart, planningEnd, dayCalendarBlocks) {
        calculateDayTimeline(
            timelineTasks,
            today,
            now,
            planningStart = planningStart,
            planningEnd = planningEnd,
            externalBlocks = dayCalendarBlocks,
        )
    }
    val overdue = remember(rows, today) {
        rows.filter { it.taskDate()?.let { date -> date < today } == true }
    }
    val fitIntoToday = remember(plan.unscheduled, unscheduledRows, rowsById) {
        (plan.unscheduled.mapNotNull { rowsById[it.id] } + unscheduledRows).distinctBy(CachedOccurrence::id)
    }
    val nowMarkerIndex = displayEntries.count { it.start <= now }
    val isToday = now.atZone(VANTA_TIME_ZONE).toLocalDate() == today

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        // Keep the last timeline row scrollable clear of Dashboard's bottom-right FAB.
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "timeline-summary") {
            TimelineSummary(plan, planningStart, planningEnd, dayCalendarBlocks.size)
        }

        plannerContent?.let { content ->
            item(key = "planner-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
            }
        }

        if (overdue.isNotEmpty()) {
            item(key = "overdue-heading") {
                SectionHeading("OVERDUE", "${overdue.size} waiting")
            }
            items(overdue, key = { "overdue-${it.id}" }) { row ->
                TaskCard(row = row, onComplete = onComplete)
            }
        }

        item(key = "schedule-heading") {
            SectionHeading("TODAY TIMELINE", "${displayEntries.size} blocks")
        }

        if (displayEntries.isEmpty()) {
            if (isToday) {
                item(key = "now-marker-empty") { NowMarker(now) }
            }
            if (rows.isEmpty() && unscheduledRows.isEmpty()) {
                item(key = "timeline-empty") {
                    EmptyState(
                        modifier = Modifier.fillMaxWidth(),
                        title = emptyTitle,
                        body = emptyBody,
                    )
                }
            }
        } else {
            displayEntries.forEachIndexed { index, entry ->
                if (isToday && index == nowMarkerIndex) {
                    item(key = "now-marker") { NowMarker(now) }
                }
                val taskBlock = entry.task
                if (taskBlock != null) {
                    val source = rowsById[taskBlock.task.id]
                    if (source != null) item(key = "timeline-${taskBlock.task.id}") {
                        TimelineEventRow(
                            block = taskBlock,
                            source = source,
                            isNext = taskBlock.task.id == plan.nextTaskId,
                            scheduled = plan.scheduled,
                            calendarBlocks = dayCalendarBlocks,
                            onComplete = onComplete,
                            onReschedule = onReschedule,
                            onStartFocus = onStartFocus,
                            snapIntervalMinutes = snapIntervalMinutes,
                        )
                    }
                } else {
                    val calendar = checkNotNull(entry.calendar)
                    item(key = "calendar-${calendar.id}") { CalendarTimelineRow(calendar) }
                }
            }
            if (isToday && nowMarkerIndex == displayEntries.size) {
                item(key = "now-marker-end") { NowMarker(now) }
            }
        }

        if (fitIntoToday.isNotEmpty()) {
            item(key = "unscheduled-heading") {
                SectionHeading("FIT INTO TODAY", "${fitIntoToday.size} anytime")
            }
            items(fitIntoToday, key = { "fit-${it.id}" }) { row ->
                TaskCard(row = row, onComplete = onComplete)
            }
        }
    }
}

@Composable
private fun TimelineSummary(plan: DayTimeline, planningStart: LocalTime, planningEnd: LocalTime, calendarBlockCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "${plan.scheduledCount} tasks · $calendarBlockCount calendar · ${formatMinutes(plan.plannedMinutes.toLong())} planned",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${formatMinutes(plan.freeMinutes)} free between ${ClockFormatter.format(planningStart)}–${ClockFormatter.format(planningEnd)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TimelineEventRow(
    block: TimelineBlock,
    source: CachedOccurrence,
    isNext: Boolean,
    scheduled: List<TimelineBlock>,
    calendarBlocks: List<ScheduleBlock>,
    onComplete: (CachedOccurrence) -> Unit,
    onReschedule: (CachedOccurrence, Instant) -> Unit,
    onStartFocus: (CachedOccurrence) -> Unit,
    snapIntervalMinutes: Int,
) {
    val originalStart = remember(source.id, block.start) {
        source.scheduledFor?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
            ?: block.start.toInstant()
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val latestOnReschedule = rememberUpdatedState(onReschedule)
    val canReschedule = source.status == "pending" && !source.allDay && source.scheduleType == "SOFT"
    var dragPreview by remember(source.id, originalStart, snapIntervalMinutes) { mutableStateOf<Instant?>(null) }
    var committedPreview by remember(source.id, originalStart, snapIntervalMinutes) { mutableStateOf<Instant?>(null) }
    var dragOffsetYPx by remember(source.id, originalStart, snapIntervalMinutes) { mutableFloatStateOf(0f) }
    val visibleStart = dragPreview ?: committedPreview ?: originalStart
    val visibleStartZoned = visibleStart.atZone(VANTA_TIME_ZONE)
    val visibleEnd = source.durationMinutes
        ?.takeIf { it in MIN_TASK_DURATION_MINUTES..MAX_TASK_DURATION_MINUTES }
        ?.let { visibleStartZoned.plusMinutes(it.toLong()) }
    val overlaps = remember(scheduled, calendarBlocks, visibleStart, source.durationMinutes) {
        overlappingTaskTitles(source.id, visibleStart, source.durationMinutes, scheduled, calendarBlocks)
    }

    LaunchedEffect(committedPreview) {
        if (committedPreview != null) {
            delay(COMMITTED_PREVIEW_TIMEOUT_MILLIS)
            committedPreview = null
        }
    }

    val dragGesture = if (canReschedule) {
        Modifier.pointerInput(source.id, originalStart, snapIntervalMinutes, density.density) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    dragOffsetYPx = 0f
                    dragPreview = originalStart
                },
                onDragEnd = {
                    val destination = dragPreview
                    val movementDp = with(density) { abs(dragOffsetYPx).toDp().value }
                    dragOffsetYPx = 0f
                    dragPreview = null
                    if (destination != null && movementDp >= MINIMUM_DRAG_COMMIT_DP && destination != originalStart) {
                        committedPreview = destination
                        latestOnReschedule.value(source, destination)
                    }
                },
                onDragCancel = {
                    dragOffsetYPx = 0f
                    dragPreview = null
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    dragOffsetYPx += dragAmount.y
                    val movementDp = with(density) { dragOffsetYPx.toDp().value }
                    dragPreview = previewTimeForVerticalDrag(
                        originalStart,
                        movementDp,
                        intervalMinutes = snapIntervalMinutes,
                    )
                },
            )
        }
    } else {
        Modifier
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .graphicsLayer { translationY = if (dragPreview != null) dragOffsetYPx else 0f }
            .zIndex(if (dragPreview != null) 1f else 0f),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.width(54.dp).padding(top = 12.dp)) {
            Text(
                ClockFormatter.format(visibleStartZoned),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        TimelineRail(modifier = Modifier.width(16.dp).fillMaxHeight())
        Spacer(Modifier.width(8.dp))
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = if (block.isCurrent) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (dragPreview != null) 8.dp else 1.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        block.task.title,
                        modifier = dragGesture.semantics {
                            if (canReschedule) {
                                contentDescription = "${block.task.title}. Long press and drag to reschedule in $snapIntervalMinutes minute steps."
                            }
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    val details = buildString {
                        append(ClockFormatter.format(visibleStartZoned))
                        visibleEnd?.let { append('–').append(ClockFormatter.format(it)) }
                        source.durationMinutes?.takeIf { it in MIN_TASK_DURATION_MINUTES..MAX_TASK_DURATION_MINUTES }
                            ?.let { append(" · ${it}m") }
                    }
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    when {
                        dragPreview != null -> TimelineTag(
                            "MOVING TO ${ClockFormatter.format(visibleStartZoned)}",
                            isEmphasis = true,
                        )
                        block.isCurrent -> TimelineTag("IN PROGRESS", isEmphasis = true)
                        isNext -> TimelineTag("NEXT", isEmphasis = false)
                    }
                    if (overlaps.isNotEmpty()) {
                        TimelineTag("OVERLAPS WITH ${overlapSummary(overlaps)}", isWarning = true)
                    } else if (dragPreview == null && committedPreview == null && block.overlapsAnotherTask) {
                        TimelineTag("OVERLAPS ANOTHER TASK", isWarning = true)
                    }
                    Text(
                        if (block.task.completed) "Completed" else "Pending",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (canReschedule) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = {
                                    TimePickerDialog(
                                        context,
                                        { _, hour, minute ->
                                            val chosen = visibleStartZoned.toLocalDate().atTime(hour, minute)
                                                .atZone(VANTA_TIME_ZONE).toInstant()
                                            val snapped = snapToTimeSlot(chosen, intervalMinutes = snapIntervalMinutes)
                                            if (snapped != originalStart) {
                                                committedPreview = snapped
                                                latestOnReschedule.value(source, snapped)
                                            }
                                        },
                                        visibleStartZoned.hour,
                                        visibleStartZoned.minute,
                                        true,
                                    ).show()
                                },
                            ) {
                                Text("Reschedule")
                            }
                            TextButton(onClick = { onStartFocus(source) }) {
                                Text("Start focus")
                            }
                        }
                    }
                }
                Checkbox(
                    checked = block.task.completed,
                    onCheckedChange = { checked -> if (checked) onComplete(source) },
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = "Mark ${block.task.title} complete"
                    },
                )
            }
        }
    }
}

@Composable
private fun CalendarTimelineRow(block: ScheduleBlock) {
    val zone = VANTA_TIME_ZONE
    val start = block.start.atZone(zone)
    val end = block.end.atZone(zone)
    val time = if (block.allDay) "ALL DAY" else "${ClockFormatter.format(start)}–${ClockFormatter.format(end)}"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("$time · CALENDAR · HARD · READ ONLY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(block.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

private data class TimelineDisplayEntry(
    val start: Instant,
    val task: TimelineBlock? = null,
    val calendar: ScheduleBlock? = null,
) {
    val key: String get() = task?.task?.id ?: calendar?.id.orEmpty()
}

@Composable
private fun TimelineRail(modifier: Modifier) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val markerColor = MaterialTheme.colorScheme.primary
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = lineColor,
                start = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f),
                end = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height),
                strokeWidth = 2.dp.toPx(),
            )
        }
        Box(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 15.dp).size(10.dp)
                .background(markerColor, CircleShape),
        )
    }
}

@Composable
private fun NowMarker(now: Instant) {
    val markerColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ClockFormatter.format(now.atZone(VANTA_TIME_ZONE)),
            modifier = Modifier.width(54.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Canvas(modifier = Modifier.size(width = 16.dp, height = 12.dp)) {
            drawLine(
                color = markerColor,
                start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
                end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
                strokeWidth = 2.dp.toPx(),
            )
            drawCircle(
                color = markerColor,
                radius = 4.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
            )
        }
        Text(
            "NOW",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun TimelineTag(label: String, isEmphasis: Boolean = false, isWarning: Boolean = false) {
    val foreground = when {
        isWarning -> MaterialTheme.colorScheme.error
        isEmphasis -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        color = when {
            isWarning -> MaterialTheme.colorScheme.errorContainer
            isEmphasis -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        },
        contentColor = foreground,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(top = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun rememberMinuteClock(): Instant {
    var now by remember { mutableStateOf(Instant.now().truncatedTo(ChronoUnit.MINUTES)) }
    LaunchedEffect(Unit) {
        while (true) {
            val remainder = System.currentTimeMillis() % 60_000L
            delay(if (remainder == 0L) 60_000L else 60_000L - remainder)
            now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        }
    }
    return now
}

private fun formatMinutes(minutes: Long): String {
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours == 0L -> "${remaining}m"
        remaining == 0L -> "${hours}h"
        else -> "${hours}h ${remaining}m"
    }
}

private fun overlapSummary(titles: List<String>): String = when (titles.size) {
    0 -> ""
    1 -> titles.single()
    2 -> "${titles[0]} and ${titles[1]}"
    else -> "${titles[0]}, ${titles[1]} +${titles.size - 2}"
}

private const val MINIMUM_DRAG_COMMIT_DP = 8f
private const val COMMITTED_PREVIEW_TIMEOUT_MILLIS = 3_000L
