package dev.system.app.habits

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HabitStreakCalculatorTest {
    private val asOf = LocalDate.of(2026, 10, 21)

    @Test
    fun dailyHabitCountsConsecutiveExpectedDates() {
        val result = HabitStreakCalculator.calculate(
            listOf(
                occurrence("2026-10-17", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-18", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-19", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-20", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-21", HabitOccurrenceStatus.COMPLETED),
            ),
            asOf,
        )

        assertEquals(5, result.current)
        assertEquals(5, result.best)
        assertEquals(5, result.completedInHistory)
    }

    @Test
    fun weeklyHabitCountsScheduledOccurrencesWithoutCountingCalendarGaps() {
        // A Monday/Wednesday/Friday habit has no expected occurrences on Tue/Thu/weekends.
        val result = HabitStreakCalculator.calculate(
            listOf(
                occurrence("2026-10-09", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-12", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-14", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-16", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-19", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-21", HabitOccurrenceStatus.COMPLETED),
            ),
            asOf,
        )

        assertEquals(6, result.current)
        assertEquals(6, result.best)
    }

    @Test
    fun pastPendingOccurrenceBreaksCurrentStreakAndPreservesBest() {
        val result = HabitStreakCalculator.calculate(
            listOf(
                occurrence("2026-10-15", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-16", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-17", HabitOccurrenceStatus.PENDING),
                occurrence("2026-10-18", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-19", HabitOccurrenceStatus.COMPLETED),
            ),
            asOf,
        )

        assertEquals(2, result.current)
        assertEquals(2, result.best)
    }

    @Test
    fun sameDayPendingOccurrenceDoesNotBreakStreakBeforeDayEnds() {
        val result = HabitStreakCalculator.calculate(
            listOf(
                occurrence("2026-10-19", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-20", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-21", HabitOccurrenceStatus.PENDING),
            ),
            asOf,
        )

        assertEquals(2, result.current)
        assertEquals(2, result.best)
    }

    @Test
    fun explicitlySkippedOccurrenceIsNeutralAndNotCountedAsComplete() {
        val result = HabitStreakCalculator.calculate(
            listOf(
                occurrence("2026-10-17", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-18", HabitOccurrenceStatus.SKIPPED),
                occurrence("2026-10-19", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-20", HabitOccurrenceStatus.SKIPPED),
                occurrence("2026-10-21", HabitOccurrenceStatus.COMPLETED),
            ),
            asOf,
        )

        assertEquals(3, result.current)
        assertEquals(3, result.best)
        assertEquals(3, result.completedInHistory)
        assertEquals(2, result.skippedInHistory)
    }

    @Test
    fun aMissedOccurrenceBreaksRunButLaterCompletionsStartANewStreak() {
        val result = HabitStreakCalculator.calculate(
            listOf(
                occurrence("2026-10-14", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-15", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-16", HabitOccurrenceStatus.PENDING),
                occurrence("2026-10-17", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-18", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-19", HabitOccurrenceStatus.COMPLETED),
            ),
            asOf,
        )

        assertEquals(3, result.current)
        assertEquals(3, result.best)
    }

    @Test
    fun futureOccurrencesDoNotAffectCurrentOrBest() {
        val result = HabitStreakCalculator.calculate(
            listOf(
                occurrence("2026-10-20", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-21", HabitOccurrenceStatus.PENDING),
                occurrence("2026-10-22", HabitOccurrenceStatus.COMPLETED),
            ),
            asOf,
        )

        assertEquals(1, result.current)
        assertEquals(1, result.best)
    }

    @Test
    fun recurrenceWindowCanStartAndEndAtScheduleBoundaries() {
        val result = HabitStreakCalculator.calculate(
            listOf(
                occurrence("2026-10-19", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-20", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-21", HabitOccurrenceStatus.COMPLETED),
            ),
            asOf,
        )

        assertEquals(3, result.current)
        assertEquals(3, result.best)
    }

    @Test
    fun offlineOptimisticCompletionCountsImmediatelyWhenCallerProvidesCompletedStatus() {
        val result = HabitStreakCalculator.calculate(
            listOf(
                occurrence("2026-10-20", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-10-21", HabitOccurrenceStatus.COMPLETED),
            ),
            asOf,
        )

        assertEquals(2, result.current)
    }

    @Test
    fun occurrenceDateUsesHabitTimezoneAcrossUtcMidnight() {
        val instant = Instant.parse("2026-10-21T00:30:00Z")

        assertEquals(
            LocalDate.of(2026, 10, 21),
            HabitOccurrence.fromInstant(instant, HabitOccurrenceStatus.COMPLETED, ZoneId.of("Asia/Kolkata")).localDate,
        )
        assertEquals(
            LocalDate.of(2026, 10, 20),
            HabitOccurrence.fromInstant(instant, HabitOccurrenceStatus.COMPLETED, ZoneId.of("America/Los_Angeles")).localDate,
        )
    }

    @Test
    fun streakContinuesAcrossMonthTransition() {
        val result = HabitStreakCalculator.calculate(
            listOf(
                occurrence("2026-01-30", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-01-31", HabitOccurrenceStatus.COMPLETED),
                occurrence("2026-02-01", HabitOccurrenceStatus.COMPLETED),
            ),
            LocalDate.of(2026, 2, 1),
        )

        assertEquals(3, result.current)
        assertEquals(3, result.best)
    }

    @Test
    fun rejectsMultipleExpectedOccurrencesForOneDateAsAmbiguous() {
        assertThrows(IllegalArgumentException::class.java) {
            HabitStreakCalculator.calculate(
                listOf(
                    occurrence("2026-10-21", HabitOccurrenceStatus.COMPLETED),
                    occurrence("2026-10-21", HabitOccurrenceStatus.PENDING),
                ),
                asOf,
            )
        }
    }

    private fun occurrence(date: String, status: HabitOccurrenceStatus) =
        HabitOccurrence(LocalDate.parse(date), status)
}
