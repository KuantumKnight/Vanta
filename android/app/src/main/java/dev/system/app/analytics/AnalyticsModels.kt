package dev.system.app.analytics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A bounded analytics window. The range is inclusive of its end date. */
enum class AnalyticsPeriod(val days: Int, val label: String) {
    SEVEN_DAYS(7, "7 days"),
    THIRTY_DAYS(30, "30 days"),
}

/** Per-task duration sample. Only tasks with both a planned duration and recorded focus count. */
data class TaskEstimateSample(
    val occurrenceId: String,
    val plannedMinutes: Int,
    val focusedMinutes: Int,
) {
    init {
        require(occurrenceId.isNotBlank())
        require(plannedMinutes > 0)
        require(focusedMinutes >= 0)
    }
}

/** One local calendar day's already-aggregated, user-owned Room values. */
data class AnalyticsDay(
    val date: LocalDate,
    val plannedTaskCount: Int = 0,
    val completedTaskCount: Int = 0,
    val plannedMinutes: Int = 0,
    /** Null means no focus session data is available for this day; zero means it was measured. */
    val actualFocusSeconds: Long? = null,
    val habitExpectedCount: Int = 0,
    val habitCompletedCount: Int = 0,
    val estimateSamples: List<TaskEstimateSample> = emptyList(),
) {
    init {
        require(plannedTaskCount >= 0)
        require(completedTaskCount >= 0)
        require(plannedMinutes >= 0)
        require(actualFocusSeconds == null || actualFocusSeconds >= 0)
        require(habitExpectedCount >= 0)
        require(habitCompletedCount >= 0)
    }
}

data class AnalyticsDayPoint(
    val date: LocalDate,
    val plannedMinutes: Int,
    /** Null keeps an unrecorded day visually distinct from a measured zero. */
    val focusedMinutes: Int?,
    val plannedTaskCount: Int,
    val completedTaskCount: Int,
)

data class AnalyticsSummary(
    val period: AnalyticsPeriod,
    val days: List<AnalyticsDayPoint>,
    val plannedMinutes: Int,
    val focusedMinutes: Int?,
    val focusCoverageDays: Int,
    val plannedTaskCount: Int,
    val completedTaskCount: Int,
    val taskCompletionRate: Double?,
    val habitExpectedCount: Int,
    val habitCompletedCount: Int,
    val habitCompletionRate: Double?,
    /** Aggregate (focused - planned) / planned, shown only with enough task samples. */
    val estimationDifferencePercent: Double?,
    val estimationSampleCount: Int,
)

data class AnalyticsDateRange(val startInclusive: LocalDate, val endInclusive: LocalDate) {
    init {
        require(!endInclusive.isBefore(startInclusive))
    }
}

/** Returns the local calendar date for an event using the user's chosen zone. */
fun analyticsLocalDate(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()

/** Creates an inclusive, bounded range ending on [anchorDate]. */
fun analyticsDateRange(anchorDate: LocalDate, period: AnalyticsPeriod): AnalyticsDateRange =
    AnalyticsDateRange(anchorDate.minusDays(period.days - 1L), anchorDate)
