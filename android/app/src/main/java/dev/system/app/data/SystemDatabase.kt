package dev.system.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SystemDao {
    @Query("SELECT * FROM cached_occurrences WHERE user_id = :userId AND status = 'pending' ORDER BY scheduledFor ASC")
    fun observePending(userId: String): Flow<List<CachedOccurrence>>

    @Query("SELECT * FROM pending_completions WHERE user_id = :userId ORDER BY queuedAt ASC")
    fun observeCompletionQueue(userId: String): Flow<List<PendingCompletion>>

    @Query("SELECT * FROM cached_occurrences WHERE user_id = :userId AND status = 'pending' ORDER BY scheduledFor ASC")
    suspend fun pendingSnapshot(userId: String): List<CachedOccurrence>

    @Query("SELECT * FROM cached_occurrences WHERE user_id = :userId AND id = :id LIMIT 1")
    suspend fun occurrence(userId: String, id: String): CachedOccurrence?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceOccurrences(rows: List<CachedOccurrence>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun addPendingCompletion(item: PendingCompletion)

    @Query("SELECT EXISTS(SELECT 1 FROM pending_completions WHERE user_id = :userId AND occurrenceId = :occurrenceId)")
    suspend fun hasQueuedCompletion(userId: String, occurrenceId: String): Boolean

    @Transaction
    suspend fun queueCompletion(item: PendingCompletion, optimisticOccurrence: CachedOccurrence): Boolean {
        if (hasQueuedCompletion(item.userId, item.occurrenceId)) return false
        addPendingCompletion(item)
        replaceOccurrences(listOf(optimisticOccurrence))
        return true
    }

    @Query("SELECT * FROM pending_completions WHERE user_id = :userId ORDER BY queuedAt ASC")
    suspend fun pendingCompletions(userId: String): List<PendingCompletion>

    @Query("SELECT * FROM pending_completions WHERE user_id = :userId AND state IN ('PENDING', 'RETRYABLE', 'AUTH_REQUIRED') AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now) ORDER BY queuedAt ASC")
    suspend fun eligibleCompletions(userId: String, now: Long): List<PendingCompletion>

    @Query("SELECT occurrenceId FROM pending_completions WHERE user_id = :userId")
    suspend fun queuedOccurrenceIds(userId: String): List<String>

    @Query("SELECT * FROM pending_completions WHERE user_id = '' ORDER BY queuedAt ASC")
    suspend fun legacyPendingCompletions(): List<PendingCompletion>

    @Query("UPDATE pending_completions SET user_id = :userId WHERE user_id = '' AND idempotencyKey = :key")
    suspend fun adoptLegacyCompletion(userId: String, key: String)

    @Query("DELETE FROM pending_completions WHERE user_id = :userId AND idempotencyKey = :key")
    suspend fun removePendingCompletion(userId: String, key: String)

    @Query("UPDATE pending_completions SET lastAttemptAt = :attemptedAt WHERE user_id = :userId AND idempotencyKey = :key")
    suspend fun markCompletionAttempt(userId: String, key: String, attemptedAt: Long)

    @Query("UPDATE pending_completions SET state = :state, retryCount = :retryCount, lastAttemptAt = :attemptedAt, nextAttemptAt = :nextAttemptAt, lastErrorCode = :errorCode, lastError = :message WHERE user_id = :userId AND idempotencyKey = :key")
    suspend fun setCompletionOutcome(
        userId: String,
        key: String,
        state: String,
        retryCount: Int,
        attemptedAt: Long,
        nextAttemptAt: Long?,
        errorCode: String?,
        message: String?,
    )

    @Query("UPDATE pending_completions SET state = 'AUTH_REQUIRED', lastAttemptAt = :attemptedAt, nextAttemptAt = NULL, lastErrorCode = 'AUTH_REQUIRED', lastError = :message WHERE user_id = :userId AND state IN ('PENDING', 'RETRYABLE', 'AUTH_REQUIRED')")
    suspend fun markCompletionsAuthRequired(userId: String, attemptedAt: Long, message: String)

    @Query("DELETE FROM cached_occurrences WHERE user_id = :userId AND id NOT IN (SELECT occurrenceId FROM pending_completions WHERE user_id = :userId)")
    suspend fun clearSnapshotExceptQueued(userId: String)

    @Transaction
    suspend fun replaceOccurrencesSnapshot(userId: String, rows: List<CachedOccurrence>) {
        val queuedIds = queuedOccurrenceIds(userId).toSet()
        clearSnapshotExceptQueued(userId)
        replaceOccurrences(rows.filter { it.userId == userId && it.id !in queuedIds })
    }

    @Query("DELETE FROM cached_occurrences WHERE user_id = ''")
    suspend fun clearLegacyOccurrences()

    @Query("DELETE FROM cached_occurrences WHERE user_id = :userId")
    suspend fun clearOccurrences(userId: String)

}

@Database(entities = [CachedOccurrence::class, PendingCompletion::class], version = 5, exportSchema = false)
abstract class SystemDatabase : RoomDatabase() {
    abstract fun systemDao(): SystemDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_occurrences ADD COLUMN kind TEXT NOT NULL DEFAULT 'task'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_occurrences ADD COLUMN user_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pending_completions ADD COLUMN user_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_occurrences_user_id_status ON cached_occurrences(user_id, status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_completions_user_id_queuedAt ON pending_completions(user_id, queuedAt)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    DELETE FROM pending_completions
                    WHERE rowid NOT IN (
                        SELECT MIN(rowid) FROM pending_completions GROUP BY user_id, occurrenceId
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_completions_user_id_occurrenceId " +
                        "ON pending_completions(user_id, occurrenceId)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_completions ADD COLUMN state TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE pending_completions ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_completions ADD COLUMN lastAttemptAt INTEGER")
                db.execSQL("ALTER TABLE pending_completions ADD COLUMN nextAttemptAt INTEGER")
                db.execSQL("ALTER TABLE pending_completions ADD COLUMN lastErrorCode TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pending_completions_user_id_state_nextAttemptAt " +
                        "ON pending_completions(user_id, state, nextAttemptAt)",
                )
            }
        }
    }
}
