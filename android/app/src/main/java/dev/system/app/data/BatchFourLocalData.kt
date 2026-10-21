package dev.system.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Bounded local history used by habits and analytics after completed rows leave the active cache. */
@Entity(
    tableName = "occurrence_history",
    primaryKeys = ["userId", "occurrenceId"],
    indices = [Index(value = ["userId", "scheduledDate"]), Index(value = ["userId", "kind", "scheduledDate"])],
)
data class OccurrenceHistory(
    val userId: String,
    val occurrenceId: String,
    val taskId: String,
    val title: String,
    val scheduledDate: String?,
    val scheduledFor: String?,
    val allDay: Boolean,
    val kind: String,
    val status: String,
    val completedAt: String?,
    val durationMinutes: Int?,
) {
    companion object {
        fun from(row: CachedOccurrence, zone: ZoneId = ZoneId.of("Asia/Kolkata")): OccurrenceHistory = OccurrenceHistory(
            userId = row.userId,
            occurrenceId = row.id,
            taskId = row.taskId,
            title = row.title,
            scheduledDate = row.scheduledFor?.let { value ->
                runCatching { Instant.parse(value).atZone(zone).toLocalDate().toString() }.getOrNull()
            },
            scheduledFor = row.scheduledFor,
            allDay = row.allDay,
            kind = row.kind,
            status = row.status,
            completedAt = row.completedAt,
            durationMinutes = row.durationMinutes,
        )
    }

    fun toCachedOccurrence(): CachedOccurrence = CachedOccurrence(
        id = occurrenceId,
        taskId = taskId,
        title = title,
        notes = null,
        scheduledFor = scheduledFor,
        allDay = allDay,
        kind = kind,
        status = status,
        completedAt = completedAt,
        userId = userId,
        durationMinutes = durationMinutes,
    )
}

/** A local audit row for the app's existing Focus Mode; no external tracking is added. */
@Entity(
    tableName = "focus_session_history",
    indices = [Index(value = ["userId", "startedAtEpochMillis"]), Index(value = ["userId", "occurrenceId"])],
)
data class FocusSessionHistory(
    @PrimaryKey val sessionId: String,
    val userId: String,
    val occurrenceId: String,
    val occurrenceTitle: String,
    val plannedDurationSeconds: Long,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long?,
    val status: String,
)

/** Optional owner-scoped daily note and completion marker, saved locally before network sync. */
@Entity(
    tableName = "daily_reviews",
    primaryKeys = ["userId", "reviewDate"],
    indices = [Index(value = ["userId", "syncPending", "reviewDate"])],
)
data class DailyReviewEntry(
    val userId: String,
    val reviewDate: String,
    val note: String?,
    val completedAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "1") val syncPending: Boolean = true,
)

/** Durable offline intent for the existing idempotent habit skip operation. */
@Entity(
    tableName = "pending_habit_skips",
    indices = [Index(value = ["userId", "queuedAt"]), Index(value = ["userId", "occurrenceId"], unique = true)],
)
data class PendingHabitSkip(
    @PrimaryKey val operationId: String,
    val userId: String,
    val occurrenceId: String,
    val queuedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val nextAttemptAt: Long = 0,
)

fun dev.system.app.focus.FocusSession.toHistory(): FocusSessionHistory = FocusSessionHistory(
    sessionId = "$userId:$occurrenceId:$startedAtEpochMillis",
    userId = userId,
    occurrenceId = occurrenceId,
    occurrenceTitle = occurrenceTitle,
    plannedDurationSeconds = plannedDurationSeconds,
    startedAtEpochMillis = startedAtEpochMillis,
    finishedAtEpochMillis = finishedAtEpochMillis,
    status = status.name,
)

fun String.asReviewDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()
