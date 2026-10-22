package dev.system.app.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.Modifier
import dev.system.app.data.CachedOccurrence
import dev.system.app.planner.DaySchedule
import dev.system.app.planner.FlexibleTask
import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.ScheduleSource
import dev.system.app.planner.ScheduleType
import dev.system.app.settings.UserPreferences
import dev.system.app.ui.components.TaskTimeZone
import dev.system.app.ui.quickcapture.QuickCaptureLaunchRequest
import dev.system.app.ui.quickcapture.QuickCaptureSheet
import dev.system.app.ui.quickcapture.QuickCaptureSubmission
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DashboardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun todaySearchFiltersTasksAndShowsEmptyState() {
        val selectedTab = mutableStateOf(DashboardTab.Today)
        composeRule.setContent {
            MaterialTheme {
                DashboardScreen(
                    modifier = Modifier,
                    selectedTab = selectedTab.value,
                    onTabSelected = { selectedTab.value = it },
                    pending = listOf(todayTask()),
                    completionQueue = emptyList(),
                    syncError = null,
                    syncing = false,
                    lastSyncedAt = null,
                    goals = emptyList(),
                    goalsLoading = false,
                    goalsError = null,
                    habits = emptyList(),
                    selectedHabitDetail = null,
                    morningBriefing = null,
                    analyticsSummary = null,
                    showPlannedVsActual = false,
                    eveningReviewSummary = null,
                    dailyReviewNote = "",
                    dailyReviewFinished = false,
                    resolvedReviewOccurrenceIds = emptySet(),
                    userPreferences = UserPreferences(),
                    notificationPermissionGranted = true,
                    notificationSettingsRecommended = false,
                    onEnableNotifications = {},
                    onRetrySync = {},
                    onRetryGoals = {},
                    onDismissCompletion = {},
                    onComplete = {},
                )
            }
        }

        composeRule.onNodeWithText("Study task").assertIsDisplayed()
        composeRule.onNodeWithText("Search tasks").performTextInput("not found")
        assertTrue(composeRule.onAllNodesWithText("Study task").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("No matching tasks").assertIsDisplayed()
    }

    @Test
    fun taskCompletionStillCallsTheProvidedAction() {
        val selectedTab = mutableStateOf(DashboardTab.Today)
        val completed = AtomicReference<CachedOccurrence?>(null)
        composeRule.setContent {
            MaterialTheme {
                DashboardScreen(
                    modifier = Modifier,
                    selectedTab = selectedTab.value,
                    onTabSelected = { selectedTab.value = it },
                    pending = listOf(todayTask()),
                    completionQueue = emptyList(),
                    syncError = null,
                    syncing = false,
                    lastSyncedAt = null,
                    goals = emptyList(),
                    goalsLoading = false,
                    goalsError = null,
                    habits = emptyList(),
                    selectedHabitDetail = null,
                    morningBriefing = null,
                    analyticsSummary = null,
                    showPlannedVsActual = false,
                    eveningReviewSummary = null,
                    dailyReviewNote = "",
                    dailyReviewFinished = false,
                    resolvedReviewOccurrenceIds = emptySet(),
                    userPreferences = UserPreferences(),
                    notificationPermissionGranted = true,
                    notificationSettingsRecommended = false,
                    onEnableNotifications = {},
                    onRetrySync = {},
                    onRetryGoals = {},
                    onDismissCompletion = {},
                    onComplete = { completed.set(it) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Mark Study task complete").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { completed.get() != null }
        composeRule.runOnIdle { assertEquals("today-occurrence", completed.get()?.id) }
    }

    @Test
    fun todayTaskOpensItsDetailSurface() {
        val anytimeTask = todayTask().copy(scheduledFor = null, allDay = false, scheduleType = "FLEXIBLE")
        composeRule.setContent {
            MaterialTheme {
                DashboardScreen(
                    modifier = Modifier,
                    selectedTab = DashboardTab.Today,
                    onTabSelected = {},
                    pending = listOf(anytimeTask),
                    completionQueue = emptyList(),
                    syncError = null,
                    syncing = false,
                    lastSyncedAt = null,
                    goals = emptyList(),
                    goalsLoading = false,
                    goalsError = null,
                    habits = emptyList(),
                    selectedHabitDetail = null,
                    morningBriefing = null,
                    analyticsSummary = null,
                    showPlannedVsActual = false,
                    eveningReviewSummary = null,
                    dailyReviewNote = "",
                    dailyReviewFinished = false,
                    resolvedReviewOccurrenceIds = emptySet(),
                    userPreferences = UserPreferences(),
                    notificationPermissionGranted = true,
                    notificationSettingsRecommended = false,
                    onEnableNotifications = {},
                    onRetrySync = {},
                    onRetryGoals = {},
                    onDismissCompletion = {},
                    onComplete = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open details for Study task").performClick()
        composeRule.onNodeWithText("Edit").assertIsDisplayed()
        composeRule.onNodeWithText("Start focus").assertIsDisplayed()
        composeRule.onNodeWithText("Complete").assertIsDisplayed()
    }

    @Test
    fun weekTaskBlockOpensDetailInsteadOfCalendarInspect() {
        val row = todayTask()
        val date = LocalDate.now(TaskTimeZone)
        val start = date.atTime(9, 0).atZone(TaskTimeZone).toInstant()
        val schedule = DaySchedule(
            date = date,
            zoneId = TaskTimeZone,
            planningStart = date.atStartOfDay(TaskTimeZone).toInstant(),
            planningEnd = date.plusDays(1).atStartOfDay(TaskTimeZone).toInstant(),
            blocks = listOf(
                ScheduleBlock(
                    id = row.id,
                    source = ScheduleSource.VANTA_TASK,
                    title = row.title,
                    start = start,
                    end = start.plusSeconds(30 * 60),
                    scheduleType = ScheduleType.SOFT,
                    editable = true,
                ),
            ),
            flexibleTasks = emptyList(),
        )
        composeRule.setContent {
            MaterialTheme {
                DashboardScreen(
                    modifier = Modifier,
                    selectedTab = DashboardTab.Week,
                    onTabSelected = {},
                    pending = emptyList(),
                    completionQueue = emptyList(),
                    syncError = null,
                    syncing = false,
                    lastSyncedAt = null,
                    goals = emptyList(),
                    goalsLoading = false,
                    goalsError = null,
                    habits = emptyList(),
                    selectedHabitDetail = null,
                    morningBriefing = null,
                    analyticsSummary = null,
                    showPlannedVsActual = false,
                    eveningReviewSummary = null,
                    dailyReviewNote = "",
                    dailyReviewFinished = false,
                    resolvedReviewOccurrenceIds = emptySet(),
                    userPreferences = UserPreferences(),
                    notificationPermissionGranted = true,
                    notificationSettingsRecommended = false,
                    onEnableNotifications = {},
                    onRetrySync = {},
                    onRetryGoals = {},
                    onDismissCompletion = {},
                    onComplete = {},
                    plannerSchedulesByDate = mapOf(date to schedule),
                    plannerWeekOccurrences = listOf(row),
                )
            }
        }

        composeRule.onNodeWithText("Inspect").performClick()
        composeRule.onNodeWithText("Edit").assertIsDisplayed()
        composeRule.onNodeWithText("Task details · changes save on this phone first").assertIsDisplayed()
    }

    @Test
    fun weekFlexibleTaskOpensDetail() {
        val row = todayTask().copy(scheduledFor = null, allDay = false, scheduleType = "FLEXIBLE")
        val date = LocalDate.now(TaskTimeZone)
        val schedule = DaySchedule(
            date = date,
            zoneId = TaskTimeZone,
            planningStart = date.atStartOfDay(TaskTimeZone).toInstant(),
            planningEnd = date.plusDays(1).atStartOfDay(TaskTimeZone).toInstant(),
            blocks = emptyList(),
            flexibleTasks = emptyList(),
        )
        composeRule.setContent {
            MaterialTheme {
                DashboardScreen(
                    modifier = Modifier,
                    selectedTab = DashboardTab.Week,
                    onTabSelected = {},
                    pending = listOf(row),
                    completionQueue = emptyList(),
                    syncError = null,
                    syncing = false,
                    lastSyncedAt = null,
                    goals = emptyList(),
                    goalsLoading = false,
                    goalsError = null,
                    habits = emptyList(),
                    selectedHabitDetail = null,
                    morningBriefing = null,
                    analyticsSummary = null,
                    showPlannedVsActual = false,
                    eveningReviewSummary = null,
                    dailyReviewNote = "",
                    dailyReviewFinished = false,
                    resolvedReviewOccurrenceIds = emptySet(),
                    userPreferences = UserPreferences(),
                    notificationPermissionGranted = true,
                    notificationSettingsRecommended = false,
                    onEnableNotifications = {},
                    onRetrySync = {},
                    onRetryGoals = {},
                    onDismissCompletion = {},
                    onComplete = {},
                    plannerSchedulesByDate = mapOf(date to schedule),
                    plannerFlexibleTasks = listOf(FlexibleTask(row.id, row.title, 30)),
                    plannerWeekOccurrences = listOf(row),
                )
            }
        }

        composeRule.onNodeWithText("Details").performClick()
        composeRule.onNodeWithText("Edit").assertIsDisplayed()
        composeRule.onNodeWithText("Anytime").assertIsDisplayed()
    }

    @Test
    fun widgetLaunchOpensQuickCaptureAndCreatesAnUnscheduledTask() {
        val selectedTab = mutableStateOf(DashboardTab.Today)
        val created = AtomicReference<QuickCaptureSubmission?>(null)
        val consumedToken = AtomicReference<Long?>(null)
        val requestDate = LocalDate.of(2026, 10, 22)
        composeRule.setContent {
            MaterialTheme {
                DashboardScreen(
                    modifier = Modifier,
                    selectedTab = selectedTab.value,
                    onTabSelected = { selectedTab.value = it },
                    pending = emptyList(),
                    completionQueue = emptyList(),
                    syncError = null,
                    syncing = false,
                    lastSyncedAt = null,
                    goals = emptyList(),
                    goalsLoading = false,
                    goalsError = null,
                    habits = emptyList(),
                    selectedHabitDetail = null,
                    morningBriefing = null,
                    analyticsSummary = null,
                    showPlannedVsActual = false,
                    eveningReviewSummary = null,
                    dailyReviewNote = "",
                    dailyReviewFinished = false,
                    resolvedReviewOccurrenceIds = emptySet(),
                    userPreferences = UserPreferences(),
                    notificationPermissionGranted = true,
                    notificationSettingsRecommended = false,
                    onEnableNotifications = {},
                    onRetrySync = {},
                    onRetryGoals = {},
                    onDismissCompletion = {},
                    onComplete = {},
                    quickCaptureRequest = QuickCaptureLaunchRequest(41L, requestDate, "now_next_widget"),
                    onQuickCaptureRequestConsumed = { consumedToken.set(it) },
                    onCreateTask = { submission ->
                        created.set(submission)
                    },
                )
            }
        }

        composeRule.onNodeWithText("Quick Capture").assertIsDisplayed()
        composeRule.onNodeWithText("Add to your day from Vanta").assertIsDisplayed()
        composeRule.onNodeWithText("22 Oct", substring = true).fetchSemanticsNode()
        composeRule.waitUntil(timeoutMillis = 2_000) { consumedToken.get() == 41L }
        composeRule.onNodeWithText("Title or quick phrase").performTextInput("Study OS")
        composeRule.onNodeWithText("Duration · optional").performTextReplacement("")
        composeRule.onNodeWithContentDescription("Schedule this task").performClick()
        composeRule.onNodeWithText("Save task").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { created.get() != null }

        composeRule.runOnIdle {
            val submission = checkNotNull(created.get())
            assertEquals("Study OS", submission.title)
            assertEquals(null, submission.notes)
            assertEquals(null, submission.scheduledFor)
            assertEquals(false, submission.allDay)
            assertEquals(null, submission.durationMinutes)
        }
    }

    @Test
    fun quickCaptureAllDaySubmissionUsesSelectedLocalDate() {
        val created = AtomicReference<QuickCaptureSubmission?>(null)
        val requestDate = LocalDate.of(2026, 10, 21)
        composeRule.setContent {
            MaterialTheme {
                QuickCaptureSheet(
                    initialDate = requestDate,
                    source = "today_compact_widget",
                    onDismiss = {},
                    defaultDurationMinutes = 30,
                    onCreateTask = { submission -> created.set(submission) },
                )
            }
        }

        composeRule.onNodeWithText("Title or quick phrase").performTextInput("Holiday")
        composeRule.onNodeWithText("Duration · optional").performTextReplacement("")
        composeRule.onNodeWithText("Schedule type · Planned").performClick()
        composeRule.onNodeWithText("Fixed").performClick()
        composeRule.onNodeWithText("Schedule type · Fixed").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("All day").performClick()
        composeRule.onNodeWithContentDescription("All day").assertIsOn()
        composeRule.onNodeWithText("Save task").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { created.get() != null }

        composeRule.runOnIdle {
            val submission = checkNotNull(created.get())
            assertEquals("Holiday", submission.title)
            assertEquals(requestDate.atStartOfDay(TaskTimeZone).toInstant(), submission.scheduledFor)
            assertEquals(true, submission.allDay)
            assertEquals(null, submission.durationMinutes)
        }
    }

    private fun todayTask() = CachedOccurrence(
        id = "today-occurrence",
        taskId = "today-task",
        title = "Study task",
        notes = "Review chapter notes",
        scheduledFor = LocalDate.now(TaskTimeZone).atTime(9, 0).atZone(TaskTimeZone).toInstant().toString(),
        allDay = false,
        kind = "task",
        status = "pending",
        completedAt = null,
        userId = "test-user",
    )
}
