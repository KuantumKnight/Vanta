package dev.system.app.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.Modifier
import dev.system.app.data.CachedOccurrence
import dev.system.app.ui.components.TaskTimeZone
import dev.system.app.ui.quickcapture.QuickCaptureLaunchRequest
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
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
        composeRule.onNode(hasSetTextAction()).performTextInput("not found")
        composeRule.onNodeWithText("Study task").assertDoesNotExist()
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
    fun widgetLaunchOpensQuickCaptureAndCreatesAnUnscheduledTask() {
        val selectedTab = mutableStateOf(DashboardTab.Today)
        val created = AtomicReference<List<Any?>?>(null)
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
                    notificationPermissionGranted = true,
                    notificationSettingsRecommended = false,
                    onEnableNotifications = {},
                    onRetrySync = {},
                    onRetryGoals = {},
                    onDismissCompletion = {},
                    onComplete = {},
                    quickCaptureRequest = QuickCaptureLaunchRequest(41L, requestDate, "now_next_widget"),
                    onQuickCaptureRequestConsumed = { consumedToken.set(it) },
                    onCreateTask = { title, notes, scheduledFor, allDay, duration ->
                        created.set(listOf(title, notes, scheduledFor, allDay, duration))
                    },
                )
            }
        }

        composeRule.onNodeWithText("Quick Capture").assertIsDisplayed()
        composeRule.onNodeWithText("Add to your day from Vanta").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 2_000) { consumedToken.get() == 41L }
        composeRule.onNodeWithText("Title or quick phrase").performTextInput("Study OS")
        composeRule.onNodeWithContentDescription("Schedule this task").performClick()
        composeRule.onNodeWithText("Save task").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { created.get() != null }

        composeRule.runOnIdle {
            assertEquals(listOf("Study OS", null, null, false, null), created.get())
        }
    }

    @Test
    fun quickCaptureAllDaySubmissionUsesSelectedLocalDate() {
        val selectedTab = mutableStateOf(DashboardTab.Today)
        val created = AtomicReference<List<Any?>?>(null)
        val requestDate = LocalDate.of(2026, 10, 21)
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
                    notificationPermissionGranted = true,
                    notificationSettingsRecommended = false,
                    onEnableNotifications = {},
                    onRetrySync = {},
                    onRetryGoals = {},
                    onDismissCompletion = {},
                    onComplete = {},
                    quickCaptureRequest = QuickCaptureLaunchRequest(42L, requestDate, "today_compact_widget"),
                    onQuickCaptureRequestConsumed = {},
                    onCreateTask = { title, notes, scheduledFor, allDay, duration ->
                        created.set(listOf(title, notes, scheduledFor, allDay, duration))
                    },
                )
            }
        }

        composeRule.onNodeWithText("Title or quick phrase").performTextInput("Holiday")
        composeRule.onNodeWithContentDescription("All day").performClick()
        composeRule.onNodeWithText("Save task").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { created.get() != null }

        composeRule.runOnIdle {
            val values = checkNotNull(created.get())
            assertEquals("Holiday", values[0])
            assertEquals(requestDate.atStartOfDay(TaskTimeZone).toInstant(), values[2])
            assertEquals(true, values[3])
            assertEquals(null, values[4])
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
