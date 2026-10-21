package dev.system.app.analytics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsAggregatorTest {
    private val anchor = LocalDate.of(2026, 10, 21)

    @Test
    fun emptyRangeContainsEveryDateAndDoesNotInventFocusOrRates() {
        val summary = AnalyticsAggregator.summarize(anchor, AnalyticsPeriod.SEVEN_DAYS, emptyList())

        assertEquals(7, summary.days.size)
        assertEquals(anchor.minusDays(6), summary.days.first().date)
        assertEquals(anchor, summary.days.last().date)
        assertEquals(0, summary.plannedMinutes)
        assertNull(summary.focusedMinutes)
        assertNull(summary.taskCompletionRate)
        assertNull(summary.habitCompletionRate)
        assertNull(summary.estimationDifferencePercent)
    }

    @Test
    fun oneDayAggregatesPlannedActualAndCompletion() {
        val summary = AnalyticsAggregator.summarize(
            anchor,
            AnalyticsPeriod.SEVEN_DAYS,
            listOf(
                AnalyticsDay(
                    date = anchor,
                    plannedTaskCount = 3,
                    completedTaskCount = 2,
                    plannedMinutes = 90,
                    actualFocusSeconds = 72 * 60L,
                ),
            ),
        )

        assertEquals(90, summary.plannedMinutes)
        assertEquals(72, summary.focusedMinutes)
        assertEquals(1, summary.focusCoverageDays)
        assertEquals(3, summary.plannedTaskCount)
        assertEquals(2, summary.completedTaskCount)
        assertEquals(2.0 / 3.0, summary.taskCompletionRate!!, 0.0001)
    }

    @Test
    fun sevenDayWindowIncludesStartAndEndAndDropsOutOfRangeRows() {
        val start = anchor.minusDays(6)
        val summary = AnalyticsAggregator.summarize(
            anchor,
            AnalyticsPeriod.SEVEN_DAYS,
            listOf(
                AnalyticsDay(start.minusDays(1), plannedTaskCount = 50, plannedMinutes = 500),
                AnalyticsDay(start, plannedTaskCount = 1, completedTaskCount = 1, plannedMinutes = 30),
                AnalyticsDay(anchor, plannedTaskCount = 1, completedTaskCount = 1, plannedMinutes = 45),
                AnalyticsDay(anchor.plusDays(1), plannedTaskCount = 90, plannedMinutes = 900),
            ),
        )

        assertEquals(2, summary.plannedTaskCount)
        assertEquals(75, summary.plannedMinutes)
        assertEquals(start, summary.days.first().date)
        assertEquals(anchor, summary.days.last().date)
    }

    @Test
    fun thirtyDayWindowHasThirtyLocalDatesAndBoundedTotals() {
        val rows = (0L until 30L).map { offset ->
            AnalyticsDay(
                date = anchor.minusDays(offset),
                plannedTaskCount = 2,
                completedTaskCount = 1,
                plannedMinutes = 30,
                actualFocusSeconds = 15 * 60L,
            )
        }
        val summary = AnalyticsAggregator.summarize(anchor, AnalyticsPeriod.THIRTY_DAYS, rows)

        assertEquals(30, summary.days.size)
        assertEquals(60, summary.plannedTaskCount)
        assertEquals(30, summary.completedTaskCount)
        assertEquals(900, summary.plannedMinutes)
        assertEquals(450, summary.focusedMinutes)
    }

    @Test
    fun estimationUsesOnlyTasksWithBothPlanAndFocusAndWaitsForThreeSamples() {
        val twoSamples = AnalyticsDay(
            date = anchor,
            estimateSamples = listOf(
                TaskEstimateSample("one", 60, 75),
                TaskEstimateSample("two", 30, 30),
            ),
        )
        val notEnough = AnalyticsAggregator.summarize(anchor, AnalyticsPeriod.SEVEN_DAYS, listOf(twoSamples))
        assertNull(notEnough.estimationDifferencePercent)
        assertEquals(2, notEnough.estimationSampleCount)

        val enough = AnalyticsAggregator.summarize(
            anchor,
            AnalyticsPeriod.SEVEN_DAYS,
            listOf(
                twoSamples,
                AnalyticsDay(anchor.minusDays(1), estimateSamples = listOf(TaskEstimateSample("three", 60, 75))),
            ),
        )
        assertEquals(3, enough.estimationSampleCount)
        assertEquals(20.0, enough.estimationDifferencePercent!!, 0.0001)
    }

    @Test
    fun noFocusIsDifferentFromMeasuredZeroAndPartialCoverageStaysVisible() {
        val noFocus = AnalyticsAggregator.summarize(
            anchor,
            AnalyticsPeriod.SEVEN_DAYS,
            listOf(AnalyticsDay(anchor, plannedMinutes = 30)),
        )
        assertNull(noFocus.focusedMinutes)
        assertNull(noFocus.days.last().focusedMinutes)

        val partial = AnalyticsAggregator.summarize(
            anchor,
            AnalyticsPeriod.SEVEN_DAYS,
            listOf(
                AnalyticsDay(anchor.minusDays(1), actualFocusSeconds = 0),
                AnalyticsDay(anchor, actualFocusSeconds = 25 * 60L),
            ),
        )
        assertEquals(25, partial.focusedMinutes)
        assertEquals(2, partial.focusCoverageDays)
        assertEquals(0, partial.days[5].focusedMinutes)
        assertEquals(25, partial.days[6].focusedMinutes)
    }

    @Test
    fun habitRateIsAggregatedFromOccurrenceCounts() {
        val summary = AnalyticsAggregator.summarize(
            anchor,
            AnalyticsPeriod.SEVEN_DAYS,
            listOf(
                AnalyticsDay(anchor.minusDays(1), habitExpectedCount = 2, habitCompletedCount = 1),
                AnalyticsDay(anchor, habitExpectedCount = 3, habitCompletedCount = 3),
            ),
        )

        assertEquals(5, summary.habitExpectedCount)
        assertEquals(4, summary.habitCompletedCount)
        assertEquals(0.8, summary.habitCompletionRate!!, 0.0001)
    }

    @Test
    fun instantIsAssignedToTheCorrectLocalDateAtTimezoneBoundary() {
        val zone = ZoneId.of("Asia/Kolkata")
        val beforeMidnightUtc = Instant.parse("2026-10-20T18:29:59Z")
        val atLocalMidnight = Instant.parse("2026-10-20T18:30:00Z")

        assertEquals(LocalDate.of(2026, 10, 20), analyticsLocalDate(beforeMidnightUtc, zone))
        assertEquals(LocalDate.of(2026, 10, 21), analyticsLocalDate(atLocalMidnight, zone))
        assertFalse(analyticsDateRange(anchor, AnalyticsPeriod.SEVEN_DAYS).startInclusive.isAfter(anchor))
        assertTrue(analyticsDateRange(anchor, AnalyticsPeriod.SEVEN_DAYS).endInclusive == anchor)
    }
}
