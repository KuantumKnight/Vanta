package dev.system.app.ui.planner.week

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A seven-date range represented as a start date and an exclusive end date. */
internal data class WeekDateRange(
    val startInclusive: LocalDate,
    val endExclusive: LocalDate,
) {
    init {
        require(endExclusive == startInclusive.plusDays(DAYS_PER_WEEK.toLong())) {
            "A planner range must contain exactly seven days."
        }
    }

    fun dates(): List<LocalDate> = (0 until DAYS_PER_WEEK).map { startInclusive.plusDays(it.toLong()) }

    fun startInstant(zoneId: ZoneId): Instant = startInclusive.atStartOfDay(zoneId).toInstant()

    fun endInstant(zoneId: ZoneId): Instant = endExclusive.atStartOfDay(zoneId).toInstant()

    fun duration(zoneId: ZoneId): Duration = Duration.between(startInstant(zoneId), endInstant(zoneId))

    operator fun contains(date: LocalDate): Boolean = date >= startInclusive && date < endExclusive
}

internal const val DAYS_PER_WEEK = 7

internal fun weekDateRange(
    date: LocalDate,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
): WeekDateRange {
    val start = date.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
    return WeekDateRange(start, start.plusDays(DAYS_PER_WEEK.toLong()))
}

internal fun shiftWeek(range: WeekDateRange, amount: Long): WeekDateRange {
    val shiftedStart = range.startInclusive.plusWeeks(amount)
    return WeekDateRange(shiftedStart, shiftedStart.plusDays(DAYS_PER_WEEK.toLong()))
}
