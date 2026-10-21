package dev.system.app.calendar

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarCacheTest {
    private lateinit var database: CalendarDatabase
    private lateinit var dao: CalendarDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            CalendarDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.calendarDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun vantaUsersAndGoogleAccountsNeverShareCachedEvents() = runBlocking {
        dao.switchGoogleAccount("vanta-a", "google-account-a")
        dao.replaceCalendarList("vanta-a", "google-account-a", listOf(GoogleCalendarSummary("primary-a", "Personal", true)))
        dao.insertEvents(listOf(event("vanta-a", "google-account-a", "primary-a", "event-a")))

        dao.switchGoogleAccount("vanta-b", "google-account-a")
        dao.replaceCalendarList("vanta-b", "google-account-a", listOf(GoogleCalendarSummary("primary-a", "Personal", true)))
        dao.insertEvents(listOf(event("vanta-b", "google-account-a", "primary-a", "event-b")))
        dao.switchGoogleAccount("vanta-a", "google-account-b")
        dao.replaceCalendarList("vanta-a", "google-account-b", listOf(GoogleCalendarSummary("primary-b", "Work", true)))

        assertTrue(events("vanta-a").isEmpty())
        assertEquals(listOf("event-b"), events("vanta-b").map(CalendarEventEntity::externalEventId))
    }

    @Test
    fun selectedCalendarChoiceSurvivesCalendarRefresh() = runBlocking {
        dao.switchGoogleAccount("vanta-user", "google-account")
        val remote = listOf(
            GoogleCalendarSummary("primary", "Personal", true),
            GoogleCalendarSummary("college", "College", false),
        )
        dao.replaceCalendarList("vanta-user", "google-account", remote)
        dao.setCalendarSelected("vanta-user", "google-account", "college", true)

        dao.replaceCalendarList("vanta-user", "google-account", remote)

        assertEquals(setOf("primary", "college"), dao.selectedCalendars("vanta-user", "google-account").map { it.calendarId }.toSet())
    }

    @Test
    fun accountSwitchDropsPriorGoogleCacheButRetainsOwnerBoundary() = runBlocking {
        dao.switchGoogleAccount("vanta-user", "google-account-a")
        dao.replaceCalendarList("vanta-user", "google-account-a", listOf(GoogleCalendarSummary("primary-a", "A", true)))
        dao.insertEvents(listOf(event("vanta-user", "google-account-a", "primary-a", "event-a")))

        dao.switchGoogleAccount("vanta-user", "google-account-b")
        dao.replaceCalendarList("vanta-user", "google-account-b", listOf(GoogleCalendarSummary("primary-b", "B", true)))

        assertTrue(events("vanta-user").isEmpty())
        assertTrue(dao.calendars("vanta-user", "google-account-a").isEmpty())
        assertEquals("google-account-b", dao.syncState("vanta-user")?.googleAccountId)
    }

    @Test
    fun rangeQueryIncludesOverlappingTimedAndAllDayBlocksOnly() = runBlocking {
        dao.switchGoogleAccount("vanta-user", "google-account")
        dao.replaceCalendarList("vanta-user", "google-account", listOf(GoogleCalendarSummary("primary", "Personal", true)))
        dao.insertEvents(
            listOf(
                event("vanta-user", "google-account", "primary", "overlap-before").copy(startEpochMillis = 500, endEpochMillis = 1_500),
                event("vanta-user", "google-account", "primary", "starts-at-end").copy(startEpochMillis = 2_000, endEpochMillis = 3_000),
                allDay("inside-day", "2026-10-21", "2026-10-22"),
                allDay("previous-day", "2026-10-20", "2026-10-21"),
            ),
        )

        val rows = dao.observeCurrentEvents(
            ownerUserId = "vanta-user",
            rangeStartMillis = 1_000,
            rangeEndMillis = 2_000,
            rangeStartDate = "2026-10-21",
            rangeEndDate = "2026-10-22",
        ).first()

        assertEquals(setOf("overlap-before", "inside-day"), rows.map(CalendarEventEntity::externalEventId).toSet())
    }

    private suspend fun events(ownerUserId: String) = dao.observeCurrentEvents(
        ownerUserId = ownerUserId,
        rangeStartMillis = 0,
        rangeEndMillis = Long.MAX_VALUE,
        rangeStartDate = "2026-01-01",
        rangeEndDate = "2030-01-01",
    ).first()

    private fun event(owner: String, account: String, calendar: String, id: String) = CalendarEventEntity(
        ownerUserId = owner,
        googleAccountId = account,
        calendarId = calendar,
        externalEventId = id,
        title = id,
        allDay = false,
        startEpochMillis = 1_000,
        endEpochMillis = 2_000,
        startDate = null,
        endDateExclusive = null,
        cachedAt = 1,
    )

    private fun allDay(id: String, start: String, end: String) = CalendarEventEntity(
        ownerUserId = "vanta-user",
        googleAccountId = "google-account",
        calendarId = "primary",
        externalEventId = id,
        title = id,
        allDay = true,
        startEpochMillis = null,
        endEpochMillis = null,
        startDate = start,
        endDateExclusive = end,
        cachedAt = 1,
    )
}
