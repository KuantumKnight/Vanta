package dev.system.app.eveningreview

import dev.system.app.data.CachedOccurrence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The user's explicit choice for an unresolved occurrence in the day review. */
internal enum class ReviewAction {
    MOVE_TO_TOMORROW,
    KEEP_CURRENT_DEADLINE,
    RESCHEDULE,
    SKIP,
}

/** Small, local UI state for the choices made during an open review. */
internal data class ReviewDecisions(
    val byOccurrenceId: Map<String, ReviewAction> = emptyMap(),
) {
    fun choose(occurrenceId: String, action: ReviewAction): ReviewDecisions =
        copy(byOccurrenceId = byOccurrenceId + (occurrenceId to action))

    fun actionFor(occurrenceId: String): ReviewAction? = byOccurrenceId[occurrenceId]
}

/** A summary derived only from occurrence and focus data supplied by the local repository. */
internal data class EveningReviewSummary(
    val date: LocalDate,
    val planned: List<CachedOccurrence>,
    val completed: List<CachedOccurrence>,
    val remaining: List<CachedOccurrence>,
    val skipped: List<CachedOccurrence>,
    val focusMinutes: Long?,
) {
    val plannedCount: Int get() = planned.size
    val completedCount: Int get() = completed.size
    val remainingCount: Int get() = remaining.size
    val skippedCount: Int get() = skipped.size
}

/**
 * Summarizes one bounded day range. A skipped occurrence is shown separately and is not
 * described as completed or remaining. Malformed/undated rows are ignored.
 */
internal object EveningReviewCalculator {
    fun calculate(
        date: LocalDate,
        occurrences: List<CachedOccurrence>,
        focusMinutes: Long? = null,
        zone: ZoneId = VANTA_REVIEW_ZONE,
    ): EveningReviewSummary {
        val planned = occurrences.asSequence()
            .filter { occurrence -> occurrence.localDate(zone) == date }
            .distinctBy(CachedOccurrence::id)
            .sortedWith(compareBy<CachedOccurrence>({ it.scheduledFor.orEmpty() }, { it.title.lowercase() }))
            .toList()

        return EveningReviewSummary(
            date = date,
            planned = planned,
            completed = planned.filter { it.status == STATUS_COMPLETED },
            remaining = planned.filter { it.status == STATUS_PENDING },
            skipped = planned.filter { it.status == STATUS_SKIPPED },
            focusMinutes = focusMinutes?.coerceAtLeast(0L),
        )
    }

    private fun CachedOccurrence.localDate(zone: ZoneId): LocalDate? =
        scheduledFor?.let { value -> runCatching { Instant.parse(value).atZone(zone).toLocalDate() }.getOrNull() }

    private const val STATUS_COMPLETED = "completed"
    private const val STATUS_PENDING = "pending"
    private const val STATUS_SKIPPED = "skipped"
}

internal val VANTA_REVIEW_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
