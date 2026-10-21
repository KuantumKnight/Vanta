package dev.system.app.ui.quickcapture

import java.time.LocalDate
import java.time.LocalTime

/** Root-to-dashboard launch request for widget and other external Quick Capture entry points. */
internal data class QuickCaptureLaunchRequest(
    val token: Long,
    val date: LocalDate,
    val source: String? = null,
)

/** A simple, deterministic result from the supported Quick Capture phrase patterns. */
internal data class QuickCaptureParseResult(
    val title: String,
    val date: LocalDate?,
    val startTime: LocalTime?,
    val durationMinutes: Int?,
    val recognizedSchedule: Boolean,
)

/**
 * Extracts only explicit `today`, `tomorrow`, clock times, and `for N min/hours` phrases.
 * A category with multiple matches is left untouched so the parser does not choose silently.
 */
internal fun parseQuickCapturePhrase(
    input: String,
    referenceDate: LocalDate,
): QuickCaptureParseResult {
    val dateMatches = DATE_PATTERN.findAll(input).toList()
    val timeMatches = TIME_PATTERN.findAll(input).toList()
    val durationMatches = DURATION_PATTERN.findAll(input).toList()

    val dateMatch = dateMatches.singleOrNull()
    val timeMatch = timeMatches.singleOrNull()
    val durationMatch = durationMatches.singleOrNull()
    val recognized = dateMatch != null || timeMatch != null || durationMatch != null
    if (!recognized) {
        return QuickCaptureParseResult(input.trim(), null, null, null, recognizedSchedule = false)
    }

    var remaining = input
    listOfNotNull(dateMatch, timeMatch, durationMatch)
        .sortedByDescending { it.range.first }
        .forEach { match ->
            remaining = remaining.replaceRange(match.range, " ")
        }
    if (timeMatch != null) remaining = remaining.replace(TRAILING_AT_CONNECTOR_PATTERN, " ")
    if (dateMatch != null) remaining = remaining.replace(TRAILING_ON_CONNECTOR_PATTERN, " ")
    val cleanedTitle = remaining
        .replace(WHITESPACE_PATTERN, " ")
        .trim(' ', ',', '.', '-', '–', '—')
        .trim()

    val explicitDate = dateMatch?.let { match ->
        when (match.value.lowercase()) {
            "today" -> referenceDate
            "tomorrow" -> referenceDate.plusDays(1)
            else -> null
        }
    }
    val startTime = timeMatch?.let(::parseTime)
    val durationMinutes = durationMatch?.let(::parseDurationMinutes)
    val date = explicitDate ?: if (startTime != null) referenceDate else null

    // If parsing would remove the entire title, preserve the phrase so an empty title is never
    // silently turned into a task. The user can still fill the structured fields manually.
    val title = cleanedTitle.ifBlank { input.trim() }
    return QuickCaptureParseResult(
        title = title,
        date = date,
        startTime = startTime,
        durationMinutes = durationMinutes,
        recognizedSchedule = true,
    )
}

private fun parseTime(match: MatchResult): LocalTime? = runCatching {
    val hour24 = match.groupValues[4]
    if (hour24.isNotEmpty()) {
        return@runCatching LocalTime.of(hour24.toInt(), match.groupValues[5].toInt())
    }

    val hour12 = match.groupValues[1].toInt()
    val minute = match.groupValues[2].takeIf(String::isNotEmpty)?.toInt() ?: 0
    val meridiem = match.groupValues[3].replace(".", "").lowercase()
    val normalizedHour = when {
        meridiem == "am" && hour12 == 12 -> 0
        meridiem == "pm" && hour12 != 12 -> hour12 + 12
        else -> hour12
    }
    LocalTime.of(normalizedHour, minute)
}.getOrNull()

private fun parseDurationMinutes(match: MatchResult): Int? {
    val amount = match.groupValues[1].toIntOrNull() ?: return null
    val unit = match.groupValues[2].lowercase()
    val minutes = if (unit.startsWith("h")) amount * 60 else amount
    return minutes
}

private val DATE_PATTERN = Regex("\\b(today|tomorrow)\\b", RegexOption.IGNORE_CASE)
private val TIME_PATTERN = Regex(
    "\\b(?:(1[0-2]|0?[1-9])(?::([0-5]\\d))?\\s*([ap]\\.?m\\.?)|([01]?\\d|2[0-3]):([0-5]\\d))\\b",
    RegexOption.IGNORE_CASE,
)
private val DURATION_PATTERN = Regex("\\bfor\\s+(\\d{1,4})\\s*(min(?:ute)?s?|h(?:ou)?rs?|hours?)\\b", RegexOption.IGNORE_CASE)
private val TRAILING_AT_CONNECTOR_PATTERN = Regex("\\bat\\s*$", RegexOption.IGNORE_CASE)
private val TRAILING_ON_CONNECTOR_PATTERN = Regex("\\bon\\s*$", RegexOption.IGNORE_CASE)
private val WHITESPACE_PATTERN = Regex("\\s+")
