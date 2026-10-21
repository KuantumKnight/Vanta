package dev.system.app.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Minimal REST reader. It asks only for fields shown in the schedule and never retains raw responses.
 * All network and JSON work runs off the main thread.
 */
class GoogleCalendarApiClient(
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun listCalendars(accessToken: String): List<GoogleCalendarSummary> = withContext(Dispatchers.IO) {
        requireToken(accessToken)
        val output = mutableListOf<GoogleCalendarSummary>()
        var pageToken: String? = null
        do {
            val parameters = linkedMapOf(
                "maxResults" to "250",
                "showDeleted" to "false",
                "fields" to "items(id,summary,primary),nextPageToken",
            )
            pageToken?.let { parameters["pageToken"] = it }
            val json = getJson("https://www.googleapis.com/calendar/v3/users/me/calendarList", parameters, accessToken)
            val items = json.optJSONArray("items")
            if (items != null) for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                output += GoogleCalendarSummary(
                    id = id,
                    name = item.optString("summary").ifBlank { id },
                    isPrimary = item.optBoolean("primary", false),
                )
            }
            pageToken = json.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (pageToken != null)
        output
    }

    suspend fun listEvents(
        accessToken: String,
        calendarId: String,
        range: CalendarRange,
    ): List<GoogleCalendarEvent> = withContext(Dispatchers.IO) {
        requireToken(accessToken)
        require(calendarId.isNotBlank())
        val output = mutableListOf<GoogleCalendarEvent>()
        var pageToken: String? = null
        do {
            val parameters = linkedMapOf(
                "singleEvents" to "true",
                "orderBy" to "startTime",
                "showDeleted" to "true",
                "maxResults" to "2500",
                "timeMin" to range.start.atZone(range.zoneId).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "timeMax" to range.endExclusive.atZone(range.zoneId).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "timeZone" to range.zoneId.id,
                "fields" to "items(id,summary,status,start(date,dateTime,timeZone),end(date,dateTime,timeZone)),nextPageToken",
            )
            pageToken?.let { parameters["pageToken"] = it }
            val url = "https://www.googleapis.com/calendar/v3/calendars/${encodePath(calendarId)}/events"
            val json = getJson(url, parameters, accessToken)
            val items = json.optJSONArray("items")
            if (items != null) for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                if (item.optString("status") == "cancelled") continue
                val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                val start = item.optJSONObject("start")?.toCalendarTime(range.zoneId) ?: continue
                val end = item.optJSONObject("end")?.toCalendarTime(range.zoneId) ?: continue
                if (!start.isSameKind(end) || !end.isAfter(start)) continue
                output += GoogleCalendarEvent(
                    externalEventId = id,
                    calendarId = calendarId,
                    title = item.optString("summary").ifBlank { "Busy" },
                    start = start,
                    end = end,
                )
            }
            pageToken = json.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (pageToken != null)
        output
    }

    private fun getJson(baseUrl: String, parameters: Map<String, String>, accessToken: String): JSONObject {
        val query = parameters.entries.joinToString("&") { (key, value) -> "${encodeQuery(key)}=${encodeQuery(value)}" }
        val connection = connectionFactory(URL("$baseUrl?$query"))
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) throw CalendarApiException(CalendarApiFailure.UNAUTHORIZED)
            if (status == HttpURLConnection.HTTP_FORBIDDEN) throw CalendarApiException(CalendarApiFailure.FORBIDDEN)
            if (status !in 200..299) throw CalendarApiException(CalendarApiFailure.HTTP_ERROR)
            val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            return JSONObject(body)
        } catch (failure: CalendarApiException) {
            throw failure
        } catch (failure: IOException) {
            throw CalendarApiException(CalendarApiFailure.NETWORK, failure)
        } catch (failure: RuntimeException) {
            throw CalendarApiException(CalendarApiFailure.INVALID_RESPONSE, failure)
        } finally {
            connection.disconnect()
        }
    }

    private fun requireToken(token: String) {
        require(token.isNotBlank()) { "Google authorization is required." }
    }

    private fun encodeQuery(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun encodePath(value: String): String = value.toByteArray(StandardCharsets.UTF_8).joinToString("") { byte ->
        val code = byte.toInt() and 0xff
        if (code in 'A'.code..'Z'.code || code in 'a'.code..'z'.code || code in '0'.code..'9'.code || code.toChar() in "-._~") code.toChar().toString()
        else "%%%02X".format(code)
    }

    private fun JSONObject.toCalendarTime(fallbackZone: ZoneId): GoogleCalendarTime? {
        val dateTime = optString("dateTime").takeIf(String::isNotBlank)
        if (dateTime != null) {
            val zone = optString("timeZone")
                .takeIf(String::isNotBlank)
                ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: fallbackZone
            val instant = runCatching { java.time.OffsetDateTime.parse(dateTime).toInstant() }
                .recoverCatching { java.time.LocalDateTime.parse(dateTime).atZone(zone).toInstant() }
                .getOrNull() ?: return null
            return GoogleCalendarTime.Timed(instant)
        }
        val date = optString("date").takeIf(String::isNotBlank) ?: return null
        return runCatching { GoogleCalendarTime.AllDay(LocalDate.parse(date)) }.getOrNull()
    }

    private fun GoogleCalendarTime.isSameKind(other: GoogleCalendarTime): Boolean =
        (this is GoogleCalendarTime.Timed && other is GoogleCalendarTime.Timed) ||
            (this is GoogleCalendarTime.AllDay && other is GoogleCalendarTime.AllDay)

    private fun GoogleCalendarTime.isAfter(other: GoogleCalendarTime): Boolean = when {
        this is GoogleCalendarTime.Timed && other is GoogleCalendarTime.Timed -> instant.isAfter(other.instant)
        this is GoogleCalendarTime.AllDay && other is GoogleCalendarTime.AllDay -> date.isAfter(other.date)
        else -> false
    }
}

enum class CalendarApiFailure {
    UNAUTHORIZED,
    FORBIDDEN,
    HTTP_ERROR,
    NETWORK,
    INVALID_RESPONSE,
}

class CalendarApiException(
    val failure: CalendarApiFailure,
    cause: Throwable? = null,
) : IOException(
    when (failure) {
        CalendarApiFailure.UNAUTHORIZED, CalendarApiFailure.FORBIDDEN -> "Google Calendar access needs attention."
        CalendarApiFailure.HTTP_ERROR -> "Google Calendar could not be refreshed."
        CalendarApiFailure.NETWORK -> "Google Calendar is unavailable offline."
        CalendarApiFailure.INVALID_RESPONSE -> "Google Calendar returned invalid data."
    },
    cause,
)
