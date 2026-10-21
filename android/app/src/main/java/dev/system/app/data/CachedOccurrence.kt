package dev.system.app.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cached_occurrences",
    indices = [
        Index(value = ["userId", "status", "scheduledFor"]),
        Index(value = ["userId", "status", "completedAt"]),
        Index(value = ["userId", "scheduleType", "scheduledFor"]),
        Index(value = ["userId", "deadlineAt", "status"]),
    ],
)
data class CachedOccurrence(
    @PrimaryKey val id: String,
    val taskId: String,
    val title: String,
    val notes: String?,
    val scheduledFor: String?,
    val allDay: Boolean,
    @ColumnInfo(defaultValue = "'task'") val kind: String,
    val status: String,
    val completedAt: String?,
    @ColumnInfo(defaultValue = "''") val userId: String = "",
    val durationMinutes: Int? = null,
    @ColumnInfo(defaultValue = "'SOFT'") val scheduleType: String = "SOFT",
    @ColumnInfo(defaultValue = "'NORMAL'") val priority: String = "NORMAL",
    val deadlineAt: String? = null,
    val earliestStart: String? = null,
    val latestEnd: String? = null,
    val preferredStart: String? = null,
)

/** Bounded widget rows plus exact day totals computed in Room. */
data class WidgetOccurrenceSnapshot(
    val occurrences: List<CachedOccurrence>,
    val todayPendingCount: Int,
    val todayCompletedCount: Int,
    val plannedMinutes: Long,
)

@Entity(tableName = "pending_completions", indices = [Index(value = ["occurrenceId"], unique = true)])
data class PendingCompletion(
    @PrimaryKey val idempotencyKey: String,
    val occurrenceId: String,
    val queuedAt: Long = System.currentTimeMillis(),
    val lastError: String? = null,
    @ColumnInfo(defaultValue = "'queued'") val state: String = "queued",
    @ColumnInfo(defaultValue = "0") val retryCount: Int = 0,
    val lastAttemptAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val nextAttemptAt: Long = 0,
    val lastErrorCode: String? = null,
    @ColumnInfo(defaultValue = "''") val userId: String = "",
)

/** A durable, typed local write. Payload columns are constrained by operationType in Room's migration. */
@Entity(
    tableName = "pending_mutations",
    indices = [
        Index(value = ["userId", "state", "nextAttemptAt", "queueOrder"]),
        Index(value = ["queueOrder"], unique = true),
    ],
)
data class PendingMutation(
    @PrimaryKey val operationId: String,
    val userId: String,
    val operationType: String,
    val queueOrder: Long = 0,
    val queuedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'queued'") val state: String = "queued",
    @ColumnInfo(defaultValue = "0") val retryCount: Int = 0,
    val lastAttemptAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val nextAttemptAt: Long = 0,
    val lastErrorCode: String? = null,
    val lastError: String? = null,
    val localOccurrenceId: String? = null,
    val serverOccurrenceId: String? = null,
    val targetOccurrenceId: String? = null,
    val title: String? = null,
    val notes: String? = null,
    val scheduledFor: String? = null,
    val allDay: Boolean? = null,
    val durationMinutes: Int? = null,
    val scheduleType: String? = null,
    val priority: String? = null,
    val deadlineAt: String? = null,
    val earliestStart: String? = null,
    val latestEnd: String? = null,
    val preferredStart: String? = null,
)

@Entity(tableName = "sync_checkpoints")
data class SyncCheckpoint(
    @PrimaryKey val userId: String,
    val revision: Long,
    val bootstrapped: Boolean,
)

@Entity(
    tableName = "bootstrap_occurrences",
    primaryKeys = ["userId", "generation", "id"],
)
data class BootstrapOccurrence(
    val userId: String,
    val generation: String,
    val id: String,
    val taskId: String,
    val title: String,
    val notes: String?,
    val scheduledFor: String?,
    val allDay: Boolean,
    val kind: String,
    val status: String,
    val completedAt: String?,
    val durationMinutes: Int?,
    @ColumnInfo(defaultValue = "'SOFT'") val scheduleType: String,
    @ColumnInfo(defaultValue = "'NORMAL'") val priority: String,
    val deadlineAt: String?,
    val earliestStart: String?,
    val latestEnd: String?,
    val preferredStart: String?,
)

/** Durable bridge for updating user-scoped focus state after a local create gains a server id. */
@Entity(tableName = "pending_focus_reconciliations", indices = [Index(value = ["userId", "queuedAt"])])
data class PendingFocusReconciliation(
    @PrimaryKey val operationId: String,
    val userId: String,
    val oldOccurrenceId: String,
    val newOccurrenceId: String,
    val queuedAt: Long = System.currentTimeMillis(),
)
