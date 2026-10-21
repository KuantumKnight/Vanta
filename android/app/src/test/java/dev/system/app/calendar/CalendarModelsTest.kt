package dev.system.app.calendar

import dev.system.app.planner.ScheduleSource
import dev.system.app.planner.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CalendarModelsTest {
    @Test
    fun timedCalendarEventMapsToReadOnlyHardBlock() {
        val start = Instant.parse("2026-10-21T08:00:00Z")
        val event = GoogleCalendarEvent(
            externalEventId = "evt-private-id",
            calendarId = "person@example.com",
            title = "Operating Systems",
            start = GoogleCalendarTime.Timed(start),
            end = GoogleCalendarTime.Timed(start.plusSeconds(3600)),
        )

        val block = event.toDisplayBlock("opaque-account-key", ZoneId.of("Asia/Kolkata"))

        assertNotNull(block)
        assertEquals(start, block?.start)
        assertEquals(start.plusSeconds(3600), block?.end)
        assertEquals(ScheduleSource.GOOGLE_CALENDAR, block?.source)
        assertEquals(ScheduleType.HARD, block?.scheduleType)
        assertEquals(false, block?.editable)
        assertFalse(block!!.id.contains("person@example.com"))
        assertFalse(block.id.contains("evt-private-id"))
    }

    @Test
    fun allDayBlockUsesExclusiveEndDateInDisplayZoneIncludingDst() {
        val row = CalendarEventEntity(
            ownerUserId = "vanta-user",
            googleAccountId = "opaque-account-key",
            calendarId = "calendar-id",
            externalEventId = "event-id",
            title = "Holiday",
            allDay = true,
            startEpochMillis = null,
            endEpochMillis = null,
            startDate = "2026-03-08",
            endDateExclusive = "2026-03-09",
            cachedAt = 10,
        )

        val block = row.toScheduleBlock(ZoneId.of("America/Los_Angeles"))

        assertNotNull(block)
        assertEquals(23L, Duration.between(block!!.start, block.end).toHours())
        assertTrue(block.allDay)
        assertFalse(block.editable)
    }

    @Test
    fun malformedAndZeroLengthCachedIntervalsAreNotDisplayed() {
        val malformed = CalendarEventEntity(
            ownerUserId = "vanta-user", googleAccountId = "account", calendarId = "calendar", externalEventId = "event",
            title = "Bad", allDay = false, startEpochMillis = 20, endEpochMillis = 20,
            startDate = null, endDateExclusive = null, cachedAt = 0,
        )
        assertNull(malformed.toScheduleBlock(ZoneId.of("UTC")))
    }

    @Test
    fun cacheRangeIsBoundedInPlanningTimezone() {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = Instant.parse("2026-10-21T15:30:00Z")

        val range = CalendarRange.bounded(now, zone)

        assertEquals(LocalDate.parse("2026-10-14"), range.start.atZone(zone).toLocalDate())
        assertEquals(LocalDate.parse("2026-12-21"), range.endExclusive.atZone(zone).toLocalDate())
        assertEquals("Asia/Kolkata", range.zoneId.id)
    }

    @Test
    fun statusReportsMissingOrExpiredCacheAsStale() {
        assertTrue(CalendarSyncStatus(false, null, null, null).isStale(10_000))
        assertFalse(CalendarSyncStatus(true, "opaque", 10_000, null).isStale(10_001))
        assertTrue(CalendarSyncStatus(true, "opaque", 0, null).isStale(CALENDAR_CACHE_FRESH_MILLIS))
    }

    @Test
    fun accountKeyIsStableOpaqueAndChangesWithPrimaryCalendar() {
        val first = googleCalendarAccountKey("person@example.com")

        assertEquals(first, googleCalendarAccountKey("PERSON@example.com"))
        assertTrue(first != googleCalendarAccountKey("work@example.com"))
        assertFalse(first.contains("person@example.com"))
    }
}
