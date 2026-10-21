package dev.system.app.ui.planner.week

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekDateRangeTest {
    @Test
    fun mondayBasedRangeContainsTheReferenceDateAndExcludesItsEnd() {
        val range = weekDateRange(LocalDate.parse("2026-10-21"))

        assertEquals(LocalDate.parse("2026-10-19"), range.startInclusive)
        assertEquals(LocalDate.parse("2026-10-26"), range.endExclusive)
        assertEquals(7, range.dates().size)
        assertTrue(LocalDate.parse("2026-10-21") in range)
        assertFalse(range.endExclusive in range)
    }

    @Test
    fun dateRangeCrossesMonthAndYearBoundariesWithoutLosingDays() {
        val monthBoundary = weekDateRange(LocalDate.parse("2026-03-01"))
        assertEquals(LocalDate.parse("2026-02-23"), monthBoundary.startInclusive)
        assertEquals(LocalDate.parse("2026-03-02"), monthBoundary.endExclusive)

        val yearBoundary = weekDateRange(LocalDate.parse("2026-12-31"))
        assertEquals(LocalDate.parse("2026-12-28"), yearBoundary.startInclusive)
        assertEquals(LocalDate.parse("2027-01-04"), yearBoundary.endExclusive)
        assertEquals(LocalDate.parse("2027-01-03"), yearBoundary.dates().last())
    }

    @Test
    fun customSundayStartAndWeekShiftingPreserveSevenDayBounds() {
        val sundayRange = weekDateRange(
            LocalDate.parse("2026-10-21"),
            firstDayOfWeek = DayOfWeek.SUNDAY,
        )
        assertEquals(LocalDate.parse("2026-10-18"), sundayRange.startInclusive)
        assertEquals(LocalDate.parse("2026-10-25"), sundayRange.endExclusive)

        val nextWeek = shiftWeek(sundayRange, 1)
        val previousWeek = shiftWeek(sundayRange, -1)
        assertEquals(LocalDate.parse("2026-10-25"), nextWeek.startInclusive)
        assertEquals(LocalDate.parse("2026-10-11"), previousWeek.startInclusive)
        assertEquals(7, nextWeek.dates().size)
    }

    @Test
    fun weekInstantsFollowTheRequestedTimezoneAcrossDstTransitions() {
        val newYork = ZoneId.of("America/New_York")
        val springForward = weekDateRange(
            LocalDate.parse("2026-03-11"),
            firstDayOfWeek = DayOfWeek.SUNDAY,
        )
        val fallBack = weekDateRange(
            LocalDate.parse("2026-11-04"),
            firstDayOfWeek = DayOfWeek.SUNDAY,
        )

        assertEquals(Duration.ofHours(167), springForward.duration(newYork))
        assertEquals(Duration.ofHours(169), fallBack.duration(newYork))
        assertEquals("2026-03-08T05:00:00Z", springForward.startInstant(newYork).toString())
        assertEquals("2026-03-15T04:00:00Z", springForward.endInstant(newYork).toString())
        assertEquals("2026-11-01T04:00:00Z", fallBack.startInstant(newYork).toString())
        assertEquals("2026-11-08T05:00:00Z", fallBack.endInstant(newYork).toString())
    }
}
