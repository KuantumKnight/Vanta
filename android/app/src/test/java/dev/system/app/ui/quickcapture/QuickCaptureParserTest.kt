package dev.system.app.ui.quickcapture

import dev.system.app.ui.components.TaskTimeZone
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCaptureParserTest {
    private val today = LocalDate.of(2026, 10, 21)

    @Test
    fun parsesClearNaturalLanguageScheduleAndDuration() {
        val result = parseQuickCapturePhrase("Study OS tomorrow 6 PM for 90 min", today)

        assertEquals("Study OS", result.title)
        assertEquals(today.plusDays(1), result.date)
        assertEquals(LocalTime.of(18, 0), result.startTime)
        assertEquals(90, result.durationMinutes)
        assertTrue(result.recognizedSchedule)
    }

    @Test
    fun parsesTwentyFourHourTimeAndHoursDuration() {
        val result = parseQuickCapturePhrase("Read tomorrow at 17:00 for 2 hours", today)

        assertEquals("Read", result.title)
        assertEquals(today.plusDays(1), result.date)
        assertEquals(LocalTime.of(17, 0), result.startTime)
        assertEquals(120, result.durationMinutes)
    }

    @Test
    fun ambiguousMultipleTimesStayInTitle() {
        val result = parseQuickCapturePhrase("Study at 6 or 8 tomorrow", today)

        assertEquals("Study at 6 or 8", result.title)
        assertEquals(today.plusDays(1), result.date)
        assertNull(result.startTime)
    }

    @Test
    fun unsupportedPhraseRemainsUntouched() {
        val phrase = "Study next week after dinner"
        val result = parseQuickCapturePhrase(phrase, today)

        assertEquals(phrase, result.title)
        assertFalse(result.recognizedSchedule)
        assertNull(result.date)
        assertNull(result.startTime)
        assertNull(result.durationMinutes)
    }

    @Test
    fun scheduleTimeWithoutDateUsesTheSelectedDate() {
        val result = parseQuickCapturePhrase("Review at 5 pm", today)

        assertEquals(today, result.date)
        assertEquals(LocalTime.of(17, 0), result.startTime)
    }

    @Test
    fun parserHandlesMidnightAndNoon() {
        val midnight = parseQuickCapturePhrase("Sleep at 12 AM", today)
        val noon = parseQuickCapturePhrase("Lunch at 12 PM", today)

        assertEquals(LocalTime.MIDNIGHT, midnight.startTime)
        assertEquals(LocalTime.NOON, noon.startTime)
    }
}

class QuickCaptureValidationTest {
    private val date = LocalDate.of(2026, 10, 21)

    @Test
    fun buildsUnscheduledTaskWithoutInventingDuration() {
        val validation = validateQuickCapture(
            title = " Read book ",
            notes = "  ",
            scheduleEnabled = false,
            date = date,
            startTime = null,
            allDay = false,
            durationText = "",
        )

        val submission = checkNotNull(validation.submission)
        assertEquals("Read book", submission.title)
        assertNull(submission.notes)
        assertNull(submission.scheduledFor)
        assertFalse(submission.allDay)
        assertNull(submission.durationMinutes)
    }

    @Test
    fun buildsTimedScheduleAtTheSelectedLocalTime() {
        val validation = validateQuickCapture(
            title = "Study",
            notes = "chapter 2",
            scheduleEnabled = true,
            date = date,
            startTime = LocalTime.of(18, 0),
            allDay = false,
            durationText = "90",
        )

        val submission = checkNotNull(validation.submission)
        assertEquals(Instant.parse("2026-10-21T12:30:00Z"), submission.scheduledFor)
        assertEquals(90, submission.durationMinutes)
        assertEquals("chapter 2", submission.notes)
    }

    @Test
    fun allDayRequiresDateAndUsesStartOfDay() {
        val validation = validateQuickCapture(
            title = "Holiday",
            notes = "",
            scheduleEnabled = true,
            date = date,
            startTime = null,
            allDay = true,
            durationText = "",
        )

        val submission = checkNotNull(validation.submission)
        assertEquals(date.atStartOfDay(TaskTimeZone).toInstant(), submission.scheduledFor)
        assertTrue(submission.allDay)
    }

    @Test
    fun invalidTitleDurationAndScheduleAreRejected() {
        assertEquals(
            QuickCaptureValidationError.EMPTY_TITLE,
            validateQuickCapture("  ", "", false, null, null, false, "").error,
        )
        assertEquals(
            QuickCaptureValidationError.INVALID_DURATION,
            validateQuickCapture("Study", "", false, null, null, false, "4").error,
        )
        assertEquals(
            QuickCaptureValidationError.INVALID_SCHEDULE,
            validateQuickCapture("Study", "", true, date, null, false, "").error,
        )
    }
}
