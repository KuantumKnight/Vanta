package dev.system.app.analytics

import java.time.LocalDate

/** Pure aggregation over already bounded, owner-scoped local rows. */
object AnalyticsAggregator {
    const val MIN_ESTIMATION_SAMPLES = 3

    fun summarize(
        anchorDate: LocalDate,
        period: AnalyticsPeriod,
        sourceDays: Iterable<AnalyticsDay>,
    ): AnalyticsSummary {
        val range = analyticsDateRange(anchorDate, period)
        val byDate = sourceDays
            .asSequence()
            .filter { !it.date.isBefore(range.startInclusive) && !it.date.isAfter(range.endInclusive) }
            .associateBy(AnalyticsDay::date)

        val points = (0 until period.days).map { offset ->
            val date = range.startInclusive.plusDays(offset.toLong())
            val source = byDate[date]
            AnalyticsDayPoint(
                date = date,
                plannedMinutes = source?.plannedMinutes ?: 0,
                focusedMinutes = source?.actualFocusSeconds?.let { seconds -> (seconds / 60L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() },
                plannedTaskCount = source?.plannedTaskCount ?: 0,
                completedTaskCount = source?.completedTaskCount?.coerceAtMost(source.plannedTaskCount) ?: 0,
            )
        }
        val inRangeDays = byDate.values
        // A focus history query may return several sessions for one occurrence. The lead maps
        // those sessions into one sample; keep this guard so duplicate Room rows cannot inflate
        // the sample threshold or count one task more than once.
        val samples = inRangeDays.flatMap(AnalyticsDay::estimateSamples)
            .distinctBy(TaskEstimateSample::occurrenceId)
        val plannedForSamples = samples.sumOf { it.plannedMinutes.toLong() }
        val focusedForSamples = samples.sumOf { it.focusedMinutes.toLong() }
        val estimationDifference = if (samples.size >= MIN_ESTIMATION_SAMPLES && plannedForSamples > 0) {
            ((focusedForSamples - plannedForSamples).toDouble() / plannedForSamples) * 100.0
        } else {
            null
        }

        val plannedTaskCount = inRangeDays.sumOf(AnalyticsDay::plannedTaskCount)
        val completedTaskCount = inRangeDays.sumOf { it.completedTaskCount.coerceAtMost(it.plannedTaskCount) }
        val habitExpected = inRangeDays.sumOf(AnalyticsDay::habitExpectedCount)
        val habitCompleted = inRangeDays.sumOf { it.habitCompletedCount.coerceAtMost(it.habitExpectedCount) }
        val measuredDays = inRangeDays.filter { it.actualFocusSeconds != null }
        val focusedMinutes = measuredDays.takeIf { it.isNotEmpty() }?.sumOf { day ->
            day.actualFocusSeconds!!
        }?.div(60L)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()

        return AnalyticsSummary(
            period = period,
            days = points,
            plannedMinutes = inRangeDays.sumOf(AnalyticsDay::plannedMinutes),
            focusedMinutes = focusedMinutes,
            focusCoverageDays = measuredDays.size,
            plannedTaskCount = plannedTaskCount,
            completedTaskCount = completedTaskCount,
            taskCompletionRate = plannedTaskCount.takeIf { it > 0 }
                ?.let { completedTaskCount.toDouble() / it },
            habitExpectedCount = habitExpected,
            habitCompletedCount = habitCompleted,
            habitCompletionRate = habitExpected.takeIf { it > 0 }
                ?.let { habitCompleted.toDouble() / it },
            estimationDifferencePercent = estimationDifference,
            estimationSampleCount = samples.size,
        )
    }
}
