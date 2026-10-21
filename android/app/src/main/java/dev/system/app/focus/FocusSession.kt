package dev.system.app.focus

import dev.system.app.data.CachedOccurrence

/** The lifecycle state of a locally recorded focus session. */
enum class FocusSessionStatus {
    ACTIVE,
    COMPLETED,
    ENDED_EARLY,
}

/**
 * A focus session is measured from wall-clock timestamps so it remains accurate when the
 * process is recreated or the app is backgrounded. This model is deliberately separate from
 * the occurrence completion state: finishing a focus session never completes its task itself.
 */
data class FocusSession(
    val userId: String,
    val occurrenceId: String,
    val occurrenceTitle: String,
    val plannedDurationSeconds: Long,
    val startedAtEpochMillis: Long,
    val status: FocusSessionStatus = FocusSessionStatus.ACTIVE,
    val finishedAtEpochMillis: Long? = null,
) {
    init {
        require(userId.isNotBlank()) { "A focus session must belong to a user." }
        require(occurrenceId.isNotBlank()) { "A focus session must refer to an occurrence." }
        require(plannedDurationSeconds in 1..MAX_PLANNED_DURATION_SECONDS) {
            "Planned focus duration must be between one second and 24 hours."
        }
        require(startedAtEpochMillis >= 0L) { "Session start time cannot be negative." }
        require(startedAtEpochMillis <= Long.MAX_VALUE - plannedDurationMillis) {
            "Session start time is too large to calculate its planned end."
        }
        when (status) {
            FocusSessionStatus.ACTIVE -> require(finishedAtEpochMillis == null) {
                "An active session cannot have a finish time."
            }
            FocusSessionStatus.COMPLETED, FocusSessionStatus.ENDED_EARLY -> require(
                finishedAtEpochMillis != null && finishedAtEpochMillis >= startedAtEpochMillis,
            ) {
                "A finished session must have a finish time at or after its start."
            }
        }
    }

    val plannedDurationMillis: Long get() = plannedDurationSeconds * MILLIS_PER_SECOND

    fun actualElapsedMillis(nowEpochMillis: Long): Long {
        val end = (finishedAtEpochMillis ?: nowEpochMillis).coerceAtLeast(startedAtEpochMillis)
        return end - startedAtEpochMillis
    }

    fun remainingMillis(nowEpochMillis: Long): Long {
        val plannedEnd = startedAtEpochMillis + plannedDurationMillis
        return (plannedEnd - nowEpochMillis).coerceAtLeast(0L)
    }

    fun complete(finishedAtEpochMillis: Long): FocusSession = finish(
        status = FocusSessionStatus.COMPLETED,
        finishedAtEpochMillis = finishedAtEpochMillis,
    )

    fun endEarly(finishedAtEpochMillis: Long): FocusSession = finish(
        status = FocusSessionStatus.ENDED_EARLY,
        finishedAtEpochMillis = finishedAtEpochMillis,
    )

    private fun finish(status: FocusSessionStatus, finishedAtEpochMillis: Long): FocusSession {
        check(this.status == FocusSessionStatus.ACTIVE) { "Only an active session can be finished." }
        return copy(
            status = status,
            finishedAtEpochMillis = finishedAtEpochMillis.coerceAtLeast(startedAtEpochMillis),
        )
    }

    companion object {
        const val DEFAULT_PLANNED_DURATION_SECONDS = 25L * 60L
        const val MAX_PLANNED_DURATION_SECONDS = 24L * 60L * 60L
        private const val MILLIS_PER_SECOND = 1_000L

        /** Uses the task's valid duration when available, otherwise a 25-minute focus block. */
        fun plannedDurationSeconds(occurrence: CachedOccurrence): Long {
            val durationMinutes = occurrence.durationMinutes
                ?.takeIf { it in 5..1_440 }
                ?: return DEFAULT_PLANNED_DURATION_SECONDS
            return durationMinutes * 60L
        }
    }
}
