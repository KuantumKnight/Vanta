package dev.system.app.calendar

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Entity(
    tableName = "google_calendar_catalog",
    primaryKeys = ["ownerUserId", "googleAccountId", "calendarId"],
    indices = [Index(value = ["ownerUserId", "googleAccountId", "selected"])],
)
data class CalendarCatalogEntity(
    val ownerUserId: String,
    val googleAccountId: String,
    val calendarId: String,
    val name: String,
    val isPrimary: Boolean,
    val selected: Boolean,
)

@Entity(
    tableName = "google_calendar_events",
    primaryKeys = ["ownerUserId", "googleAccountId", "calendarId", "externalEventId"],
    indices = [
        Index(value = ["ownerUserId", "googleAccountId", "startEpochMillis"]),
        Index(value = ["ownerUserId", "googleAccountId", "startDate"]),
        Index(value = ["ownerUserId", "googleAccountId", "calendarId"]),
    ],
)
data class CalendarEventEntity(
    val ownerUserId: String,
    val googleAccountId: String,
    val calendarId: String,
    val externalEventId: String,
    val title: String,
    val allDay: Boolean,
    val startEpochMillis: Long?,
    val endEpochMillis: Long?,
    val startDate: String?,
    /** Google uses an exclusive end date for all-day events. */
    val endDateExclusive: String?,
    val cachedAt: Long,
)

@Entity(tableName = "google_calendar_sync_state")
data class CalendarSyncStateEntity(
    @PrimaryKey val ownerUserId: String,
    val googleAccountId: String?,
    val connected: Boolean,
    val lastSuccessAt: Long?,
    val lastErrorCode: String?,
)

@Dao
internal interface CalendarDao {
    @Query("SELECT * FROM google_calendar_catalog WHERE ownerUserId = :ownerUserId AND googleAccountId = :googleAccountId ORDER BY isPrimary DESC, name COLLATE NOCASE ASC")
    suspend fun calendars(ownerUserId: String, googleAccountId: String): List<CalendarCatalogEntity>

    @Query("""
        SELECT * FROM google_calendar_catalog
        WHERE ownerUserId = :ownerUserId
          AND googleAccountId = (SELECT googleAccountId FROM google_calendar_sync_state WHERE ownerUserId = :ownerUserId LIMIT 1)
        ORDER BY isPrimary DESC, name COLLATE NOCASE ASC
    """)
    fun observeCurrentCalendars(ownerUserId: String): Flow<List<CalendarCatalogEntity>>

    @Query("SELECT * FROM google_calendar_catalog WHERE ownerUserId = :ownerUserId AND googleAccountId = :googleAccountId AND selected = 1 ORDER BY isPrimary DESC, name COLLATE NOCASE ASC")
    suspend fun selectedCalendars(ownerUserId: String, googleAccountId: String): List<CalendarCatalogEntity>

    @Query("DELETE FROM google_calendar_catalog WHERE ownerUserId = :ownerUserId AND googleAccountId = :googleAccountId")
    suspend fun deleteCalendars(ownerUserId: String, googleAccountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalendars(rows: List<CalendarCatalogEntity>)

    @Transaction
    suspend fun replaceCalendarList(ownerUserId: String, googleAccountId: String, remote: List<GoogleCalendarSummary>) {
        val old = calendars(ownerUserId, googleAccountId).associateBy(CalendarCatalogEntity::calendarId)
        val rows = remote.map { calendar ->
            CalendarCatalogEntity(
                ownerUserId = ownerUserId,
                googleAccountId = googleAccountId,
                calendarId = calendar.id,
                name = calendar.name,
                isPrimary = calendar.isPrimary,
                selected = old[calendar.id]?.selected ?: calendar.isPrimary,
            )
        }
        deleteCalendars(ownerUserId, googleAccountId)
        if (rows.isNotEmpty()) insertCalendars(rows)
    }

    @Query("UPDATE google_calendar_catalog SET selected = :selected WHERE ownerUserId = :ownerUserId AND googleAccountId = :googleAccountId AND calendarId = :calendarId")
    suspend fun setCalendarSelected(ownerUserId: String, googleAccountId: String, calendarId: String, selected: Boolean)

    @Query("""
        SELECT event.* FROM google_calendar_events AS event
        INNER JOIN google_calendar_catalog AS calendar
          ON calendar.ownerUserId = event.ownerUserId
          AND calendar.googleAccountId = event.googleAccountId
          AND calendar.calendarId = event.calendarId
          AND calendar.selected = 1
        WHERE event.ownerUserId = :ownerUserId
          AND event.googleAccountId = (SELECT googleAccountId FROM google_calendar_sync_state WHERE ownerUserId = :ownerUserId LIMIT 1)
          AND (
            (event.allDay = 0 AND event.startEpochMillis < :rangeEndMillis AND event.endEpochMillis > :rangeStartMillis)
            OR (event.allDay = 1 AND event.startDate < :rangeEndDate AND event.endDateExclusive > :rangeStartDate)
          )
        ORDER BY event.allDay DESC, event.startEpochMillis ASC, event.startDate ASC, event.title COLLATE NOCASE ASC
    """)
    fun observeCurrentEvents(
        ownerUserId: String,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
        rangeStartDate: String,
        rangeEndDate: String,
    ): Flow<List<CalendarEventEntity>>

    @Query("""
        DELETE FROM google_calendar_events
        WHERE ownerUserId = :ownerUserId AND googleAccountId = :googleAccountId AND calendarId = :calendarId AND (
            (allDay = 0 AND startEpochMillis < :rangeEndMillis AND endEpochMillis > :rangeStartMillis)
            OR (allDay = 1 AND startDate < :rangeEndDate AND endDateExclusive > :rangeStartDate)
        )
    """)
    suspend fun deleteEventsInRange(
        ownerUserId: String,
        googleAccountId: String,
        calendarId: String,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
        rangeStartDate: String,
        rangeEndDate: String,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<CalendarEventEntity>)

    @Transaction
    suspend fun replaceEventsInRange(
        ownerUserId: String,
        googleAccountId: String,
        calendarId: String,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
        rangeStartDate: String,
        rangeEndDate: String,
        events: List<CalendarEventEntity>,
    ) {
        deleteEventsInRange(ownerUserId, googleAccountId, calendarId, rangeStartMillis, rangeEndMillis, rangeStartDate, rangeEndDate)
        if (events.isNotEmpty()) insertEvents(events)
    }

    @Query("DELETE FROM google_calendar_events WHERE ownerUserId = :ownerUserId AND googleAccountId = :googleAccountId AND cachedAt < :olderThan")
    suspend fun pruneOldEvents(ownerUserId: String, googleAccountId: String, olderThan: Long)

    @Query("""
        DELETE FROM google_calendar_events
        WHERE ownerUserId = :ownerUserId AND googleAccountId = :googleAccountId AND (
            (allDay = 0 AND (startEpochMillis >= :rangeEndMillis OR endEpochMillis <= :rangeStartMillis))
            OR (allDay = 1 AND (startDate >= :rangeEndDate OR endDateExclusive <= :rangeStartDate))
        )
    """)
    suspend fun pruneEventsOutsideRange(
        ownerUserId: String,
        googleAccountId: String,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
        rangeStartDate: String,
        rangeEndDate: String,
    )

    @Query("SELECT * FROM google_calendar_sync_state WHERE ownerUserId = :ownerUserId LIMIT 1")
    suspend fun syncState(ownerUserId: String): CalendarSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSyncState(state: CalendarSyncStateEntity)

    @Query("DELETE FROM google_calendar_events WHERE ownerUserId = :ownerUserId")
    suspend fun deleteAllEvents(ownerUserId: String)

    @Query("DELETE FROM google_calendar_catalog WHERE ownerUserId = :ownerUserId")
    suspend fun deleteAllCalendars(ownerUserId: String)

    @Query("DELETE FROM google_calendar_sync_state WHERE ownerUserId = :ownerUserId")
    suspend fun deleteSyncState(ownerUserId: String)

    @Query("SELECT * FROM google_calendar_sync_state WHERE ownerUserId = :ownerUserId LIMIT 1")
    fun observeSyncState(ownerUserId: String): Flow<CalendarSyncStateEntity?>

    @Query("DELETE FROM google_calendar_events WHERE ownerUserId = :ownerUserId AND googleAccountId = :googleAccountId AND calendarId NOT IN (:calendarIds)")
    suspend fun deleteEventsOutsideCalendarList(ownerUserId: String, googleAccountId: String, calendarIds: List<String>)

    @Transaction
    suspend fun switchGoogleAccount(ownerUserId: String, newAccountId: String) {
        val current = syncState(ownerUserId)
        if (current?.googleAccountId != null && current.googleAccountId != newAccountId) {
            // Do not leave an older Google account's events available after an account switch.
            deleteAllEvents(ownerUserId)
            deleteAllCalendars(ownerUserId)
        }
        saveSyncState(
            CalendarSyncStateEntity(
                ownerUserId = ownerUserId,
                googleAccountId = newAccountId,
                connected = true,
                lastSuccessAt = current?.lastSuccessAt.takeIf { current?.googleAccountId == newAccountId },
                lastErrorCode = null,
            ),
        )
    }
}

@Database(
    entities = [CalendarCatalogEntity::class, CalendarEventEntity::class, CalendarSyncStateEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class CalendarDatabase : RoomDatabase() {
    abstract fun calendarDao(): CalendarDao

    companion object {
        @Volatile private var instance: CalendarDatabase? = null

        fun get(context: Context): CalendarDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CalendarDatabase::class.java,
                "vanta_google_calendar.db",
            ).build().also { instance = it }
        }
    }
}

data class CalendarRange(
    val start: Instant,
    val endExclusive: Instant,
    val zoneId: ZoneId,
) {
    init { require(endExclusive.isAfter(start)) }

    internal val startEpochMillis: Long get() = start.toEpochMilli()
    internal val endEpochMillis: Long get() = endExclusive.toEpochMilli()
    internal val startDate: String get() = start.atZone(zoneId).toLocalDate().toString()
    internal val endDate: String get() = endExclusive.atZone(zoneId).toLocalDate().toString()

    companion object {
        fun bounded(now: Instant, zoneId: ZoneId): CalendarRange {
            val today = now.atZone(zoneId).toLocalDate()
            val start = today.minusDays(CACHE_PAST_DAYS).atStartOfDay(zoneId).toInstant()
            val end = today.plusDays(CACHE_FUTURE_DAYS + 1).atStartOfDay(zoneId).toInstant()
            return CalendarRange(start, end, zoneId)
        }
    }
}

const val CACHE_PAST_DAYS = 7L
const val CACHE_FUTURE_DAYS = 60L
const val CALENDAR_CACHE_FRESH_MILLIS = 15 * 60 * 1000L
