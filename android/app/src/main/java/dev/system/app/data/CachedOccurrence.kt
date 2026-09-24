package dev.system.app.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "cached_occurrences")
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
)

@Entity(tableName = "pending_completions")
data class PendingCompletion(
    @PrimaryKey val idempotencyKey: String,
    val occurrenceId: String,
    val queuedAt: Long = System.currentTimeMillis(),
    val lastError: String? = null,
)
