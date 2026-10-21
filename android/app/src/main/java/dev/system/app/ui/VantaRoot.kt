package dev.system.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.system.app.analytics.AnalyticsPeriod
import dev.system.app.analytics.analyticsDateRange
import dev.system.app.briefing.BriefingOccurrence
import dev.system.app.briefing.MorningBriefing
import dev.system.app.briefing.MorningBriefingBuilder
import dev.system.app.data.CachedOccurrence
import dev.system.app.data.GoalProgress
import dev.system.app.data.SystemRepository
import dev.system.app.SystemApplication
import dev.system.app.BuildConfig
import dev.system.app.calendar.CalendarAuthorizationResult
import dev.system.app.calendar.CalendarCatalogEntity
import dev.system.app.calendar.CalendarRange
import dev.system.app.calendar.CalendarSyncStatus
import dev.system.app.calendar.GoogleCalendarAuthorization
import dev.system.app.calendar.GoogleCalendarRepository
import dev.system.app.focus.FocusSession
import dev.system.app.focus.FocusSessionStatus
import dev.system.app.focus.FocusSessionStore
import dev.system.app.habits.HabitDetail
import dev.system.app.habits.HabitSummary
import dev.system.app.habits.HabitTodayOccurrence
import dev.system.app.planner.DaySchedule
import dev.system.app.planner.PlanProposal
import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.ai.AiPlanClient
import dev.system.app.planner.ai.validatePlanProposal
import dev.system.app.planner.ScheduleConflict
import dev.system.app.planner.conflicts.detectFocusOverrun
import dev.system.app.planner.conflicts.validateDayReplanProposal
import dev.system.app.planner.core.buildDaySchedule
import dev.system.app.planner.core.toPlannerFlexibleTask
import dev.system.app.planner.core.toPlanningContext
import dev.system.app.settings.UserPreferences
import dev.system.app.settings.UserPreferencesRepository
import dev.system.app.ui.auth.LoadingScreen
import dev.system.app.ui.auth.SignInScreen
import dev.system.app.ui.components.TaskTimeZone
import dev.system.app.ui.focus.FocusScreen
import dev.system.app.ui.navigation.DashboardScreen
import dev.system.app.ui.navigation.DashboardTab
import dev.system.app.ui.navigation.DashboardTopBar
import dev.system.app.ui.navigation.buildAnalyticsSummary
import dev.system.app.ui.navigation.buildEveningReviewSummary
import dev.system.app.ui.navigation.buildHabitDashboardData
import dev.system.app.ui.planner.week.weekDateRange
import dev.system.app.ui.quickcapture.QuickCaptureLaunchRequest
import dev.system.app.ui.quickcapture.QuickCaptureSubmission
import dev.system.app.ui.calendar.GoogleCalendarSettingsPanel
import dev.system.app.ui.calendar.rememberGoogleCalendarUserAuthorization
import dev.system.app.ui.planner.ai.PlanMyDayUiState
import dev.system.app.ui.theme.VantaTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun dateRange(start: LocalDate, dayCount: Long, zoneId: ZoneId): CalendarRange {
    require(dayCount > 0)
    return CalendarRange(
        start = start.atStartOfDay(zoneId).toInstant(),
        endExclusive = start.plusDays(dayCount).atStartOfDay(zoneId).toInstant(),
        zoneId = zoneId,
    )
}

@Composable
internal fun VantaRoot(
    repository: SystemRepository,
    userPreferencesRepository: UserPreferencesRepository,
    quickCaptureRequest: QuickCaptureLaunchRequest? = null,
    onQuickCaptureRequestConsumed: (Long) -> Unit = {},
    navigationRequest: DashboardTab? = null,
    onNavigationRequestConsumed: () -> Unit = {},
) {
    val authenticated by repository.isAuthenticated.collectAsState(initial = null)
    val accountContext by repository.accountContext.collectAsState()
    val syncState by repository.syncState.collectAsState()
    val completionQueue by repository.completionQueue.collectAsState(initial = emptyList())
    val pending by repository.pendingOccurrences.collectAsState(initial = emptyList())
    val preferences by userPreferencesRepository.preferences.collectAsState(initial = UserPreferences())
    val context = LocalContext.current
    val focusSessionStore = remember(context) { FocusSessionStore(context) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val today = LocalDate.now(TaskTimeZone)
    val calendarRepository = remember(context) {
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            (context.applicationContext as? SystemApplication)?.googleCalendarRepository ?: GoogleCalendarRepository(context)
        }
    }
    val calendarRefreshMutex = remember(calendarRepository) { Mutex() }
    val calendarAuthorization = remember(context) { GoogleCalendarAuthorization(context.applicationContext) }
    var selectedTab by remember { mutableStateOf(DashboardTab.Today) }
    var plannerWeekStart by remember(preferences.weekStartDay) {
        mutableStateOf(weekDateRange(today, preferences.weekStartDay).startInclusive)
    }
    LaunchedEffect(today, preferences.weekStartDay) {
        plannerWeekStart = weekDateRange(today, preferences.weekStartDay).startInclusive
    }
    var calendarRefreshing by remember(accountContext.userId) { mutableStateOf(false) }
    var calendarSettingsError by remember(accountContext.userId) { mutableStateOf<String?>(null) }
    var calendarAuthorizationOwnerId by remember { mutableStateOf<String?>(null) }
    var aiPlanState by remember(accountContext.userId, today) { mutableStateOf<PlanMyDayUiState>(PlanMyDayUiState.Ready) }
    var replanPreview by remember(accountContext.userId, today) { mutableStateOf<PlanProposal?>(null) }
    var focusOverrun by remember(accountContext.userId, today) { mutableStateOf<ScheduleConflict?>(null) }
    var goals by remember { mutableStateOf<List<GoalProgress>>(emptyList()) }
    var goalsLoading by remember { mutableStateOf(false) }
    var goalsError by remember { mutableStateOf<String?>(null) }
    var syncBusy by remember { mutableStateOf(false) }
    var activeFocusSession by remember { mutableStateOf<FocusSession?>(null) }
    var showFocus by remember { mutableStateOf(false) }
    var habits by remember(accountContext.userId) { mutableStateOf<List<HabitSummary>>(emptyList()) }
    var habitDetails by remember(accountContext.userId) { mutableStateOf<Map<String, HabitDetail>>(emptyMap()) }
    var selectedHabitId by remember(accountContext.userId) { mutableStateOf<String?>(null) }
    var morningBriefing by remember(accountContext.userId) { mutableStateOf<MorningBriefing?>(null) }
    var analyticsPeriod by remember { mutableStateOf(AnalyticsPeriod.SEVEN_DAYS) }
    var analyticsSummary by remember(accountContext.userId) { mutableStateOf<dev.system.app.analytics.AnalyticsSummary?>(null) }
    var eveningReviewSummary by remember(accountContext.userId) { mutableStateOf<dev.system.app.eveningreview.EveningReviewSummary?>(null) }
    var dailyReviewNote by remember(today, accountContext.userId) { mutableStateOf("") }
    var dailyReviewFinished by remember(today, accountContext.userId) { mutableStateOf(false) }
    var resolvedReviewOccurrenceIds by remember(today, accountContext.userId) { mutableStateOf<Set<String>>(emptySet()) }
    var notificationPermissionGranted by remember(context) { mutableStateOf(hasNotificationPermission(context)) }
    var notificationSettingsRecommended by remember { mutableStateOf(false) }

    val currentWeekStart = weekDateRange(today, preferences.weekStartDay).startInclusive
    val todayPlannerOccurrences by remember(repository, currentWeekStart) {
        repository.observePlannerWeek(currentWeekStart)
    }.collectAsState(initial = emptyList())
    val plannerWeekOccurrences by remember(repository, plannerWeekStart) {
        repository.observePlannerWeek(plannerWeekStart)
    }.collectAsState(initial = emptyList())

    val disconnectedCalendarStatus = remember(accountContext.userId) {
        CalendarSyncStatus(connected = false, googleAccountKey = null, lastSuccessAt = null, lastErrorCode = null)
    }
    val calendarStatus by remember(calendarRepository, accountContext.userId) {
        accountContext.userId?.let { calendarRepository.value.observeSyncStatus(it) } ?: flowOf(disconnectedCalendarStatus)
    }.collectAsState(initial = disconnectedCalendarStatus)
    val calendarCatalog by remember(calendarRepository, accountContext.userId) {
        accountContext.userId?.let { calendarRepository.value.observeCalendars(it) } ?: flowOf(emptyList<CalendarCatalogEntity>())
    }.collectAsState(initial = emptyList())
    val todayCalendarRange = remember(today) { dateRange(today, 1, TaskTimeZone) }
    val weekCalendarRange = remember(plannerWeekStart) { dateRange(plannerWeekStart, 7, TaskTimeZone) }
    val todayCalendarBlocks by remember(calendarRepository, accountContext.userId, todayCalendarRange) {
        accountContext.userId?.let { calendarRepository.value.observeScheduleBlocks(it, todayCalendarRange) }
            ?: flowOf(emptyList<ScheduleBlock>())
    }.collectAsState(initial = emptyList())
    val plannerWeekCalendarBlocks by remember(calendarRepository, accountContext.userId, weekCalendarRange) {
        accountContext.userId?.let { calendarRepository.value.observeScheduleBlocks(it, weekCalendarRange) }
            ?: flowOf(emptyList<ScheduleBlock>())
    }.collectAsState(initial = emptyList())

    val aiPlanClient = remember(repository) {
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            AiPlanClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY, repository::planningAccessToken)
        }
    }
    DisposableEffect(repository) {
        onDispose { if (aiPlanClient.isInitialized()) aiPlanClient.value.close() }
    }

    val planningStartTime = LocalTime.of(preferences.dayStartMinutes / 60, preferences.dayStartMinutes % 60)
    val planningEndTime = LocalTime.of(preferences.dayEndMinutes / 60, preferences.dayEndMinutes % 60)
    val todaySchedule = remember(todayPlannerOccurrences, todayCalendarBlocks, today, planningStartTime, planningEndTime) {
        buildDaySchedule(
            date = today,
            zoneId = TaskTimeZone,
            planningStartTime = planningStartTime,
            planningEndTime = planningEndTime,
            occurrences = todayPlannerOccurrences,
            calendarBlocks = todayCalendarBlocks,
        )
    }
    val todayPlanningContext = remember(todaySchedule) { todaySchedule.toPlanningContext() }
    val plannerWeekRange = remember(plannerWeekStart, preferences.weekStartDay) {
        weekDateRange(plannerWeekStart, preferences.weekStartDay)
    }
    val plannerSchedulesByDate = remember(
        plannerWeekRange,
        plannerWeekOccurrences,
        plannerWeekCalendarBlocks,
        planningStartTime,
        planningEndTime,
        today,
    ) {
        plannerWeekRange.dates().associateWith { date ->
            buildDaySchedule(
                date = date,
                zoneId = TaskTimeZone,
                planningStartTime = planningStartTime,
                planningEndTime = planningEndTime,
                occurrences = plannerWeekOccurrences,
                calendarBlocks = plannerWeekCalendarBlocks,
                includeUnboundedFlexibleTasks = false,
            )
        }
    }
    val plannerFlexibleTasks = remember(plannerWeekOccurrences) {
        plannerWeekOccurrences.mapNotNull(CachedOccurrence::toPlannerFlexibleTask)
            .distinctBy { it.occurrenceId }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationPermissionGranted = granted
        notificationSettingsRecommended = !granted
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationPermissionGranted = hasNotificationPermission(context)
        if (notificationPermissionGranted) notificationSettingsRecommended = false
    }

    val onEnableNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (notificationSettingsRecommended) {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                })
            } else {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    suspend fun performGoogleCalendarRefresh(ownerUserId: String, accessToken: String) {
        if (ownerUserId != accountContext.userId) return
        calendarRefreshing = true
        calendarSettingsError = null
        try {
            val result = calendarRepository.value.refresh(ownerUserId, accessToken, TaskTimeZone)
            snackbarHost.showSnackbar("Calendar updated · ${result.eventsCached} events cached")
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            calendarSettingsError = "Calendar could not refresh. Vanta tasks remain available."
            snackbarHost.showSnackbar(calendarSettingsError!!)
        } finally {
            calendarRefreshing = false
        }
    }

    suspend fun refreshGoogleCalendar(ownerUserId: String, accessToken: String) {
        calendarRefreshMutex.withLock { performGoogleCalendarRefresh(ownerUserId, accessToken) }
    }

    val calendarUserAuthorization = rememberGoogleCalendarUserAuthorization(
        onAccessToken = { token ->
            val owner = calendarAuthorizationOwnerId
            if (owner != null && owner == accountContext.userId) {
                scope.launch { refreshGoogleCalendar(owner, token) }
            } else {
                calendarSettingsError = "Your account changed before Calendar connected. Try again."
            }
        },
        onCancelled = { calendarSettingsError = "Google Calendar connection was cancelled." },
        onError = { message ->
            calendarSettingsError = message
            scope.launch { snackbarHost.showSnackbar(message) }
        },
    )

    fun requestCalendarAuthorization(chooseAccount: Boolean) {
        val owner = accountContext.userId ?: return
        calendarAuthorizationOwnerId = owner
        calendarSettingsError = null
        calendarUserAuthorization(chooseAccount)
    }

    suspend fun refreshStaleCalendarWithoutPrompt(ownerUserId: String) {
        calendarRefreshMutex.withLock {
            if (!calendarStatus.connected || !calendarStatus.isStale(System.currentTimeMillis())) return@withLock
            if (calendarRepository.value.isCacheFresh(ownerUserId)) return@withLock
            val result = try {
                calendarAuthorization.requestAccessToken(chooseGoogleAccount = false)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withLock
            }
            when (result) {
                is CalendarAuthorizationResult.Authorized -> performGoogleCalendarRefresh(ownerUserId, result.accessToken)
                is CalendarAuthorizationResult.NeedsUserInteraction -> calendarRepository.value.markAuthorizationRequired(ownerUserId)
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val owner = accountContext.userId
        if (authenticated == true && owner != null) {
            scope.launch { refreshStaleCalendarWithoutPrompt(owner) }
        }
    }

    LaunchedEffect(authenticated, accountContext.userId, calendarStatus.connected, calendarStatus.lastSuccessAt) {
        val owner = accountContext.userId
        if (authenticated == true && owner != null && calendarStatus.connected &&
            calendarStatus.isStale(System.currentTimeMillis())
        ) {
            refreshStaleCalendarWithoutPrompt(owner)
        }
    }

    suspend fun refreshGoals() {
        goalsLoading = true
        goalsError = null
        runCatching { repository.loadGoals() }
            .onSuccess { goals = it }
            .onFailure { goalsError = it.message ?: "Could not load goals." }
        goalsLoading = false
    }

    fun requestDayPlan(planningContext: dev.system.app.planner.PlanningContext) {
        aiPlanState = PlanMyDayUiState.Requesting
        scope.launch {
            runCatching { aiPlanClient.value.propose(planningContext) }
                .onSuccess { aiPlanState = PlanMyDayUiState.Preview(it) }
                .onFailure { failure ->
                    aiPlanState = PlanMyDayUiState.Unavailable(
                        failure.message ?: "Planning assistant unavailable. Your schedule is still usable.",
                    )
                }
        }
    }

    fun applyDayPlan(planningContext: dev.system.app.planner.PlanningContext, proposal: PlanProposal) {
        scope.launch {
            runCatching {
                val latestRows = repository.plannerWeekSnapshot(currentWeekStart)
                val latestSchedule = buildDaySchedule(
                    date = today,
                    zoneId = TaskTimeZone,
                    planningStartTime = planningStartTime,
                    planningEndTime = planningEndTime,
                    occurrences = latestRows,
                    calendarBlocks = todayCalendarBlocks,
                )
                val latestContext = latestSchedule.toPlanningContext()
                val checked = validatePlanProposal(latestContext, proposal)
                require(checked.issues.isEmpty() && checked.proposal.placements.size == proposal.placements.size) {
                    "The schedule changed after this preview. Request a fresh plan before applying it."
                }
                repository.applyReschedulePlan(
                    checked.proposal,
                    latestContext.planningStart,
                    latestContext.planningEnd,
                )
            }.onSuccess {
                aiPlanState = PlanMyDayUiState.Ready
                snackbarHost.showSnackbar("Plan saved on this phone")
            }.onFailure { failure ->
                aiPlanState = PlanMyDayUiState.Unavailable(failure.message ?: "Could not apply this plan.")
            }
        }
    }

    fun applyReplan(proposal: PlanProposal) {
        scope.launch {
            runCatching {
                val latestRows = repository.plannerWeekSnapshot(currentWeekStart)
                val latestSchedule = buildDaySchedule(
                    date = today,
                    zoneId = TaskTimeZone,
                    planningStartTime = planningStartTime,
                    planningEndTime = planningEndTime,
                    occurrences = latestRows,
                    calendarBlocks = todayCalendarBlocks,
                )
                val now = Instant.now()
                require(validateDayReplanProposal(latestSchedule, proposal, now)) {
                    "The schedule changed after this preview. Review a fresh replan before applying it."
                }
                repository.applyReschedulePlan(proposal, latestSchedule.planningStart, latestSchedule.planningEnd, now)
            }.onSuccess {
                replanPreview = null
                snackbarHost.showSnackbar("Replan saved on this phone")
            }.onFailure { failure ->
                snackbarHost.showSnackbar(failure.message ?: "Could not apply this replan.")
            }
        }
    }

    fun completeOccurrence(row: CachedOccurrence) {
        val userId = accountContext.userId
        val session = activeFocusSession
        val completedSession = if (userId != null && session?.status == FocusSessionStatus.ACTIVE && session.occurrenceId == row.id) {
            runCatching { focusSessionStore.complete(userId) }.getOrNull()
        } else null
        completedSession?.let { finished ->
            val plannedEnd = Instant.ofEpochMilli(finished.startedAtEpochMillis).plusMillis(finished.plannedDurationMillis)
            val actualEnd = finished.finishedAtEpochMillis?.let(Instant::ofEpochMilli)
            if (actualEnd != null) {
                focusOverrun = detectFocusOverrun(todaySchedule, row.id, plannedEnd, actualEnd)
                    ?.takeIf { it.blockIds.size > 1 }
            }
        }
        completedSession?.let { activeFocusSession = it }
        scope.launch {
            completedSession?.let { runCatching { repository.recordFocusSession(it) } }
            runCatching { repository.complete(row.id) }
                .onSuccess {
                    snackbarHost.showSnackbar("Completed")
                    runCatching { goals = repository.loadGoals() }
                }
                .onFailure {
                    snackbarHost.showSnackbar("Saved on this phone. It will sync when you’re back online.")
                }
        }
    }

    fun completeHabit(occurrence: HabitTodayOccurrence) {
        val cached = pending.firstOrNull { it.id == occurrence.occurrenceId }
        if (cached != null) {
            completeOccurrence(cached)
        } else {
            scope.launch {
                runCatching { repository.complete(occurrence.occurrenceId) }
                    .onFailure { snackbarHost.showSnackbar("Could not complete this habit yet.") }
            }
        }
    }

    fun startFocus(row: CachedOccurrence) {
        val userId = accountContext.userId
        if (userId == null) {
            scope.launch { snackbarHost.showSnackbar("Sign in to start a focus session.") }
            return
        }
        val current = focusSessionStore.activeSession(userId)
        if (current != null) {
            activeFocusSession = current
            if (current.occurrenceId == row.id) showFocus = true
            else scope.launch { snackbarHost.showSnackbar("Finish the active focus session before starting another.") }
            return
        }
        runCatching { focusSessionStore.start(userId, row) }
            .onSuccess { session ->
                activeFocusSession = session
                showFocus = true
                scope.launch { runCatching { repository.recordFocusSession(session) } }
            }
            .onFailure {
                scope.launch { snackbarHost.showSnackbar(it.message ?: "Could not start focus.") }
            }
    }

    fun rescheduleReviewOccurrence(row: CachedOccurrence, scheduledFor: Instant) {
        scope.launch {
            runCatching { repository.rescheduleOccurrence(row.id, scheduledFor) }
                .onSuccess {
                    resolvedReviewOccurrenceIds = resolvedReviewOccurrenceIds + row.id
                    snackbarHost.showSnackbar("Schedule updated")
                }
                .onFailure { snackbarHost.showSnackbar(it.message ?: "Could not update the schedule.") }
        }
    }

    LaunchedEffect(authenticated) {
        if (authenticated == true) {
            runCatching { repository.syncNow() }
            refreshGoals()
        } else {
            selectedTab = DashboardTab.Today
            goals = emptyList()
            goalsError = null
            goalsLoading = false
            habits = emptyList()
            habitDetails = emptyMap()
            morningBriefing = null
            analyticsSummary = null
            eveningReviewSummary = null
            selectedHabitId = null
        }
    }

    LaunchedEffect(navigationRequest, authenticated) {
        if (navigationRequest != null && authenticated == true) {
            selectedTab = navigationRequest
            onNavigationRequestConsumed()
        }
    }

    LaunchedEffect(authenticated, accountContext.userId) {
        val userId = accountContext.userId
        if (authenticated == true && userId != null) {
            activeFocusSession = focusSessionStore.activeSession(userId)
            showFocus = activeFocusSession != null
            activeFocusSession?.let { session -> runCatching { repository.recordFocusSession(session) } }
        } else {
            activeFocusSession = null
            showFocus = false
        }
    }

    LaunchedEffect(authenticated, accountContext.userId, syncState.syncing, syncState.lastSyncedAt) {
        val userId = accountContext.userId
        if (authenticated == true && userId != null) {
            // Keep the visible focus session aligned with local-id reconciliation after sync.
            activeFocusSession = focusSessionStore.activeSession(userId)
        }
    }

    LaunchedEffect(selectedTab, syncState.lastSyncedAt) {
        if (authenticated == true && selectedTab == DashboardTab.Goals) refreshGoals()
    }

    LaunchedEffect(authenticated, accountContext.userId, today) {
        if (authenticated != true || accountContext.userId == null) {
            dailyReviewNote = ""
            dailyReviewFinished = false
        } else {
            val entry = runCatching { repository.dailyReviewFor(today) }.getOrNull()
            dailyReviewNote = entry?.note.orEmpty()
            dailyReviewFinished = entry != null
        }
    }

    LaunchedEffect(
        authenticated,
        accountContext.userId,
        selectedTab,
        pending,
        syncState.lastSyncedAt,
        analyticsPeriod,
        preferences.weekStartDay,
        today,
    ) {
        if (authenticated != true || accountContext.userId == null) return@LaunchedEffect
        when (selectedTab) {
            DashboardTab.Today -> {
                val rows = runCatching { repository.occurrencesForDate(today) }.getOrDefault(emptyList())
                morningBriefing = withContext(Dispatchers.Default) {
                    MorningBriefingBuilder.build(
                        date = today,
                        occurrences = rows.map { row ->
                            BriefingOccurrence(
                                taskId = row.taskId,
                                title = row.title,
                                scheduledFor = row.scheduledFor,
                                allDay = row.allDay,
                                kind = row.kind,
                                durationMinutes = row.durationMinutes,
                                priority = row.priority,
                            )
                        },
                    )
                }
            }
            DashboardTab.Habits -> {
                val history = runCatching {
                    repository.occurrenceHistoryForRange(today.minusDays(364), today, kind = "habit")
                }.getOrDefault(emptyList())
                val projection = withContext(Dispatchers.Default) {
                    buildHabitDashboardData(history, today, preferences.weekStartDay)
                }
                habits = projection.summaries
                habitDetails = projection.details
                if (selectedHabitId !in projection.details.keys) selectedHabitId = null
            }
            DashboardTab.Analytics -> {
                val range = analyticsDateRange(today, analyticsPeriod)
                val occurrences = runCatching {
                    repository.occurrenceHistoryForRange(range.startInclusive, range.endInclusive)
                }.getOrDefault(emptyList())
                val focus = runCatching {
                    repository.focusHistoryForRange(range.startInclusive, range.endInclusive)
                }.getOrDefault(emptyList())
                analyticsSummary = withContext(Dispatchers.Default) {
                    buildAnalyticsSummary(today, analyticsPeriod, occurrences, focus)
                }
            }
            DashboardTab.EveningReview -> {
                val occurrences = runCatching { repository.occurrenceHistoryForRange(today, today) }.getOrDefault(emptyList())
                val focus = runCatching { repository.focusHistoryForRange(today, today) }.getOrDefault(emptyList())
                eveningReviewSummary = withContext(Dispatchers.Default) {
                    buildEveningReviewSummary(today, occurrences, focus)
                }
            }
            else -> Unit
        }
    }

    fun signOut() {
        scope.launch {
            runCatching { repository.signOut() }
                .onFailure { snackbarHost.showSnackbar(it.message ?: "Could not sign out.") }
        }
    }

    VantaTheme(preferences.theme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (authenticated) {
                null -> LoadingScreen()
                false -> SignInScreen(repository)
                true -> Scaffold(
                    topBar = {
                        if (!showFocus) {
                            DashboardTopBar(
                                syncing = syncState.syncing || syncBusy,
                                onSync = {
                                    scope.launch {
                                        syncBusy = true
                                        runCatching { repository.syncNow() }
                                            .onFailure { snackbarHost.showSnackbar("Sync failed. Your saved tasks are still on this phone.") }
                                        syncBusy = false
                                    }
                                },
                                onSignOut = ::signOut,
                            )
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbarHost) },
                ) { insets ->
                    Box(modifier = Modifier.padding(insets).fillMaxSize()) {
                        val focused = activeFocusSession
                        if (showFocus && focused?.status == FocusSessionStatus.ACTIVE) {
                            FocusScreen(
                                activeSession = focused,
                                occurrence = pending.firstOrNull { it.id == focused.occurrenceId },
                                onCompleteExistingOccurrence = ::completeOccurrence,
                                onEndEarly = {
                                    accountContext.userId?.let { userId ->
                                        runCatching { focusSessionStore.endEarly(userId) }
                                            .onSuccess { session ->
                                                activeFocusSession = session
                                                scope.launch { runCatching { repository.recordFocusSession(session) } }
                                            }
                                    }
                                    showFocus = false
                                },
                                onExit = { showFocus = false },
                            )
                        } else {
                            DashboardScreen(
                                modifier = Modifier.fillMaxSize(),
                                selectedTab = selectedTab,
                                onTabSelected = { selectedTab = it },
                                pending = pending,
                                completionQueue = completionQueue,
                                syncError = syncState.error,
                                syncing = syncState.syncing || syncBusy,
                                lastSyncedAt = syncState.lastSyncedAt,
                                goals = goals,
                                goalsLoading = goalsLoading,
                                goalsError = goalsError,
                                habits = habits,
                                selectedHabitDetail = selectedHabitId?.let(habitDetails::get),
                                morningBriefing = morningBriefing,
                                analyticsSummary = analyticsSummary,
                                showPlannedVsActual = preferences.showPlannedVsActual,
                                eveningReviewSummary = eveningReviewSummary,
                                dailyReviewNote = dailyReviewNote,
                                dailyReviewFinished = dailyReviewFinished,
                                resolvedReviewOccurrenceIds = resolvedReviewOccurrenceIds,
                                userPreferences = preferences,
                                notificationPermissionGranted = notificationPermissionGranted,
                                notificationSettingsRecommended = notificationSettingsRecommended,
                                onEnableNotifications = onEnableNotifications,
                                onRetrySync = { repository.retryCompletions() },
                                onRetryGoals = { scope.launch { refreshGoals() } },
                                onDismissCompletion = { key -> scope.launch { repository.dismissTerminalCompletion(key) } },
                                onComplete = ::completeOccurrence,
                                onCreateTask = { submission: QuickCaptureSubmission ->
                                    scope.launch {
                                        runCatching {
                                            repository.createTask(
                                                title = submission.title,
                                                notes = submission.notes,
                                                scheduledFor = submission.scheduledFor,
                                                allDay = submission.allDay,
                                                durationMinutes = submission.durationMinutes,
                                                scheduleType = submission.scheduleType,
                                                priority = submission.priority,
                                                deadlineAt = submission.deadlineAt,
                                                preferredStart = submission.preferredStart,
                                            )
                                        }.onSuccess {
                                            snackbarHost.showSnackbar("Saved on this phone")
                                        }.onFailure {
                                            snackbarHost.showSnackbar(it.message ?: "Could not save this task.")
                                        }
                                    }
                                },
                                onReschedule = { row, scheduledFor -> rescheduleReviewOccurrence(row, scheduledFor) },
                                onStartFocus = ::startFocus,
                                onCompleteHabit = ::completeHabit,
                                onOpenHabit = { habit -> selectedHabitId = habit.id },
                                onBackFromHabit = { selectedHabitId = null },
                                onAnalyticsPeriodSelected = { analyticsPeriod = it },
                                onMoveToTomorrow = { row ->
                                    val existingTime = row.scheduledFor?.let { value ->
                                        runCatching { Instant.parse(value).atZone(TaskTimeZone).toLocalTime() }.getOrNull()
                                    }
                                    val time = when {
                                        row.allDay -> LocalTime.MIDNIGHT
                                        existingTime != null -> existingTime
                                        else -> LocalTime.of(9, 0)
                                    }
                                    val target = today.plusDays(1).atTime(time).atZone(TaskTimeZone).toInstant()
                                    rescheduleReviewOccurrence(row, target)
                                },
                                onKeepReviewDeadline = { row ->
                                    resolvedReviewOccurrenceIds = resolvedReviewOccurrenceIds + row.id
                                },
                                onReviewReschedule = ::rescheduleReviewOccurrence,
                                onSkipHabit = { row ->
                                    scope.launch {
                                        runCatching { repository.skipHabitOccurrence(row.id) }
                                            .onSuccess { stored ->
                                                if (stored) {
                                                    resolvedReviewOccurrenceIds = resolvedReviewOccurrenceIds + row.id
                                                } else {
                                                    snackbarHost.showSnackbar("This habit is already updated or unavailable.")
                                                }
                                            }
                                            .onFailure { snackbarHost.showSnackbar(it.message ?: "Could not skip this habit.") }
                                    }
                                },
                                onReviewNoteChange = { dailyReviewNote = it.take(4_000) },
                                onFinishDay = { dateText, note ->
                                    val date = runCatching { LocalDate.parse(dateText) }.getOrDefault(today)
                                    scope.launch {
                                        runCatching { repository.finishDailyReview(date, note) }
                                            .onSuccess {
                                                dailyReviewNote = note
                                                dailyReviewFinished = true
                                                snackbarHost.showSnackbar("Review saved on this phone")
                                            }
                                            .onFailure { snackbarHost.showSnackbar(it.message ?: "Could not save your review.") }
                                    }
                                },
                                onUpdatePreferences = { update ->
                                    scope.launch { userPreferencesRepository.update(update) }
                                },
                                onResetPreferences = {
                                    scope.launch { userPreferencesRepository.reset() }
                                },
                                onSignOutFromSettings = ::signOut,
                                todayCalendarBlocks = todayCalendarBlocks,
                                plannerWeekStart = plannerWeekStart,
                                plannerSchedulesByDate = plannerSchedulesByDate,
                                plannerFlexibleTasks = plannerFlexibleTasks,
                                onPlannerWeekChanged = { plannerWeekStart = it },
                                planContext = todayPlanningContext,
                                planUiState = aiPlanState,
                                focusOverrun = focusOverrun,
                                onDismissFocusOverrun = { focusOverrun = null },
                                onRequestDayPlan = ::requestDayPlan,
                                onApplyDayPlan = ::applyDayPlan,
                                onCancelDayPlan = { aiPlanState = PlanMyDayUiState.Ready },
                                onRequestReplan = { replanPreview = it },
                                pendingReplan = replanPreview,
                                onApplyReplan = ::applyReplan,
                                onDismissReplan = { replanPreview = null },
                                calendarSettings = {
                                    calendarSettingsError?.let { message ->
                                        androidx.compose.material3.Text(message, color = MaterialTheme.colorScheme.error)
                                    }
                                    GoogleCalendarSettingsPanel(
                                        status = calendarStatus,
                                        calendars = calendarCatalog,
                                        isRefreshing = calendarRefreshing,
                                        zoneId = TaskTimeZone,
                                        onConnect = { requestCalendarAuthorization(chooseAccount = true) },
                                        onRefresh = { requestCalendarAuthorization(chooseAccount = false) },
                                        onDisconnect = {
                                            val owner = accountContext.userId
                                            if (owner != null) scope.launch {
                                                calendarRefreshing = true
                                                val revoked = calendarRepository.value.disconnect(owner, calendarAuthorization)
                                                calendarRefreshing = false
                                                if (revoked) {
                                                    calendarSettingsError = null
                                                    snackbarHost.showSnackbar("Google Calendar disconnected")
                                                } else {
                                                    calendarSettingsError = "Calendar disconnected on this phone, but Google access could not be revoked."
                                                }
                                            }
                                        },
                                        onCalendarSelection = { calendarId, selected ->
                                            val owner = accountContext.userId
                                            if (owner != null) scope.launch {
                                                if (calendarRepository.value.setCalendarSelected(owner, calendarId, selected)) {
                                                    requestCalendarAuthorization(chooseAccount = false)
                                                }
                                            }
                                        },
                                    )
                                },
                                quickCaptureRequest = quickCaptureRequest,
                                onQuickCaptureRequestConsumed = onQuickCaptureRequestConsumed,
                            )
                        }
                    }
                }
            }
        }
    }
}
