package dev.system.app.ui.today.timeline

import android.app.TimePickerDialog
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.system.app.data.CachedOccurrence
import dev.system.app.planner.ScheduleType
import dev.system.app.planner.ScheduleBlock
import dev.system.app.ui.components.TaskCard
import dev.system.app.ui.components.VantaCard
import dev.system.app.ui.components.VantaCardTone
import dev.system.app.ui.components.VantaChip
import dev.system.app.ui.components.VantaEmptyState
import dev.system.app.ui.components.VantaSectionHeader
import dev.system.app.ui.components.taskDate
import dev.system.app.ui.theme.LocalVantaColors
import dev.system.app.ui.theme.VantaElevation
import dev.system.app.ui.theme.VantaIconSize
import dev.system.app.ui.theme.VantaLayout
import dev.system.app.ui.theme.VantaMotion
import dev.system.app.ui.theme.VantaSpacing
import dev.system.app.ui.theme.VantaTimeline
import dev.system.app.ui.theme.vantaMotionDuration
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
    // The schedule projection only changes when tasks, the day, or planning inputs change.
    // Current/next labels are derived separately below so the minute clock does not repeat
    // overlap detection and free-time aggregation.
    val basePlan = remember(timelineTasks, today, planningStart, planningEnd, dayCalendarBlocks) {
        calculateDayTimeline(
            timelineTasks,
            today,
            today.atStartOfDay(VANTA_TIME_ZONE).toInstant(),
            planningStart = planningStart,
            planningEnd = planningEnd,
            externalBlocks = dayCalendarBlocks,
        )
    }
    val currentTaskId = remember(basePlan.scheduled, now) {
        basePlan.scheduled.firstOrNull { block ->
            val end = block.end?.toInstant()
            end != null && block.start.toInstant() <= now && now < end
        }?.task?.id
    }
    val nextTaskId = remember(basePlan.scheduled, now) {
        basePlan.scheduled.firstOrNull { it.start.toInstant() > now }?.task?.id
    }
    val displayEntries = remember(dayCalendarBlocks, basePlan.scheduled) {
        val taskEntries = basePlan.scheduled.map { TimelineDisplayEntry(it.start.toInstant(), task = it) }
        val calendarEntries = dayCalendarBlocks.map { TimelineDisplayEntry(it.start, calendar = it) }
        (taskEntries + calendarEntries).sortedWith(compareBy<TimelineDisplayEntry> { it.start }.thenBy { it.key })
    }
    val overdue = remember(rows, today) {
        rows.filter { it.taskDate()?.let { date -> date < today } == true }
    }
    val fitIntoToday = remember(basePlan.unscheduled, unscheduledRows, rowsById) {
        (basePlan.unscheduled.mapNotNull { rowsById[it.id] } + unscheduledRows).distinctBy(CachedOccurrence::id)
    }
    val nowMarkerIndex = displayEntries.count { it.start <= now }
    val isToday = now.atZone(VANTA_TIME_ZONE).toLocalDate() == today

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        // Keep the last timeline row scrollable clear of Dashboard's bottom-right FAB.
        contentPadding = PaddingValues(
            start = VantaLayout.screenHorizontalPadding,
            end = VantaLayout.screenHorizontalPadding,
            bottom = VantaIconSize.touchTarget + VantaSpacing.xxxl,
        ),
        verticalArrangement = Arrangement.spacedBy(VantaTimeline.itemSpacing),
    ) {
        item(key = "timeline-summary") {
            TimelineSummary(
                scheduledCount = basePlan.scheduledCount,
                calendarBlockCount = dayCalendarBlocks.size,
                plannedMinutes = basePlan.plannedMinutes,
                freeMinutes = basePlan.freeMinutes,
                planningStart = planningStart,
                planningEnd = planningEnd,
            )
        }

        plannerContent?.let { content ->
            item(key = "planner-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(VantaSpacing.sm)) { content() }
            }
        }

        if (overdue.isNotEmpty()) {
            item(key = "overdue-heading") {
                VantaSectionHeader("Overdue", "${overdue.size} waiting")
            }
            items(overdue, key = { "overdue-${it.id}" }) { row ->
                TaskCard(row = row, onComplete = onComplete)
            }
        }

        item(key = "schedule-heading") {
            VantaSectionHeader("Timeline", "${displayEntries.size} ${if (displayEntries.size == 1) "item" else "items"}")
        }

        if (displayEntries.isEmpty()) {
            if (isToday) {
                item(key = "now-marker-empty") { NowMarker(now) }
            }
            if (rows.isEmpty() && unscheduledRows.isEmpty()) {
                item(key = "timeline-empty") {
                    VantaEmptyState(
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
                            isCurrent = taskBlock.task.id == currentTaskId,
                            isNext = taskBlock.task.id == nextTaskId,
                            scheduled = basePlan.scheduled,
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
                VantaSectionHeader("Anytime", "${fitIntoToday.size} ${if (fitIntoToday.size == 1) "task" else "tasks"}")
            }
            items(fitIntoToday, key = { "fit-${it.id}" }) { row ->
                TaskCard(row = row, onComplete = onComplete)
            }
        }
    }
}

@Composable
private fun TimelineSummary(
    scheduledCount: Int,
    calendarBlockCount: Int,
    plannedMinutes: Int,
    freeMinutes: Long,
    planningStart: LocalTime,
    planningEnd: LocalTime,
) {
    VantaCard(modifier = Modifier.fillMaxWidth(), tone = VantaCardTone.Subtle) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = VantaSpacing.md, vertical = VantaSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(VantaSpacing.xxs),
        ) {
            Text(
                buildString {
                    append("$scheduledCount ${if (scheduledCount == 1) "task" else "tasks"}")
                    if (calendarBlockCount > 0) append(" · $calendarBlockCount calendar")
                    append(" · ${formatMinutes(plannedMinutes.toLong())} planned")
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${formatMinutes(freeMinutes)} free between ${ClockFormatter.format(planningStart)}–${ClockFormatter.format(planningEnd)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimelineEventRow(
    block: TimelineBlock,
    source: CachedOccurrence,
    isCurrent: Boolean,
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
    val haptic = LocalHapticFeedback.current
    val latestOnReschedule = rememberUpdatedState(onReschedule)
    val scheduleType = remember(source.scheduleType) {
        runCatching { ScheduleType.valueOf(source.scheduleType) }.getOrDefault(ScheduleType.FLEXIBLE)
    }
    val canReschedule = source.status == "pending" && !source.allDay && scheduleType == ScheduleType.SOFT
    var dragPreview by remember(source.id, originalStart, snapIntervalMinutes) { mutableStateOf<Instant?>(null) }
    var committedPreview by remember(source.id, originalStart, snapIntervalMinutes) { mutableStateOf<Instant?>(null) }
    var dragOffsetYPx by remember(source.id, originalStart, snapIntervalMinutes) { mutableFloatStateOf(0f) }
    val dragScale by animateFloatAsState(
        targetValue = if (dragPreview != null) 1.01f else 1f,
        animationSpec = tween(durationMillis = vantaMotionDuration(VantaMotion.fastMillis)),
        label = "timeline-drag-scale",
    )
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
        Modifier.pointerInput(source.id, originalStart, snapIntervalMinutes, density.density, haptic) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    dragOffsetYPx = 0f
                    dragPreview = originalStart
                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                },
                onDragEnd = {
                    val destination = dragPreview
                    val movementDp = with(density) { abs(dragOffsetYPx).toDp().value }
                    dragOffsetYPx = 0f
                    dragPreview = null
                    if (destination != null && movementDp >= MINIMUM_DRAG_COMMIT_DP && destination != originalStart) {
                        committedPreview = destination
                        latestOnReschedule.value(source, destination)
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    } else if (destination != null && destination != originalStart) {
                        haptic.performHapticFeedback(HapticFeedbackType.Reject)
                    }
                },
                onDragCancel = {
                    dragOffsetYPx = 0f
                    dragPreview = null
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    dragOffsetYPx += dragAmount.y
                    val movementDp = with(density) { dragOffsetYPx.toDp().value }
                    val preview = previewTimeForVerticalDrag(
                        originalStart,
                        movementDp,
                        intervalMinutes = snapIntervalMinutes,
                    )
                    if (preview != dragPreview) {
                        if (preview != null && preview != originalStart) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        dragPreview = preview
                    }
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
        Column(modifier = Modifier.width(VantaTimeline.timeColumnWidth).padding(top = VantaSpacing.sm)) {
            Text(
                ClockFormatter.format(visibleStartZoned),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        TimelineRail(modifier = Modifier.width(VantaTimeline.railWidth).fillMaxHeight())
        Spacer(Modifier.width(VantaSpacing.xs))
        val semanticColors = LocalVantaColors.current
        val blockColors = when (scheduleType) {
            ScheduleType.HARD -> semanticColors.timelineHard to semanticColors.onTimelineHard
            ScheduleType.SOFT -> semanticColors.timelineSoft to semanticColors.onTimelineSoft
            ScheduleType.FLEXIBLE -> semanticColors.timelineFlexible to semanticColors.onTimelineFlexible
        }
        val scheduleLabel = when (scheduleType) {
            ScheduleType.HARD -> "Fixed"
            ScheduleType.SOFT -> "Planned"
            ScheduleType.FLEXIBLE -> "Anytime"
        }
        Card(
            modifier = Modifier.weight(1f).then(dragGesture),
            colors = CardDefaults.cardColors(
                containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else blockColors.first,
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (dragPreview != null) VantaElevation.raised else VantaElevation.flat,
            ),
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .graphicsLayer { scaleX = dragScale; scaleY = dragScale }
                    .padding(start = VantaSpacing.sm, end = VantaSpacing.xs, top = VantaSpacing.sm, bottom = VantaSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        block.task.title,
                        modifier = Modifier.semantics {
                            if (canReschedule) {
                                contentDescription = "${block.task.title}. Long press and drag to move in $snapIntervalMinutes minute steps, or use Set start time."
                                dragPreview?.let { preview ->
                                    stateDescription = "Moving to ${ClockFormatter.format(preview.atZone(VANTA_TIME_ZONE))}"
                                }
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
                        modifier = Modifier.padding(top = VantaSpacing.xxs),
                    )
                    Row(
                        modifier = Modifier.padding(top = VantaSpacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(VantaSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VantaChip(
                            label = scheduleLabel,
                            containerColor = blockColors.first,
                            contentColor = blockColors.second,
                        )
                        when {
                            dragPreview != null -> TimelineTag(
                                "Moving to ${ClockFormatter.format(visibleStartZoned)}",
                                isEmphasis = true,
                            )
                            isCurrent -> TimelineTag("In progress", isEmphasis = true)
                            isNext -> TimelineTag("Next", isEmphasis = false)
                        }
                    }
                    if (overlaps.isNotEmpty()) {
                        TimelineTag("Overlaps ${overlapSummary(overlaps)}", isWarning = true)
                    } else if (dragPreview == null && committedPreview == null && block.overlapsAnotherTask) {
                        TimelineTag("Overlaps another task", isWarning = true)
                    }
                    if (block.task.completed) {
                        TimelineTag("Completed", isSuccess = true)
                    }
                    if (canReschedule) {
                        Row(horizontalArrangement = Arrangement.spacedBy(VantaSpacing.xxs)) {
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
                                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                            }
                                        },
                                        visibleStartZoned.hour,
                                        visibleStartZoned.minute,
                                        true,
                                    ).show()
                                },
                            ) {
                                Text("Set start time")
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
    val time = if (block.allDay) "All day" else "${ClockFormatter.format(start)}–${ClockFormatter.format(end)}"
    val semanticColors = LocalVantaColors.current
    VantaCard(modifier = Modifier.fillMaxWidth(), tone = VantaCardTone.Subtle) {
        Column(modifier = Modifier.fillMaxWidth().padding(VantaSpacing.md)) {
            VantaChip(
                label = "Calendar · $time",
                containerColor = semanticColors.calendarExternal,
                contentColor = semanticColors.onCalendarExternal,
            )
            Text(
                block.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = VantaSpacing.xs),
            )
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
            modifier = Modifier.align(Alignment.TopCenter).padding(top = VantaSpacing.md).size(VantaTimeline.markerSize)
                .background(markerColor, CircleShape),
        )
    }
}

@Composable
private fun NowMarker(now: Instant) {
    val markerColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = VantaSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ClockFormatter.format(now.atZone(VANTA_TIME_ZONE)),
            modifier = Modifier.width(VantaTimeline.timeColumnWidth),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Canvas(modifier = Modifier.size(width = VantaTimeline.railWidth, height = VantaSpacing.sm)) {
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
            "Now",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = VantaSpacing.xs),
        )
    }
}

@Composable
private fun TimelineTag(
    label: String,
    isEmphasis: Boolean = false,
    isWarning: Boolean = false,
    isSuccess: Boolean = false,
) {
    val foreground = when {
        isWarning -> LocalVantaColors.current.onWarningContainer
        isSuccess -> LocalVantaColors.current.onSuccessContainer
        isEmphasis -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        color = when {
            isWarning -> LocalVantaColors.current.warningContainer
            isSuccess -> LocalVantaColors.current.successContainer
            isEmphasis -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        },
        contentColor = foreground,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(top = VantaSpacing.xs),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = VantaSpacing.xs, vertical = VantaSpacing.xxs),
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
