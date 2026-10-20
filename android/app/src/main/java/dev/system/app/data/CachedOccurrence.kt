package dev.system.app.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "cached_occurrences", indices = [Index(value = ["user_id", "status"])])
data class CachedOccurrence(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id", defaultValue = "''") val userId: String,
    val taskId: String,
    val title: String,
    val notes: String?,
    val scheduledFor: String?,
    val allDay: Boolean,
    @ColumnInfo(defaultValue = "'task'") val kind: String,
    val status: String,
    val completedAt: String?,
)

@Entity(tableName = "pending_completions", indices = [Index(value = ["user_id", "queuedAt"])])
data class PendingCompletion(
    @PrimaryKey val idempotencyKey: String,
    @ColumnInfo(name = "user_id", defaultValue = "''") val userId: String,
    val occurrenceId: String,
    val queuedAt: Long = System.currentTimeMillis(),
    val lastError: String? = null,
)
