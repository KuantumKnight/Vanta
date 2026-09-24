package dev.system.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SystemDao {
    @Query("SELECT * FROM cached_occurrences WHERE status = 'pending' ORDER BY scheduledFor ASC")
    fun observePending(): Flow<List<CachedOccurrence>>

    @Query("SELECT * FROM pending_completions ORDER BY queuedAt ASC")
    fun observeCompletionQueue(): Flow<List<PendingCompletion>>

    @Query("SELECT * FROM cached_occurrences WHERE status = 'pending' ORDER BY scheduledFor ASC")
    suspend fun pendingSnapshot(): List<CachedOccurrence>

    @Query("SELECT * FROM cached_occurrences WHERE id = :id LIMIT 1")
    suspend fun occurrence(id: String): CachedOccurrence?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceOccurrences(rows: List<CachedOccurrence>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPendingCompletion(item: PendingCompletion)

    @Query("SELECT * FROM pending_completions ORDER BY queuedAt ASC")
    suspend fun pendingCompletions(): List<PendingCompletion>

    @Query("DELETE FROM pending_completions WHERE idempotencyKey = :key")
    suspend fun removePendingCompletion(key: String)

    @Query("UPDATE pending_completions SET lastError = :message WHERE idempotencyKey = :key")
    suspend fun setCompletionError(key: String, message: String)

    @Query("DELETE FROM cached_occurrences")
    suspend fun clearOccurrences()

    @Query("DELETE FROM pending_completions")
    suspend fun clearCompletionQueue()
}

@Database(entities = [CachedOccurrence::class, PendingCompletion::class], version = 2, exportSchema = false)
abstract class SystemDatabase : RoomDatabase() {
    abstract fun systemDao(): SystemDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_occurrences ADD COLUMN kind TEXT NOT NULL DEFAULT 'task'")
            }
        }
    }
}
