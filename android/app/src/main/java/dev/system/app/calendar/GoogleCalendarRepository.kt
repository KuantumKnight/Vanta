package dev.system.app.calendar

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

data class CalendarSyncStatus(
    val connected: Boolean,
    val googleAccountKey: String?,
    val lastSuccessAt: Long?,
    val lastErrorCode: String?,
) {
    fun isStale(nowMillis: Long): Boolean = lastSuccessAt == null || nowMillis - lastSuccessAt >= CALENDAR_CACHE_FRESH_MILLIS
}

data class CalendarRefreshResult(
    val googleAccountKey: String,
    val calendarsRefreshed: Int,
    val eventsCached: Int,
    val synchronizedAt: Instant,
)

/** Local cache and refresh coordinator. Caller provides a short-lived access token and never stores it. */
class GoogleCalendarRepository(
    context: Context,
    private val api: GoogleCalendarApiClient = GoogleCalendarApiClient(),
) {
    private val database = CalendarDatabase.get(context.applicationContext)
    private val dao = database.calendarDao()

    fun observeCalendars(ownerUserId: String): Flow<List<CalendarCatalogEntity>> =
        dao.observeCurrentCalendars(ownerUserId)

    fun observeScheduleBlocks(ownerUserId: String, range: CalendarRange): Flow<List<dev.system.app.planner.ScheduleBlock>> =
        dao.observeCurrentEvents(
            ownerUserId = ownerUserId,
            rangeStartMillis = range.startEpochMillis,
            rangeEndMillis = range.endEpochMillis,
            rangeStartDate = range.startDate,
            rangeEndDate = range.endDate,
        ).map { rows -> rows.mapNotNull { it.toScheduleBlock(range.zoneId) } }

    fun observeSyncStatus(ownerUserId: String): Flow<CalendarSyncStatus> = dao.observeSyncState(ownerUserId).map { state ->
        CalendarSyncStatus(
            connected = state?.connected == true,
            googleAccountKey = state?.googleAccountId,
            lastSuccessAt = state?.lastSuccessAt,
            lastErrorCode = state?.lastErrorCode,
        )
    }

    suspend fun isCacheFresh(ownerUserId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val state = dao.syncState(ownerUserId) ?: return false
        return state.connected && state.lastSuccessAt?.let { nowMillis - it < CALENDAR_CACHE_FRESH_MILLIS } == true
    }

    /** Fetches only a bounded window and expands recurrence on Google's side (`singleEvents=true`). */
    suspend fun refresh(
        ownerUserId: String,
        accessToken: String,
        zoneId: ZoneId,
        now: Instant = Instant.now(),
    ): CalendarRefreshResult {
        require(ownerUserId.isNotBlank())
        val nowMillis = now.toEpochMilli()
        val range = CalendarRange.bounded(now, zoneId)
        try {
            val remoteCalendars = api.listCalendars(accessToken)
            val primaryId = remoteCalendars.firstOrNull(GoogleCalendarSummary::isPrimary)?.id
                ?: throw CalendarApiException(CalendarApiFailure.INVALID_RESPONSE)
            val accountKey = googleCalendarAccountKey(primaryId)

            database.withTransaction {
                dao.switchGoogleAccount(ownerUserId, accountKey)
                dao.replaceCalendarList(ownerUserId, accountKey, remoteCalendars)
                if (remoteCalendars.isNotEmpty()) {
                    dao.deleteEventsOutsideCalendarList(ownerUserId, accountKey, remoteCalendars.map(GoogleCalendarSummary::id))
                }
            }

            val selected = dao.selectedCalendars(ownerUserId, accountKey)
            var eventCount = 0
            for (calendar in selected) {
                val fetched = api.listEvents(accessToken, calendar.calendarId, range)
                    .map { it.toCacheEntity(ownerUserId, accountKey, nowMillis) }
                dao.replaceEventsInRange(
                    ownerUserId = ownerUserId,
                    googleAccountId = accountKey,
                    calendarId = calendar.calendarId,
                    rangeStartMillis = range.startEpochMillis,
                    rangeEndMillis = range.endEpochMillis,
                    rangeStartDate = range.startDate,
                    rangeEndDate = range.endDate,
                    events = fetched,
                )
                eventCount += fetched.size
            }
            dao.pruneEventsOutsideRange(
                ownerUserId, accountKey, range.startEpochMillis, range.endEpochMillis, range.startDate, range.endDate,
            )
            dao.pruneOldEvents(ownerUserId, accountKey, nowMillis - 90L * 24 * 60 * 60 * 1000)
            dao.saveSyncState(
                CalendarSyncStateEntity(
                    ownerUserId = ownerUserId,
                    googleAccountId = accountKey,
                    connected = true,
                    lastSuccessAt = nowMillis,
                    lastErrorCode = null,
                ),
            )
            return CalendarRefreshResult(accountKey, selected.size, eventCount, now)
        } catch (failure: CalendarApiException) {
            recordFailure(ownerUserId, failure)
            throw failure
        }
    }

    suspend fun setCalendarSelected(ownerUserId: String, calendarId: String, selected: Boolean): Boolean {
        val accountKey = dao.syncState(ownerUserId)?.googleAccountId ?: return false
        if (dao.calendars(ownerUserId, accountKey).none { it.calendarId == calendarId }) return false
        dao.setCalendarSelected(ownerUserId, accountKey, calendarId, selected)
        return true
    }

    /** Interaction was needed while refreshing in the background; keep cached events and surface stale state. */
    suspend fun markAuthorizationRequired(ownerUserId: String) {
        val current = dao.syncState(ownerUserId)
        dao.saveSyncState(
            current?.copy(connected = false, lastErrorCode = "AUTHORIZATION_REQUIRED")
                ?: CalendarSyncStateEntity(
                    ownerUserId = ownerUserId,
                    googleAccountId = null,
                    connected = false,
                    lastSuccessAt = null,
                    lastErrorCode = "AUTHORIZATION_REQUIRED",
                ),
        )
    }

    /** Explicit user disconnect removes Google-derived local data but leaves all Vanta-owned data alone. */
    suspend fun disconnect(ownerUserId: String, authorization: GoogleCalendarAuthorization): Boolean {
        val revoked = runCatching { authorization.revokeAccess() }.isSuccess
        database.withTransaction {
            dao.deleteAllEvents(ownerUserId)
            dao.deleteAllCalendars(ownerUserId)
            dao.deleteSyncState(ownerUserId)
        }
        return revoked
    }

    private suspend fun recordFailure(ownerUserId: String, failure: CalendarApiException) {
        val existing = dao.syncState(ownerUserId)
        val requiresReconnect = failure.failure == CalendarApiFailure.UNAUTHORIZED ||
            failure.failure == CalendarApiFailure.FORBIDDEN
        dao.saveSyncState(
            CalendarSyncStateEntity(
                ownerUserId = ownerUserId,
                googleAccountId = existing?.googleAccountId,
                connected = existing?.connected == true && !requiresReconnect,
                lastSuccessAt = existing?.lastSuccessAt,
                lastErrorCode = failure.failure.name,
            ),
        )
    }

}

internal fun googleCalendarAccountKey(primaryCalendarId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(primaryCalendarId.trim().lowercase().toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
