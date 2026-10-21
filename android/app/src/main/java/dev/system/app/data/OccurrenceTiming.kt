package dev.system.app.data

import java.time.Duration
import java.time.Instant

/** Start/end calculations shared by schedule features, independent of UI or sync code. */
fun CachedOccurrence.startInstant(): Instant? = scheduledFor?.let { value ->
    runCatching { Instant.parse(value) }.getOrNull()
}

fun CachedOccurrence.duration(): Duration? = durationMinutes
    ?.takeIf { it in MIN_TASK_DURATION_MINUTES..MAX_TASK_DURATION_MINUTES }
    ?.let { Duration.ofMinutes(it.toLong()) }

/** All-day rows have a calendar date, not a timed interval. */
fun CachedOccurrence.endInstant(): Instant? {
    if (allDay) return null
    val start = startInstant() ?: return null
    val duration = duration() ?: return null
    return start.plus(duration)
}

const val MIN_TASK_DURATION_MINUTES = 5
const val MAX_TASK_DURATION_MINUTES = 24 * 60
