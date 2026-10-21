package dev.system.app.calendar

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class GoogleCalendarApiClientTest {
    @Test
    fun calendarListLoadsPagesWithoutKeepingUnneededFields() = runBlocking {
        val requestedUrls = mutableListOf<URL>()
        val client = GoogleCalendarApiClient { url ->
            requestedUrls += url
            val body = if (url.query.contains("pageToken")) {
                """{"items":[{"id":"college","summary":"College","primary":false,"description":"ignored"}]}"""
            } else {
                """{"items":[{"id":"person@example.com","summary":"Personal","primary":true}],"nextPageToken":"next"}"""
            }
            FakeConnection(url, 200, body)
        }

        val calendars = client.listCalendars("short-lived-token")

        assertEquals(2, calendars.size)
        assertEquals("Personal", calendars[0].name)
        assertTrue(calendars[0].isPrimary)
        assertEquals("College", calendars[1].name)
        assertEquals(2, requestedUrls.size)
    }

    @Test
    fun eventsAreExpandedAndOnlyRequiredFieldsAreRetained() = runBlocking {
        val requestedUrls = mutableListOf<URL>()
        val body = """
            {"items":[
              {"id":"instance_1","summary":"Class","status":"confirmed","start":{"dateTime":"2026-10-21T09:00:00+05:30"},"end":{"dateTime":"2026-10-21T10:00:00+05:30"},"description":"Do not retain","attendees":[{"email":"private@example.com"}]},
              {"id":"floating-local","summary":"Local zone event","start":{"dateTime":"2026-10-21T11:00:00","timeZone":"Asia/Kolkata"},"end":{"dateTime":"2026-10-21T12:00:00","timeZone":"Asia/Kolkata"}},
              {"id":"holiday","summary":"Holiday","start":{"date":"2026-10-22"},"end":{"date":"2026-10-23"}},
              {"id":"cancelled","status":"cancelled","start":{"date":"2026-10-22"},"end":{"date":"2026-10-23"}}
            ]}
        """.trimIndent()
        val client = GoogleCalendarApiClient { url ->
            requestedUrls += url
            FakeConnection(url, 200, body)
        }
        val range = CalendarRange(
            start = Instant.parse("2026-10-20T00:00:00Z"),
            endExclusive = Instant.parse("2026-10-24T00:00:00Z"),
            zoneId = ZoneId.of("Asia/Kolkata"),
        )

        val events = client.listEvents("short-lived-token", "college@example.com", range)

        assertEquals(3, events.size)
        assertEquals(Instant.parse("2026-10-21T03:30:00Z"), (events[0].start as GoogleCalendarTime.Timed).instant)
        assertEquals("Class", events[0].title)
        assertEquals(Instant.parse("2026-10-21T05:30:00Z"), (events[1].start as GoogleCalendarTime.Timed).instant)
        assertEquals(GoogleCalendarTime.AllDay(java.time.LocalDate.parse("2026-10-22")), events[2].start)
        assertTrue(requestedUrls.single().path.contains("college%40example.com"))
        assertTrue(requestedUrls.single().query.contains("singleEvents=true"))
        assertTrue(requestedUrls.single().query.contains("orderBy=startTime"))
    }

    @Test
    fun unauthorizedResponseIsReportedWithoutReadingOrLoggingResponseBody() = runBlocking {
        val client = GoogleCalendarApiClient { url -> FakeConnection(url, 401, "secret-looking-error") }

        val error = runCatching { client.listCalendars("expired-token") }.exceptionOrNull()

        assertTrue(error is CalendarApiException)
        assertEquals(CalendarApiFailure.UNAUTHORIZED, (error as CalendarApiException).failure)
        assertEquals("Google Calendar access needs attention.", error.message)
    }

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val body: String,
    ) : HttpURLConnection(url) {
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        override fun getResponseCode(): Int = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
}
