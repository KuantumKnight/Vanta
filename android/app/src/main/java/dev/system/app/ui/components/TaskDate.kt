package dev.system.app.ui.components

import dev.system.app.data.CachedOccurrence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val TaskTimeZone: ZoneId = ZoneId.of("Asia/Kolkata")

internal fun CachedOccurrence.taskDate(): LocalDate? =
    scheduledFor?.let { runCatching { Instant.parse(it).atZone(TaskTimeZone).toLocalDate() }.getOrNull() }

internal fun formatClock(instant: String): String =
    runCatching { Instant.parse(instant).atZone(TaskTimeZone).format(DateTimeFormatter.ofPattern("h:mm a")) }
        .getOrDefault("recently")
