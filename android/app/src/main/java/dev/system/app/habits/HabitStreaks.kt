package dev.system.app.habits

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Status of one expected habit occurrence. A skipped date remains visible but is streak-neutral. */
enum class HabitOccurrenceStatus {
    PENDING,
    COMPLETED,
    SKIPPED,
}

/**
 * One expected occurrence, dated in the habit's own timezone.
 *
 * Callers should query a bounded, contiguous range of expected occurrences. In particular,
 * historical pending occurrences must be included so they break a streak, while recurrence
 * exception dates that were never expected should be omitted.
 */
data class HabitOccurrence(
    val localDate: LocalDate,
    val status: HabitOccurrenceStatus,
) {
    companion object {
        fun fromInstant(
            scheduledAt: Instant,
            status: HabitOccurrenceStatus,
            timeZone: ZoneId,
        ): HabitOccurrence = HabitOccurrence(scheduledAt.atZone(timeZone).toLocalDate(), status)
    }
}

/** Streak lengths count completed expected occurrences, not arbitrary calendar days. */
data class HabitStreak(
    val current: Int,
    val best: Int,
    val completedInHistory: Int,
    val skippedInHistory: Int,
)

/**
 * Calculates streaks from occurrence history. Daily habits count consecutive expected dates;
 * weekly and other supported schedules count consecutive expected occurrences, so unscheduled
 * calendar days never break a streak.
 *
 * The caller supplies every expected occurrence in its history range. Missing historical
 * occurrences are represented as PENDING and break the run once their local date is before
 * [asOfDate]. A pending occurrence on [asOfDate] is still in progress until that local day ends.
 * Explicitly skipped occurrences are neutral: they neither add to the streak nor break it.
 *
 * `best` is exact for the supplied range. A caller that only loads a recent range must label it as
 * range-limited or merge it with a persisted/server aggregate; this calculator never invents older
 * history.
 */
object HabitStreakCalculator {
    fun calculate(
        occurrences: List<HabitOccurrence>,
        asOfDate: LocalDate,
    ): HabitStreak {
        val due = occurrences
            .asSequence()
            .filter { it.localDate <= asOfDate }
            .sortedBy { it.localDate }
            .toList()

        require(due.map { it.localDate }.distinct().size == due.size) {
            "Habit streak input must contain at most one expected occurrence per local date."
        }

        var run = 0
        var best = 0
        var completed = 0
        var skipped = 0

        due.forEach { occurrence ->
            when (occurrence.status) {
                HabitOccurrenceStatus.COMPLETED -> {
                    run += 1
                    completed += 1
                    best = maxOf(best, run)
                }

                HabitOccurrenceStatus.SKIPPED -> skipped += 1

                HabitOccurrenceStatus.PENDING -> {
                    if (occurrence.localDate < asOfDate) run = 0
                }
            }
        }

        return HabitStreak(
            current = run,
            best = best,
            completedInHistory = completed,
            skippedInHistory = skipped,
        )
    }
}
