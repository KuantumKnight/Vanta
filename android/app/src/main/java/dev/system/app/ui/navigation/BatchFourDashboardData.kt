package dev.system.app.ui.navigation

import dev.system.app.analytics.AnalyticsAggregator
import dev.system.app.analytics.AnalyticsDay
import dev.system.app.analytics.AnalyticsPeriod
import dev.system.app.analytics.AnalyticsSummary
import dev.system.app.analytics.TaskEstimateSample
import dev.system.app.data.FocusSessionHistory
import dev.system.app.data.OccurrenceHistory
import dev.system.app.eveningreview.EveningReviewCalculator
import dev.system.app.eveningreview.EveningReviewSummary
import dev.system.app.habits.HabitDetail
import dev.system.app.habits.HabitHistoryDay
import dev.system.app.habits.HabitOccurrence
import dev.system.app.habits.HabitOccurrenceStatus
import dev.system.app.habits.HabitStreakCalculator
import dev.system.app.habits.HabitSummary
import dev.system.app.habits.HabitTodayOccurrence
import java.time.DayOfWeek
import java.time.LocalDate

/** Maps bounded, owner-scoped Room rows into the V2 habit destinations. */
internal data class HabitDashboardData(
    val summaries: List<HabitSummary>,
    val details: Map<String, HabitDetail>,
)

internal fun buildHabitDashboardData(
    history: List<OccurrenceHistory>,
    today: LocalDate,
    weekStartDay: DayOfWeek,
): HabitDashboardData {
    val dated = history.asSequence()
        .filter { it.kind == "habit" }
        .mapNotNull { item ->
            val date = item.scheduledDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            if (date.isAfter(today)) return@mapNotNull null
            item to date
        }
        .distinctBy { it.first.occurrenceId }
        .toList()

    val weekOffset = (today.dayOfWeek.value - weekStartDay.value + 7) % 7
    val weekStart = today.minusDays(weekOffset.toLong())
    val historyStart = today.minusDays(HABIT_HISTORY_DAYS - 1L)

    val grouped = dated.groupBy { it.first.taskId }
    val summaries = mutableListOf<HabitSummary>()
    val details = linkedMapOf<String, HabitDetail>()

    grouped.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (habitId, rows) ->
        val title = rows.firstOrNull { it.first.title.isNotBlank() }?.first?.title ?: "Habit"
        val byDate = rows.groupBy { it.second }.mapValues { (_, occurrences) ->
            // A habit can have more than one expected item on a date. Any unresolved expected
            // item keeps the date incomplete; skipped-only dates remain streak-neutral.
            when {
                occurrences.any { it.first.status == "pending" } -> HabitOccurrenceStatus.PENDING
                occurrences.any { it.first.status == "completed" } -> HabitOccurrenceStatus.COMPLETED
                else -> HabitOccurrenceStatus.SKIPPED
            }
        }
        val streakOccurrences = byDate.map { (date, status) -> HabitOccurrence(date, status) }
        val streak = HabitStreakCalculator.calculate(streakOccurrences, today)
        val todayRows = rows.asSequence()
            .filter { it.second == today }
            .map { (item, date) ->
                HabitTodayOccurrence(
                    occurrenceId = item.occurrenceId,
                    habitId = habitId,
                    title = item.title,
                    localDate = date,
                    status = item.status.toHabitStatus(),
                )
            }
            .sortedBy { it.title.lowercase() }
            .toList()

        summaries += HabitSummary(
            id = habitId,
            title = title,
            todayOccurrences = todayRows,
            currentStreak = streak.current,
            bestStreak = streak.best,
            streakUnitLabel = "expected occurrences",
        )

        val thisWeek = (0..6).map { offset ->
            val date = weekStart.plusDays(offset.toLong())
            HabitHistoryDay(date, byDate[date])
        }
        val historyDays = (0 until HABIT_HISTORY_DAYS.toInt()).map { offset ->
            val date = historyStart.plusDays(offset.toLong())
            HabitHistoryDay(date, byDate[date])
        }
        details[habitId] = HabitDetail(
            id = habitId,
            title = title,
            scheduleDescription = "Streaks count consecutive expected occurrences. Skipped occurrences are neutral. Current and best streaks use the past 365 days of history, so a longer run may extend beyond this range.",
            currentStreak = streak.current,
            bestStreak = streak.best,
            streakUnitLabel = "expected occurrences",
            thisWeek = thisWeek,
            history = historyDays,
        )
    }

    return HabitDashboardData(summaries, details)
}

/** Builds transparent local metrics from bounded occurrence and existing focus-session history. */
internal fun buildAnalyticsSummary(
    anchorDate: LocalDate,
    period: AnalyticsPeriod,
    occurrences: List<OccurrenceHistory>,
    focusSessions: List<FocusSessionHistory>,
): AnalyticsSummary {
    val focusedByDate = mutableMapOf<LocalDate, Long>()
    val focusedByOccurrence = mutableMapOf<String, Long>()
    focusSessions.forEach { session ->
        val end = session.finishedAtEpochMillis ?: return@forEach
        val durationSeconds = ((end - session.startedAtEpochMillis).coerceAtLeast(0L)) / 1_000L
        val date = runCatching {
            java.time.Instant.ofEpochMilli(session.startedAtEpochMillis).atZone(VANTA_ANALYTICS_ZONE).toLocalDate()
        }.getOrNull() ?: return@forEach
        focusedByDate[date] = (focusedByDate[date] ?: 0L) + durationSeconds
        focusedByOccurrence[session.occurrenceId] = (focusedByOccurrence[session.occurrenceId] ?: 0L) + durationSeconds
    }

    val dated = occurrences.mapNotNull { row ->
        val date = row.scheduledDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
        row to date
    }
    val occurrencesByDate = dated.groupBy { it.second }
    val days = (occurrencesByDate.keys + focusedByDate.keys).distinct().map { date ->
        val rows = occurrencesByDate[date].orEmpty()
        val habits = rows.filter { it.first.kind == "habit" }
        val tasks = rows.filter { it.first.kind == "task" && it.first.status != "skipped" }
        val samples = tasks.mapNotNull { (task, _) ->
            val planned = task.durationMinutes?.takeIf { it in 1..1_440 } ?: return@mapNotNull null
            val focused = focusedByOccurrence[task.occurrenceId]?.takeIf { it > 0L } ?: return@mapNotNull null
            TaskEstimateSample(
                occurrenceId = task.occurrenceId,
                plannedMinutes = planned,
                focusedMinutes = (focused / 60L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
        AnalyticsDay(
            date = date,
            plannedTaskCount = tasks.size,
            completedTaskCount = tasks.count { it.first.status == "completed" },
            plannedMinutes = tasks.sumOf { (occurrence, _) ->
                occurrence.durationMinutes?.takeIf { it in 1..1_440 } ?: 0
            },
            actualFocusSeconds = focusedByDate[date],
            habitExpectedCount = habits.size,
            habitCompletedCount = habits.count { it.first.status == "completed" },
            estimateSamples = samples,
        )
    }

    return AnalyticsAggregator.summarize(anchorDate, period, days)
}

internal fun buildEveningReviewSummary(
    date: LocalDate,
    occurrences: List<OccurrenceHistory>,
    focusSessions: List<FocusSessionHistory>,
): EveningReviewSummary {
    val focusSeconds = focusSessions.asSequence()
        .mapNotNull { session -> session.finishedAtEpochMillis?.let { end -> (end - session.startedAtEpochMillis).coerceAtLeast(0L) } }
        .sum()
    val focusMinutes = focusSessions.takeIf { it.any { session -> session.finishedAtEpochMillis != null } }
        ?.let { focusSeconds / 60_000L }
    return EveningReviewCalculator.calculate(
        date = date,
        occurrences = occurrences.map(OccurrenceHistory::toCachedOccurrence),
        focusMinutes = focusMinutes,
    )
}

private fun String.toHabitStatus(): HabitOccurrenceStatus = when (this) {
    "completed" -> HabitOccurrenceStatus.COMPLETED
    "skipped" -> HabitOccurrenceStatus.SKIPPED
    else -> HabitOccurrenceStatus.PENDING
}

private const val HABIT_HISTORY_DAYS = 365L
private val VANTA_ANALYTICS_ZONE = java.time.ZoneId.of("Asia/Kolkata")
