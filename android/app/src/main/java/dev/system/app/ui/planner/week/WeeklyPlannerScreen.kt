package dev.system.app.ui.planner.week

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.system.app.planner.DaySchedule
import dev.system.app.planner.FlexibleTask
import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.ScheduleSource
import dev.system.app.planner.ScheduleType
import dev.system.app.planner.core.findFreeIntervals
import java.time.Duration
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Bounded seven-day planning surface. Data is supplied by the caller for the selected week; this
 * composable never subscribes to Room or creates a minute-by-minute time grid.
 */
@Composable
internal fun WeeklyPlannerScreen(
    modifier: Modifier = Modifier,
    weekStart: LocalDate,
    weekStartDay: DayOfWeek = DayOfWeek.MONDAY,
    today: LocalDate,
    schedulesByDate: Map<LocalDate, DaySchedule>,
    flexibleTasks: List<FlexibleTask>,
    onWeekChanged: (LocalDate) -> Unit,
    onSelectDay: (LocalDate) -> Unit,
    onInspectBlock: (ScheduleBlock) -> Unit,
    onReschedule: (ScheduleBlock) -> Unit,
    onScheduleFlexibleTask: (FlexibleTask, LocalDate) -> Unit,
    onQuickCapture: (LocalDate) -> Unit,
) {
    val range = remember(weekStart, weekStartDay) { weekDateRange(weekStart, weekStartDay) }
    val weekDates = remember(range.startInclusive) { range.dates() }
    var selectedDay by remember(range.startInclusive) {
        mutableStateOf(if (today in range) today else range.startInclusive)
    }
    LaunchedEffect(range.startInclusive) {
        if (selectedDay !in range) selectedDay = range.startInclusive
    }
    val weekSchedules = weekDates.mapNotNull(schedulesByDate::get)
    val weekPlannedMinutes = weekSchedules.sumOf(::sumPlannedMinutes)
    val weekCalendarMinutes = weekSchedules.sumOf(::sumCalendarMinutes)
    val weekFlexibleTasks = remember(range.startInclusive, schedulesByDate, flexibleTasks) {
        (flexibleTasks + weekDates.flatMap { schedulesByDate[it]?.flexibleTasks.orEmpty() })
            .distinctBy(FlexibleTask::occurrenceId)
    }

    Column(modifier = modifier.fillMaxSize()) {
        WeekNavigationHeader(
            range = range,
            isCurrentWeek = today in range,
            plannedMinutes = weekPlannedMinutes,
            calendarMinutes = weekCalendarMinutes,
            flexibleCount = weekFlexibleTasks.size,
            onPrevious = { onWeekChanged(shiftWeek(range, -1).startInclusive) },
            onCurrent = { onWeekChanged(weekDateRange(today, weekStartDay).startInclusive) },
            onNext = { onWeekChanged(shiftWeek(range, 1).startInclusive) },
        )
        WeekDayStrip(
            range = range,
            today = today,
            selectedDay = selectedDay,
            schedulesByDate = schedulesByDate,
            onSelectDay = { date ->
                selectedDay = date
                onSelectDay(date)
            },
        )

        val daySchedule = schedulesByDate[selectedDay]
        val sortedBlocks = daySchedule?.blocks.orEmpty()
            .sortedWith(compareBy<ScheduleBlock> { it.start }.thenBy { it.id })

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "selected-day-heading-${selectedDay}") {
                SelectedDaySummary(
                    date = selectedDay,
                    schedule = daySchedule,
                    onQuickCapture = { onQuickCapture(selectedDay) },
                )
            }

            if (sortedBlocks.isEmpty()) {
                item(key = "empty-day-${selectedDay}") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("No scheduled blocks", fontWeight = FontWeight.SemiBold)
                            Text(
                                "This day is open. Add a task or schedule something from the flexible list.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            } else {
                item(key = "day-blocks-heading-${selectedDay}") {
                    Text("SCHEDULE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                items(sortedBlocks, key = { it.id }) { block ->
                    val completed = block.id in daySchedule?.completedOccurrenceIds.orEmpty()
                    ScheduleBlockCard(
                        block = block,
                        zone = daySchedule?.zoneId,
                        completed = completed,
                        onInspect = { onInspectBlock(block) },
                        onReschedule = { onReschedule(block) },
                    )
                }
            }

            val freeMinutes = daySchedule?.let(::freeMinutesFor) ?: 0L
            item(key = "free-time-${selectedDay}") {
                FreeTimeCard(freeMinutes = freeMinutes)
            }

            if (weekFlexibleTasks.isNotEmpty()) {
                item(key = "flexible-tray-heading-${range.startInclusive}") {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text("TO SCHEDULE THIS WEEK", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Flexible tasks stay unscheduled until you choose a time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                items(weekFlexibleTasks, key = { "flex-${it.occurrenceId}" }) { task ->
                    FlexibleTaskCard(
                        task = task,
                        onSchedule = { onScheduleFlexibleTask(task, selectedDay) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekNavigationHeader(
    range: WeekDateRange,
    isCurrentWeek: Boolean,
    plannedMinutes: Long,
    calendarMinutes: Long,
    flexibleCount: Int,
    onPrevious: () -> Unit,
    onCurrent: () -> Unit,
    onNext: () -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val lastDate = range.endExclusive.minusDays(1)
    val sameMonth = range.startInclusive.month == lastDate.month && range.startInclusive.year == lastDate.year
    val label = if (sameMonth) {
        "${range.startInclusive.format(DateTimeFormatter.ofPattern("MMM d", locale))}–${lastDate.format(DateTimeFormatter.ofPattern("d, yyyy", locale))}"
    } else {
        "${range.startInclusive.format(DateTimeFormatter.ofPattern("MMM d", locale))}–${lastDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))}"
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPrevious, modifier = Modifier.semantics { contentDescription = "Previous week" }) {
                Text("‹")
            }
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onNext, modifier = Modifier.semantics { contentDescription = "Next week" }) {
                Text("›")
            }
        }
        Text(
            "WEEK TOTAL · ${formatHours(plannedMinutes)} planned · ${formatHours(calendarMinutes)} calendar · $flexibleCount flexible",
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isCurrentWeek) {
            TextButton(onClick = onCurrent, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("This week")
            }
        }
    }
}

@Composable
private fun WeekDayStrip(
    range: WeekDateRange,
    today: LocalDate,
    selectedDay: LocalDate,
    schedulesByDate: Map<LocalDate, DaySchedule>,
    onSelectDay: (LocalDate) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(range.dates(), key = LocalDate::toString) { date ->
            val schedule = schedulesByDate[date]
            val workMinutes = schedule?.let(::sumPlannedMinutes) ?: 0L
            val calendarMinutes = schedule?.let(::sumCalendarMinutes) ?: 0L
            DaySummaryCard(
                date = date,
                today = today,
                selected = date == selectedDay,
                plannedMinutes = workMinutes,
                commitmentMinutes = calendarMinutes,
                flexibleCount = schedule?.flexibleTasks?.distinctBy(FlexibleTask::occurrenceId)?.size ?: 0,
                onClick = { onSelectDay(date) },
            )
        }
    }
}

@Composable
private fun DaySummaryCard(
    date: LocalDate,
    today: LocalDate,
    selected: Boolean,
    plannedMinutes: Long,
    commitmentMinutes: Long,
    flexibleCount: Int,
    onClick: () -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.width(116.dp).height(128.dp).clickable(onClick = onClick),
        border = if (selected) BorderStroke(2.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.primaryContainer else colors.surface,
        ),
    ) {
        Column(Modifier.fillMaxSize().padding(11.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    date.format(DateTimeFormatter.ofPattern("EEE", locale)).uppercase(locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (date == today) {
                        Spacer(Modifier.width(5.dp))
                        Text("TODAY", style = MaterialTheme.typography.labelSmall, color = colors.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${formatHours(plannedMinutes)} planned", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (commitmentMinutes > 0) {
                    Text("${formatHours(commitmentMinutes)} busy", style = MaterialTheme.typography.labelSmall, color = colors.tertiary)
                } else if (flexibleCount > 0) {
                    Text("$flexibleCount to plan", style = MaterialTheme.typography.labelSmall, color = colors.secondary)
                } else {
                    Text(if (plannedMinutes == 0L) "Free" else "", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SelectedDaySummary(
    date: LocalDate,
    schedule: DaySchedule?,
    onQuickCapture: () -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val plannedMinutes = schedule?.let(::sumPlannedMinutes) ?: 0L
    val calendarMinutes = schedule?.let(::sumCalendarMinutes) ?: 0L
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    date.format(DateTimeFormatter.ofPattern("EEEE", locale)),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", locale)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onQuickCapture) { Text("＋ Add") }
        }
        Text(
            "${formatHours(plannedMinutes)} planned · ${formatHours(calendarMinutes)} calendar commitments · ${schedule?.flexibleTasks?.size ?: 0} flexible",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ScheduleBlockCard(
    block: ScheduleBlock,
    zone: java.time.ZoneId?,
    completed: Boolean,
    onInspect: () -> Unit,
    onReschedule: () -> Unit,
) {
    val isCalendar = block.source == ScheduleSource.GOOGLE_CALENDAR
    val canReschedule = block.editable && !isCalendar && !completed
    val colors = MaterialTheme.colorScheme
    val background = when {
        completed -> colors.secondaryContainer
        isCalendar -> MaterialTheme.colorScheme.tertiaryContainer
        block.scheduleType == ScheduleType.HARD -> colors.errorContainer
        block.scheduleType == ScheduleType.SOFT -> colors.surfaceVariant
        else -> colors.secondaryContainer
    }
    val foreground = when {
        completed -> colors.onSecondaryContainer
        isCalendar -> colors.onTertiaryContainer
        block.scheduleType == ScheduleType.HARD -> colors.onErrorContainer
        block.scheduleType == ScheduleType.SOFT -> colors.onSurfaceVariant
        else -> colors.onSecondaryContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onInspect),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = background),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatBlockTime(block, zone),
                    modifier = Modifier.width(96.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = foreground,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        block.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = foreground,
                        textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None,
                    )
                    val label = if (completed) "COMPLETED" else
                        "${block.source.name.replace('_', ' ')} · ${block.scheduleType.name}"
                    Text(label, style = MaterialTheme.typography.labelSmall, color = foreground)
                }
                if (completed) {
                    Text("DONE", style = MaterialTheme.typography.labelSmall, color = foreground, fontWeight = FontWeight.Bold)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onInspect) { Text("Inspect") }
                if (canReschedule) {
                    OutlinedButton(onClick = onReschedule) { Text("Reschedule") }
                }
            }
        }
    }
}

@Composable
private fun FreeTimeCard(freeMinutes: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("FREE TIME", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(formatHours(freeMinutes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FlexibleTaskCard(task: FlexibleTask, onSchedule: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val duration = if (task.durationMinutes > 0) formatHours(task.durationMinutes.toLong()) else "Duration unset"
                val deadline = task.deadlineAt?.let { " · due ${DateTimeFormatter.ofPattern("EEE, MMM d", locale).format(it.atZone(java.time.ZoneId.systemDefault()))}" }.orEmpty()
                Text(
                    "$duration$deadline",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            OutlinedButton(onClick = onSchedule) { Text("Schedule") }
        }
    }
}

private fun freeMinutesFor(schedule: DaySchedule): Long = findFreeIntervals(
    planningStart = schedule.planningStart,
    planningEnd = schedule.planningEnd,
    blocks = schedule.blocks,
).sumOf { it.durationMinutes }

private fun formatHours(minutes: Long): String {
    val hours = minutes.coerceAtLeast(0) / 60
    val remaining = minutes.coerceAtLeast(0) % 60
    return if (hours == 0L) "${remaining}m" else if (remaining == 0L) "${hours}h" else "${hours}h ${remaining}m"
}

private fun formatBlockTime(block: ScheduleBlock, zone: java.time.ZoneId?): String {
    if (block.allDay) return "ALL DAY"
    val zoneId = zone ?: java.time.ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    return "${formatter.format(block.start.atZone(zoneId))}–${formatter.format(block.end.atZone(zoneId))}"
}

private fun sumPlannedMinutes(schedule: DaySchedule): Long = schedule.blocks
    .asSequence()
    .filter { it.source == ScheduleSource.VANTA_TASK || it.source == ScheduleSource.VANTA_HABIT }
    .sumOf { Duration.between(it.start, it.end).toMinutes().coerceAtLeast(0) }

private fun sumCalendarMinutes(schedule: DaySchedule): Long = schedule.blocks
    .asSequence()
    .filter { it.source == ScheduleSource.GOOGLE_CALENDAR }
    .sumOf { Duration.between(it.start, it.end).toMinutes().coerceAtLeast(0) }
