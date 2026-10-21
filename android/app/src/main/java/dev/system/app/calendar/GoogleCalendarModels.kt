package dev.system.app.calendar

import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.ScheduleSource
import dev.system.app.planner.ScheduleType
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar summaries omit descriptions, attendee details, and other unrelated Google fields. */
data class GoogleCalendarSummary(
    val id: String,
    val name: String,
    val isPrimary: Boolean,
)

sealed interface GoogleCalendarTime {
    data class Timed(val instant: Instant) : GoogleCalendarTime
    data class AllDay(val date: LocalDate) : GoogleCalendarTime
}

/** Only fields needed to display busy time are kept from Google's event resource. */
data class GoogleCalendarEvent(
    val externalEventId: String,
    val calendarId: String,
    val title: String,
    val start: GoogleCalendarTime,
    val end: GoogleCalendarTime,
)

internal fun GoogleCalendarEvent.toCacheEntity(ownerUserId: String, googleAccountId: String, cachedAt: Long) =
    CalendarEventEntity(
        ownerUserId = ownerUserId,
        googleAccountId = googleAccountId,
        calendarId = calendarId,
        externalEventId = externalEventId,
        title = title.ifBlank { "Busy" },
        allDay = start is GoogleCalendarTime.AllDay && end is GoogleCalendarTime.AllDay,
        startEpochMillis = (start as? GoogleCalendarTime.Timed)?.instant?.toEpochMilli(),
        endEpochMillis = (end as? GoogleCalendarTime.Timed)?.instant?.toEpochMilli(),
        startDate = (start as? GoogleCalendarTime.AllDay)?.date?.toString(),
        endDateExclusive = (end as? GoogleCalendarTime.AllDay)?.date?.toString(),
        cachedAt = cachedAt,
    )

/** Convert an event to the common planner block shape in the requested display zone. */
fun CalendarEventEntity.toScheduleBlock(zoneId: ZoneId): ScheduleBlock? {
    val interval = if (allDay) {
        val firstDay = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val dayAfterLast = endDateExclusive?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        if (!dayAfterLast.isAfter(firstDay)) return null
        firstDay.atStartOfDay(zoneId).toInstant() to dayAfterLast.atStartOfDay(zoneId).toInstant()
    } else {
        val first = startEpochMillis?.let(Instant::ofEpochMilli) ?: return null
        val last = endEpochMillis?.let(Instant::ofEpochMilli) ?: return null
        first to last
    }
    if (!interval.second.isAfter(interval.first)) return null
    return ScheduleBlock(
        id = "google:${stableCalendarBlockId(googleAccountId, calendarId, externalEventId)}",
        source = ScheduleSource.GOOGLE_CALENDAR,
        title = title,
        start = interval.first,
        end = interval.second,
        scheduleType = ScheduleType.HARD,
        editable = false,
        allDay = allDay,
    )
}

private fun stableCalendarBlockId(googleAccountId: String, calendarId: String, eventId: String): String {
    val raw = "$googleAccountId\u0000$calendarId\u0000$eventId".toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { byte -> "%02x".format(byte) }
}

internal fun GoogleCalendarEvent.toDisplayBlock(googleAccountId: String, zoneId: ZoneId): ScheduleBlock? {
    val cached = toCacheEntity(ownerUserId = "", googleAccountId = googleAccountId, cachedAt = 0L)
    return cached.toScheduleBlock(zoneId)
}
