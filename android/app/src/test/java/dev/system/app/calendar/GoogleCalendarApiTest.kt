package dev.system.app.calendar

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCalendarApiTest {
    @Test
    fun requestsExpandedPagesAndParsesTimedAllDayAndOverlappingEvents() = runBlocking {
        val requests = mutableListOf<URL>()
        val responses = ArrayDeque(
            listOf(
                """
                {
                  "items": [
                    {"id":"class","summary":"Class","start":{"dateTime":"2026-10-21T09:00:00+05:30"},"end":{"dateTime":"2026-10-21T10:00:00+05:30"}},
                    {"id":"overlap","summary":"Appointment","start":{"dateTime":"2026-10-21T09:30:00+05:30"},"end":{"dateTime":"2026-10-21T10:30:00+05:30"}},
                    {"id":"holiday","summary":"Holiday","start":{"date":"2026-10-21"},"end":{"date":"2026-10-22"}}
                  ],
                  "nextPageToken":"page-2"
                }
                """.trimIndent(),
                """
                {
                  "items": [
                    {"id":"recurring-instance","summary":"Study group","start":{"dateTime":"2026-10-21T18:00:00+05:30"},"end":{"dateTime":"2026-10-21T19:00:00+05:30"}}
                  ]
                }
                """.trimIndent(),
            ),
        )
        val api = GoogleCalendarApiClient { url ->
            requests += url
            FakeHttpURLConnection(url, body = responses.removeFirst())
        }

        val events = api.listEvents(
            accessToken = "ephemeral-token",
            calendarId = "personal@example.com",
            range = CalendarRange(
                start = Instant.parse("2026-10-20T18:30:00Z"),
                endExclusive = Instant.parse("2026-10-27T18:30:00Z"),
                zoneId = ZoneId.of("Asia/Kolkata"),
            ),
        )

        assertEquals(listOf("class", "overlap", "holiday", "recurring-instance"), events.map { it.externalEventId })
        assertEquals(Instant.parse("2026-10-21T03:30:00Z"), (events[0].start as GoogleCalendarTime.Timed).instant)
        assertEquals(GoogleCalendarTime.AllDay(java.time.LocalDate.parse("2026-10-21")), events[2].start)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.path.contains("personal%40example.com") })
        assertTrue(requests.all { it.query.contains("singleEvents=true") && it.query.contains("timeZone=Asia%2FKolkata") })
        assertTrue(requests[1].query.contains("pageToken=page-2"))
    }

    @Test
    fun emptyCalendarProducesAnEmptyEventList() = runBlocking {
        val api = GoogleCalendarApiClient { url -> FakeHttpURLConnection(url, body = "{\"items\":[]}") }

        val events = api.listEvents(
            accessToken = "ephemeral-token",
            calendarId = "primary",
            range = CalendarRange(
                Instant.parse("2026-10-21T00:00:00Z"),
                Instant.parse("2026-10-22T00:00:00Z"),
                ZoneId.of("UTC"),
            ),
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun expiredAuthorizationIsReportedWithoutReadingResponseBody() = runBlocking {
        var bodyRead = false
        val api = GoogleCalendarApiClient { url ->
            FakeHttpURLConnection(url, status = HttpURLConnection.HTTP_UNAUTHORIZED) {
                bodyRead = true
                "{}"
            }
        }

        val failure = runCatching {
            api.listEvents(
                accessToken = "expired-token",
                calendarId = "primary",
                range = CalendarRange(
                    Instant.parse("2026-10-21T00:00:00Z"),
                    Instant.parse("2026-10-22T00:00:00Z"),
                    ZoneId.of("UTC"),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is CalendarApiException)
        assertEquals(CalendarApiFailure.UNAUTHORIZED, (failure as CalendarApiException).failure)
        assertFalse(bodyRead)
    }

    private class FakeHttpURLConnection(
        url: URL,
        private val status: Int = HttpURLConnection.HTTP_OK,
        private val responseBody: () -> String,
    ) : HttpURLConnection(url) {
        constructor(url: URL, status: Int = HttpURLConnection.HTTP_OK, body: String) : this(url, status, { body })

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getInputStream() = ByteArrayInputStream(responseBody().toByteArray(Charsets.UTF_8))
    }
}
