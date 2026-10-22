package dev.system.app.ui.taskdetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import dev.system.app.data.CachedOccurrence
import dev.system.app.ui.components.TaskCard
import dev.system.app.ui.quickcapture.QuickCaptureSubmission
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TaskDetailSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editedTaskSavesValidatedDraft() {
        val saved = AtomicReference<QuickCaptureSubmission?>(null)
        composeRule.setContent {
            MaterialTheme {
                TaskDetailSheet(
                    row = taskRow(),
                    onDismiss = {},
                    onSave = { _, submission -> saved.set(submission) },
                    onComplete = {},
                    onStartFocus = {},
                )
            }
        }

        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithContentDescription("Task title").performTextReplacement("Updated title")
        composeRule.onNodeWithContentDescription("Task notes").performTextReplacement("New notes")
        composeRule.onNodeWithText("Save changes").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { saved.get() != null }

        composeRule.runOnIdle {
            val result = checkNotNull(saved.get())
            assertEquals("Updated title", result.title)
            assertEquals("New notes", result.notes)
            assertEquals(30, result.durationMinutes)
        }
    }

    @Test
    fun taskCardOpensTheProvidedDetailAction() {
        val opened = AtomicReference<CachedOccurrence?>(null)
        composeRule.setContent {
            MaterialTheme {
                TaskCard(row = taskRow(), onComplete = {}, onOpenTask = { opened.set(it) })
            }
        }

        composeRule.onNodeWithContentDescription("Open details for Study task").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { opened.get() != null }
        composeRule.runOnIdle { assertEquals("task-occurrence", opened.get()?.id) }
    }

    @Test
    fun detailContentRemainsScrollableWithLargeFontScale() {
        val longNotes = List(120) { "note-$it" }.joinToString("\n")
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.8f)) {
                MaterialTheme {
                    TaskDetailSheet(
                        row = taskRow().copy(notes = longNotes),
                        onDismiss = {},
                        onSave = { _, _ -> },
                        onComplete = {},
                        onStartFocus = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Study task").assertIsDisplayed()
        composeRule.onNodeWithText("note-119", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun cancelOffersDiscardAndKeepEditingChoices() {
        val dismissed = AtomicReference(false)
        composeRule.setContent {
            MaterialTheme {
                TaskDetailSheet(
                    row = taskRow(),
                    onDismiss = { dismissed.set(true) },
                    onSave = { _, _ -> },
                    onComplete = {},
                    onStartFocus = {},
                )
            }
        }

        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithContentDescription("Task title").performTextInput(" changed")
        composeRule.onNodeWithText("Cancel edit").performScrollTo().performClick()
        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.onNodeWithContentDescription("Task title").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel edit").performScrollTo().performClick()
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.onNodeWithText("Edit").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Task title").assertCountEquals(0)
        assertTrue(!dismissed.get())
    }

    @Test
    fun invalidTitleShowsValidationAndBlocksSave() {
        composeRule.setContent {
            MaterialTheme {
                TaskDetailSheet(
                    row = taskRow(),
                    onDismiss = {},
                    onSave = { _, _ -> error("Invalid task must not save") },
                    onComplete = {},
                    onStartFocus = {},
                )
            }
        }

        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithContentDescription("Task title").performTextClearance()
        composeRule.onNodeWithText("Add a title to save this task.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Save changes").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun completedTaskShowsReadOnlyDetailsWithoutEditControls() {
        composeRule.setContent {
            MaterialTheme {
                TaskDetailSheet(
                    row = taskRow().copy(status = "completed", completedAt = "2026-10-22T10:00:00Z"),
                    onDismiss = {},
                    onSave = { _, _ -> error("Completed task must not save") },
                    onComplete = {},
                    onStartFocus = {},
                )
            }
        }

        composeRule.onNodeWithText("Completed task · details are read-only").assertIsDisplayed()
        composeRule.onAllNodesWithText("Task notes").assertCountEquals(0)
        composeRule.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test
    fun habitOccurrenceStaysReadOnly() {
        composeRule.setContent {
            MaterialTheme {
                TaskDetailSheet(
                    row = taskRow().copy(kind = "habit"),
                    onDismiss = {},
                    onSave = { _, _ -> error("Recurring habit occurrence must not save") },
                    onComplete = {},
                    onStartFocus = {},
                )
            }
        }

        composeRule.onNodeWithText("Habit occurrence · details are read-only").assertIsDisplayed()
        composeRule.onAllNodesWithText("Edit").assertCountEquals(0)
    }

    @Test
    fun taskWithoutUpdateEligibilityStaysReadOnly() {
        composeRule.setContent {
            MaterialTheme {
                TaskDetailSheet(
                    row = taskRow(),
                    canEditTask = { false },
                    onDismiss = {},
                    onSave = { _, _ -> error("Ineligible task must not save") },
                    onComplete = {},
                    onStartFocus = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Edit").assertCountEquals(0)
        composeRule.onNodeWithText("This task is read-only").assertIsDisplayed()
        composeRule.onNodeWithText("Start focus").assertIsDisplayed()
        composeRule.onNodeWithText("Complete").assertIsDisplayed()
    }

    @Test
    fun flexibleTaskCanBeScheduledFromEditMode() {
        val saved = AtomicReference<QuickCaptureSubmission?>(null)
        composeRule.setContent {
            MaterialTheme {
                TaskDetailSheet(
                    row = taskRow().copy(scheduledFor = null, allDay = false, scheduleType = "FLEXIBLE"),
                    onDismiss = {},
                    onSave = { _, submission -> saved.set(submission) },
                    onComplete = {},
                    onStartFocus = {},
                )
            }
        }

        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithText("Add a date and time").performClick()
        composeRule.onNodeWithText("Save changes").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { saved.get() != null }
        composeRule.runOnIdle {
            val submission = checkNotNull(saved.get())
            assertTrue(submission.scheduledFor != null)
            assertEquals("SOFT", submission.scheduleType.name)
        }
    }

    private fun taskRow() = CachedOccurrence(
        id = "task-occurrence",
        taskId = "task-id",
        title = "Study task",
        notes = "Review notes",
        scheduledFor = LocalDate.of(2026, 10, 22).atTime(9, 0).atOffset(ZoneOffset.UTC).toInstant().toString(),
        allDay = false,
        kind = "task",
        status = "pending",
        completedAt = null,
        userId = "test-user",
        durationMinutes = 30,
        scheduleType = "SOFT",
        priority = "NORMAL",
    )
}
