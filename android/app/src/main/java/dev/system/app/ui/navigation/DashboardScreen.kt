package dev.system.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.system.app.data.CachedOccurrence
import dev.system.app.data.GoalProgress
import dev.system.app.data.PendingCompletion
import dev.system.app.planner.DaySchedule
import dev.system.app.planner.FlexibleTask
import dev.system.app.planner.PlanProposal
import dev.system.app.planner.PlanningContext
import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.ScheduleConflict
import dev.system.app.planner.conflicts.detectScheduleConflicts
import dev.system.app.planner.conflicts.findConflictAlternatives
import dev.system.app.planner.conflicts.proposeRemainingDayReplan
import dev.system.app.ui.planner.ai.PlanMyDayCard
import dev.system.app.ui.planner.ai.PlanMyDayUiState
import dev.system.app.ui.planner.replan.ConflictResolutionCard
import dev.system.app.ui.planner.replan.ReplanPreviewCard
import dev.system.app.ui.planner.week.WeeklyPlannerScreen
import dev.system.app.ui.planner.week.weekDateRange
import dev.system.app.analytics.AnalyticsPeriod
import dev.system.app.analytics.AnalyticsScreen
import dev.system.app.analytics.AnalyticsSummary
import dev.system.app.briefing.MorningBriefing
import dev.system.app.briefing.MorningBriefingCard
import dev.system.app.eveningreview.EveningReviewScreen
import dev.system.app.eveningreview.EveningReviewSummary
import dev.system.app.habits.HabitDetail
import dev.system.app.habits.HabitSummary
import dev.system.app.habits.HabitTodayOccurrence
import dev.system.app.habits.HabitsScreen
import dev.system.app.habits.HabitDetailScreen
import dev.system.app.R
import dev.system.app.ui.components.CompletionQueueCard
import dev.system.app.ui.components.NotificationPermissionCard
import dev.system.app.ui.components.SyncSummary
import dev.system.app.ui.components.TaskTimeZone
import dev.system.app.ui.components.taskDate
import dev.system.app.ui.goals.GoalsScreen
import dev.system.app.ui.quickcapture.QuickCaptureLaunchRequest
import dev.system.app.ui.quickcapture.QuickCaptureSubmission
import dev.system.app.ui.quickcapture.QuickCaptureSheet
import dev.system.app.ui.settings.SettingsScreen
import dev.system.app.settings.UserPreferences
import dev.system.app.ui.today.TodayScreen
import dev.system.app.ui.upcoming.UpcomingScreen
import java.time.LocalDate
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.LocalTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardTopBar(
    syncing: Boolean,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    LocalDate.now(TaskTimeZone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            TextButton(enabled = !syncing, onClick = onSync) { Text(if (syncing) "Syncing…" else "Sync") }
            Box {
                TextButton(onClick = { menuExpanded = true }) { Text("Account") }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Sign out") },
                        onClick = { menuExpanded = false; onSignOut() },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardScreen(
    modifier: Modifier,
    selectedTab: DashboardTab,
    onTabSelected: (DashboardTab) -> Unit,
    pending: List<CachedOccurrence>,
    completionQueue: List<PendingCompletion>,
    todayCalendarBlocks: List<ScheduleBlock> = emptyList(),
    syncError: String?,
    syncing: Boolean,
    lastSyncedAt: String?,
    goals: List<GoalProgress>,
    goalsLoading: Boolean,
    goalsError: String?,
    habits: List<HabitSummary>,
    selectedHabitDetail: HabitDetail?,
    morningBriefing: MorningBriefing?,
    analyticsSummary: AnalyticsSummary?,
    showPlannedVsActual: Boolean,
    eveningReviewSummary: EveningReviewSummary?,
    dailyReviewNote: String,
    dailyReviewFinished: Boolean,
    resolvedReviewOccurrenceIds: Set<String>,
    userPreferences: UserPreferences,
    notificationPermissionGranted: Boolean,
    notificationSettingsRecommended: Boolean,
    onEnableNotifications: () -> Unit,
    onRetrySync: () -> Unit,
    onRetryGoals: () -> Unit,
    onDismissCompletion: (String) -> Unit,
    onComplete: (CachedOccurrence) -> Unit,
    onCreateTask: (QuickCaptureSubmission) -> Unit = {},
    onReschedule: (CachedOccurrence, Instant) -> Unit = { _, _ -> },
    onStartFocus: (CachedOccurrence) -> Unit = {},
    onCompleteHabit: (HabitTodayOccurrence) -> Unit = {},
    onOpenHabit: (HabitSummary) -> Unit = {},
    onBackFromHabit: () -> Unit = {},
    onAnalyticsPeriodSelected: (AnalyticsPeriod) -> Unit = {},
    onMoveToTomorrow: (CachedOccurrence) -> Unit = {},
    onKeepReviewDeadline: (CachedOccurrence) -> Unit = {},
    onReviewReschedule: (CachedOccurrence, Instant) -> Unit = { _, _ -> },
    onSkipHabit: (CachedOccurrence) -> Unit = {},
    onReviewNoteChange: (String) -> Unit = {},
    onFinishDay: (String, String) -> Unit = { _, _ -> },
    onUpdatePreferences: ((UserPreferences) -> UserPreferences) -> Unit = {},
    onResetPreferences: () -> Unit = {},
    onSignOutFromSettings: () -> Unit = {},
    quickCaptureRequest: QuickCaptureLaunchRequest? = null,
    onQuickCaptureRequestConsumed: (Long) -> Unit = {},
    plannerWeekStart: LocalDate = weekDateRange(LocalDate.now(TaskTimeZone), userPreferences.weekStartDay).startInclusive,
    plannerSchedulesByDate: Map<LocalDate, DaySchedule> = emptyMap(),
    plannerFlexibleTasks: List<FlexibleTask> = emptyList(),
    onPlannerWeekChanged: (LocalDate) -> Unit = {},
    onPlannerSelectDay: (LocalDate) -> Unit = {},
    onPlannerInspectBlock: (ScheduleBlock) -> Unit = {},
    onPlannerRescheduleBlock: (ScheduleBlock) -> Unit = {},
    onPlannerScheduleFlexible: (FlexibleTask, LocalDate) -> Unit = { _, _ -> },
    planContext: PlanningContext? = null,
    planUiState: PlanMyDayUiState = PlanMyDayUiState.Ready,
    focusOverrun: ScheduleConflict? = null,
    onDismissFocusOverrun: () -> Unit = {},
    onRequestDayPlan: (PlanningContext) -> Unit = {},
    onApplyDayPlan: (PlanningContext, PlanProposal) -> Unit = { _, _ -> },
    onCancelDayPlan: () -> Unit = {},
    onRequestReplan: (PlanProposal) -> Unit = {},
    pendingReplan: PlanProposal? = null,
    onApplyReplan: (PlanProposal) -> Unit = {},
    onDismissReplan: () -> Unit = {},
    calendarSettings: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var search by remember { mutableStateOf("") }
    val today = LocalDate.now(TaskTimeZone)
    var quickCaptureOpen by remember { mutableStateOf(false) }
    var quickCaptureDate by remember { mutableStateOf(today) }
    var quickCaptureSource by remember { mutableStateOf<String?>(null) }
    var reviewRescheduleRow by remember { mutableStateOf<CachedOccurrence?>(null) }
    var rescheduleInitialDate by remember { mutableStateOf<LocalDate?>(null) }
    var inspectedWeekBlock by remember { mutableStateOf<ScheduleBlock?>(null) }
    var dismissedConflictKeys by remember(today) { mutableStateOf<Set<String>>(emptySet()) }
    var briefingDismissedForToday by remember(today) { mutableStateOf(false) }
    LaunchedEffect(quickCaptureRequest?.token) {
        val request = quickCaptureRequest ?: return@LaunchedEffect
        quickCaptureDate = request.date
        quickCaptureSource = request.source
        quickCaptureOpen = true
        onQuickCaptureRequestConsumed(request.token)
    }
    val normalizedQuery = search.trim()
    val filteredTasks = remember(pending, normalizedQuery) {
        pending.filter { row ->
            normalizedQuery.isBlank() ||
                row.title.contains(normalizedQuery, ignoreCase = true) ||
                row.notes.orEmpty().contains(normalizedQuery, ignoreCase = true)
        }
    }
    val filteredGoals = remember(goals, normalizedQuery) {
        goals.filter {
            normalizedQuery.isBlank() ||
                it.title.contains(normalizedQuery, ignoreCase = true) ||
                it.description.orEmpty().contains(normalizedQuery, ignoreCase = true)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = selectedTab.ordinal) {
            DashboardTab.values().forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    text = { Text(tab.label) },
                )
            }
        }

        if (selectedTab in setOf(DashboardTab.Today, DashboardTab.Upcoming, DashboardTab.Goals)) OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            label = { Text(if (selectedTab == DashboardTab.Goals) "Search goals" else "Search tasks") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            trailingIcon = {
                if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("Clear") }
            },
        )

        if (selectedTab in setOf(DashboardTab.Today, DashboardTab.Upcoming, DashboardTab.Goals)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                SyncSummary(syncing, syncError, lastSyncedAt, onRetrySync)
                if (!notificationPermissionGranted) {
                    NotificationPermissionCard(notificationSettingsRecommended, onEnableNotifications)
                }
                if (completionQueue.isNotEmpty()) CompletionQueueCard(
                    completionQueue,
                    onRetrySync,
                    onDismiss = onDismissCompletion,
                )
            }
        }

        when (selectedTab) {
            DashboardTab.Today -> {
                val todayRows = filteredTasks.filter { row ->
                    val date = row.taskDate()
                    date != null && date <= today
                }
                val unscheduledRows = filteredTasks.filter { row -> row.taskDate() == null }
                Column(modifier = Modifier.weight(1f)) {
                    morningBriefing?.takeUnless { briefingDismissedForToday }?.let { briefing ->
                        MorningBriefingCard(
                            briefing = briefing,
                            onViewToday = { briefingDismissedForToday = true },
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    TodayScreen(
                        modifier = Modifier.weight(1f),
                        rows = todayRows,
                        unscheduledRows = unscheduledRows,
                        today = today,
                        emptyTitle = if (search.isBlank()) "Nothing due today" else "No matching tasks",
                        emptyBody = if (search.isBlank()) "Add a task in ChatGPT and it will show up here." else "Try a different word.",
                        onComplete = onComplete,
                        onReschedule = onReschedule,
                        onStartFocus = onStartFocus,
                        snapIntervalMinutes = userPreferences.snapIntervalMinutes,
                        planningStart = LocalTime.of(userPreferences.dayStartMinutes / 60, userPreferences.dayStartMinutes % 60),
                        planningEnd = LocalTime.of(userPreferences.dayEndMinutes / 60, userPreferences.dayEndMinutes % 60),
                        calendarBlocks = todayCalendarBlocks,
                        plannerContent = {
                            planContext?.let { context ->
                                val schedule = DaySchedule(
                                    date = context.date,
                                    zoneId = context.zoneId,
                                    planningStart = context.planningStart,
                                    planningEnd = context.planningEnd,
                                    blocks = context.hardBlocks + context.calendarCommitments + context.softBlocks,
                                    flexibleTasks = context.flexibleTasks,
                                    completedOccurrenceIds = context.completedOccurrenceIds,
                                )
                                val conflictNow = Instant.now()
                                val conflicts = remember(context, conflictNow) { detectScheduleConflicts(schedule, conflictNow) }
                                val primaryConflict = conflicts.firstOrNull { it.type.name != "DEADLINE_RISK" }
                                    ?: conflicts.firstOrNull()
                                val primaryConflictKey = primaryConflict?.let { conflict ->
                                    "${conflict.type}:${conflict.blockIds.sorted().joinToString(",")}:${conflict.start}:${conflict.end}"
                                }
                                if (primaryConflict != null &&
                                    (primaryConflictKey == null || primaryConflictKey !in dismissedConflictKeys)
                                ) {
                                    val movableBlock = primaryConflict.blockIds.asSequence()
                                        .mapNotNull { id -> schedule.blocks.firstOrNull { it.id == id } }
                                        .firstOrNull { it.scheduleType.name == "SOFT" && it.editable && it.start >= conflictNow }
                                    val alternatives = movableBlock?.let { findConflictAlternatives(schedule, it.id, conflictNow) }.orEmpty()
                                    val taskRow = movableBlock?.let { block -> pending.firstOrNull { it.id == block.id } }
                                    ConflictResolutionCard(
                                        conflict = primaryConflict,
                                        alternatives = alternatives,
                                        zoneId = context.zoneId,
                                        onSelectAlternative = { slot -> taskRow?.let { onReschedule(it, slot.start) } },
                                        onKeepSchedule = {
                                            primaryConflictKey?.let { key -> dismissedConflictKeys = dismissedConflictKeys + key }
                                        },
                                        onChooseAnotherTime = { taskRow?.let { reviewRescheduleRow = it } },
                                        canChooseAnotherTime = taskRow != null,
                                    )
                                    if (conflicts.size > 1) {
                                        Text("${conflicts.size} planning issues need review.", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                focusOverrun?.let { overrun ->
                                    AlertDialog(
                                        onDismissRequest = onDismissFocusOverrun,
                                        title = { Text("Focus session ran late") },
                                        text = { Text(overrun.message) },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                onRequestReplan(proposeRemainingDayReplan(schedule, Instant.now()))
                                                onDismissFocusOverrun()
                                            }) { Text("Replan afternoon") }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = onDismissFocusOverrun) { Text("Keep schedule") }
                                        },
                                    )
                                }
                                if (pendingReplan != null) {
                                    ReplanPreviewCard(
                                        proposal = pendingReplan,
                                        taskTitles = pending.associate { it.id to it.title },
                                        zoneId = context.zoneId,
                                        onApply = onApplyReplan,
                                        onAdjust = onDismissReplan,
                                        onKeepSchedule = onDismissReplan,
                                    )
                                } else if (schedule.blocks.any { it.scheduleType.name == "SOFT" && it.editable } || schedule.flexibleTasks.isNotEmpty()) {
                                    TextButton(onClick = { onRequestReplan(proposeRemainingDayReplan(schedule, Instant.now())) }) {
                                        Text("Replan rest of day")
                                    }
                                }
                                PlanMyDayCard(
                                    context = context,
                                    state = planUiState,
                                    freeMinutes = dev.system.app.planner.core.findFreeIntervals(
                                        context.planningStart,
                                        context.planningEnd,
                                        context.hardBlocks + context.calendarCommitments + context.softBlocks,
                                    ).sumOf { it.durationMinutes },
                                    onRequestPlan = { onRequestDayPlan(context) },
                                    onApplyPlan = { onApplyDayPlan(context, it) },
                                    onAdjust = { onCancelDayPlan() },
                                    onCancel = onCancelDayPlan,
                                )
                            }
                        },
                    )
                }
            }
            DashboardTab.Week -> {
                WeeklyPlannerScreen(
                    modifier = Modifier.weight(1f),
                    weekStart = plannerWeekStart,
                    weekStartDay = userPreferences.weekStartDay,
                    today = today,
                    schedulesByDate = plannerSchedulesByDate,
                    flexibleTasks = plannerFlexibleTasks,
                    onWeekChanged = onPlannerWeekChanged,
                    onSelectDay = { date ->
                        quickCaptureDate = date
                        onPlannerSelectDay(date)
                    },
                    onInspectBlock = { block ->
                        onPlannerInspectBlock(block)
                        inspectedWeekBlock = block
                    },
                    onReschedule = { block ->
                        onPlannerRescheduleBlock(block)
                        val row = pending.firstOrNull { it.id == block.id }
                        if (row != null) {
                            rescheduleInitialDate = block.start.atZone(TaskTimeZone).toLocalDate()
                            reviewRescheduleRow = row
                        }
                    },
                    onScheduleFlexibleTask = { task, date ->
                        val row = pending.firstOrNull { it.id == task.occurrenceId }
                        if (row != null) {
                            rescheduleInitialDate = date
                            reviewRescheduleRow = row
                        }
                    },
                    onQuickCapture = { date ->
                        quickCaptureDate = date
                        quickCaptureSource = "weekly_planner"
                        quickCaptureOpen = true
                    },
                )
            }
            DashboardTab.Upcoming -> {
                val upcomingRows = filteredTasks.filter { row -> row.taskDate()?.let { it > today } == true }
                val unscheduledRows = filteredTasks.filter { it.taskDate() == null }
                UpcomingScreen(
                    modifier = Modifier.weight(1f),
                    dated = upcomingRows,
                    unscheduled = unscheduledRows,
                    today = today,
                    searchActive = search.isNotBlank(),
                    onComplete = onComplete,
                )
            }
            DashboardTab.Goals -> {
                GoalsScreen(
                    modifier = Modifier.weight(1f),
                    goals = filteredGoals,
                    loading = goalsLoading,
                    error = goalsError,
                    searchActive = search.isNotBlank(),
                    onRetry = onRetryGoals,
                )
            }
            DashboardTab.Habits -> {
                if (selectedHabitDetail == null) {
                    HabitsScreen(
                        modifier = Modifier.weight(1f),
                        habits = habits,
                        onComplete = onCompleteHabit,
                        onOpenHabit = onOpenHabit,
                    )
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        TextButton(onClick = onBackFromHabit, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
                            Text("← Habits")
                        }
                        val todayOccurrence = habits.firstOrNull { it.id == selectedHabitDetail.id }
                            ?.todayOccurrences?.firstOrNull { it.localDate == today }
                        HabitDetailScreen(
                            detail = selectedHabitDetail,
                            todayOccurrence = todayOccurrence,
                            onComplete = onCompleteHabit,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            DashboardTab.Analytics -> {
                analyticsSummary?.let { summary ->
                    AnalyticsScreen(
                        summary = summary,
                        onPeriodSelected = onAnalyticsPeriodSelected,
                        showPlannedVsActual = showPlannedVsActual,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            DashboardTab.EveningReview -> {
                eveningReviewSummary?.let { summary ->
                    EveningReviewScreen(
                        summary = summary,
                        note = dailyReviewNote,
                        reviewFinished = dailyReviewFinished,
                        resolvedOccurrenceIds = resolvedReviewOccurrenceIds,
                        onMoveToTomorrow = onMoveToTomorrow,
                        onKeepDeadline = onKeepReviewDeadline,
                        onReschedule = { reviewRescheduleRow = it },
                        onSkip = onSkipHabit,
                        onNoteChange = onReviewNoteChange,
                        onFinishDay = onFinishDay,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            DashboardTab.Settings -> {
                SettingsScreen(
                    preferences = userPreferences,
                    onUpdate = onUpdatePreferences,
                    onReset = onResetPreferences,
                    onSignOut = onSignOutFromSettings,
                    calendarSettings = calendarSettings,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        }

        if (selectedTab in setOf(DashboardTab.Today, DashboardTab.Week, DashboardTab.Upcoming, DashboardTab.Goals)) {
            FloatingActionButton(
                onClick = {
                    if (selectedTab != DashboardTab.Week) quickCaptureDate = today
                    quickCaptureSource = null
                    quickCaptureOpen = true
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
                    .semantics { contentDescription = "Create a task" },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text("＋", style = MaterialTheme.typography.headlineSmall)
            }
        }
    }

    if (quickCaptureOpen) {
        QuickCaptureSheet(
            initialDate = quickCaptureDate,
            source = quickCaptureSource,
            onDismiss = { quickCaptureOpen = false },
            onCreateTask = onCreateTask,
            defaultDurationMinutes = userPreferences.defaultDurationMinutes,
        )
    }

    val rescheduling = reviewRescheduleRow
    if (rescheduling != null) {
        val initialTime = remember(rescheduling.id) {
            rescheduling.scheduledFor?.let { value ->
                runCatching { Instant.parse(value).atZone(TaskTimeZone).toLocalTime() }.getOrNull()
            } ?: LocalTime.of(9, 0)
        }
        val initialDateMillis = remember(rescheduling.id) {
            val date = rescheduling.scheduledFor?.let { value ->
                runCatching { Instant.parse(value).atZone(TaskTimeZone).toLocalDate() }.getOrNull()
            } ?: rescheduleInitialDate ?: today
            date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = initialDateMillis)
        DatePickerDialog(
            onDismissRequest = { reviewRescheduleRow = null },
            confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        val target = date.atTime(initialTime).atZone(TaskTimeZone).toInstant()
                        onReviewReschedule(rescheduling, target)
                    }
                    reviewRescheduleRow = null
                    rescheduleInitialDate = null
                }) { Text("Reschedule") }
            },
            dismissButton = { TextButton(onClick = { reviewRescheduleRow = null; rescheduleInitialDate = null }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    inspectedWeekBlock?.let { block ->
        AlertDialog(
            onDismissRequest = { inspectedWeekBlock = null },
            title = { Text(block.title) },
            text = {
                Text(
                    if (block.allDay) "All day · ${block.scheduleType.name} · ${block.source.name.replace('_', ' ')}"
                    else "${DateTimeFormatter.ofPattern("EEE, MMM d", java.util.Locale.getDefault()).format(block.start.atZone(TaskTimeZone))} · " +
                        "${DateTimeFormatter.ofPattern("HH:mm").format(block.start.atZone(TaskTimeZone))}–" +
                        "${DateTimeFormatter.ofPattern("HH:mm").format(block.end.atZone(TaskTimeZone))} · " +
                        "${block.scheduleType.name} · ${block.source.name.replace('_', ' ')}",
                )
            },
            confirmButton = { TextButton(onClick = { inspectedWeekBlock = null }) { Text("Close") } },
        )
    }
}
