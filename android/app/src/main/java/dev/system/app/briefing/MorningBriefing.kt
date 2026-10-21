package dev.system.app.briefing

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A small, local projection of one scheduled task or habit occurrence. */
internal data class BriefingOccurrence(
    val taskId: String,
    val title: String,
    val scheduledFor: String?,
    val allDay: Boolean = false,
    val kind: String = "task",
    val durationMinutes: Int? = null,
    val priority: String? = null,
)

internal data class BriefingFirstItem(
    val title: String,
    val time: LocalTime?,
    val allDay: Boolean,
)

/** Values shown in the briefing. Planned minutes are present only when every timed item has a duration. */
internal data class MorningBriefing(
    val date: LocalDate,
    val scheduledItemCount: Int,
    val habitCount: Int,
    val plannedMinutes: Long?,
    val firstItem: BriefingFirstItem?,
    val importantTaskCount: Int?,
)

/** Builds a deterministic briefing from the bounded, already-local set of dated occurrences. */
internal object MorningBriefingBuilder {
    val defaultZone: ZoneId = ZoneId.of("Asia/Kolkata")

    fun build(
        date: LocalDate,
        occurrences: List<BriefingOccurrence>,
        zone: ZoneId = defaultZone,
        importantTaskCount: Int? = null,
    ): MorningBriefing {
        val todays = occurrences.mapNotNull { occurrence ->
            val instant = occurrence.scheduledFor
                ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
                ?: return@mapNotNull null
            if (instant.atZone(zone).toLocalDate() == date) occurrence to instant else null
        }

        val timedItems = todays.filterNot { (occurrence, _) -> occurrence.allDay }
        val plannedMinutes = timedItems
            .takeIf { items ->
                items.isNotEmpty() && items.all { (occurrence, _) ->
                    occurrence.durationMinutes?.let { it in 5..1440 } == true
                }
            }
            ?.sumOf { (occurrence, _) -> occurrence.durationMinutes!!.toLong() }

        val first = todays.minWithOrNull(
            compareBy<Pair<BriefingOccurrence, Instant>> { it.second }
                .thenBy { it.first.title.lowercase() }
                .thenBy { it.first.taskId },
        )?.let { (occurrence, instant) ->
            BriefingFirstItem(
                title = occurrence.title,
                time = if (occurrence.allDay) null else instant.atZone(zone).toLocalTime(),
                allDay = occurrence.allDay,
            )
        }

        val habits = todays.asSequence()
            .map { (occurrence, _) -> occurrence }
            .filter { it.kind == "habit" }
            .map { it.taskId }
            .toSet()
            .size

        return MorningBriefing(
            date = date,
            scheduledItemCount = todays.size,
            habitCount = habits,
            plannedMinutes = plannedMinutes,
            firstItem = first,
            importantTaskCount = importantTaskCount?.coerceAtLeast(0) ?: todays
                .asSequence()
                .map { it.first }
                .filter { it.kind == "task" }
                .count { it.priority?.equals("HIGH", ignoreCase = true) == true }
                .takeIf { todays.any { (occurrence, _) -> occurrence.kind == "task" && occurrence.priority != null } },
        )
    }
}
