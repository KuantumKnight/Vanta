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
    @Query("""
        SELECT cached_occurrences.* FROM cached_occurrences
        WHERE cached_occurrences.userId = :userId AND cached_occurrences.status = 'pending'
          AND NOT EXISTS (
            SELECT 1 FROM pending_completions
            WHERE pending_completions.userId = :userId AND pending_completions.occurrenceId = cached_occurrences.id
          )
        ORDER BY cached_occurrences.scheduledFor ASC
    """)
    fun observePending(userId: String): Flow<List<CachedOccurrence>>

    @Query("SELECT * FROM pending_completions WHERE userId = :userId ORDER BY queuedAt ASC")
    fun observeCompletionQueue(userId: String): Flow<List<PendingCompletion>>

    @Query("""
        SELECT cached_occurrences.* FROM cached_occurrences
        WHERE cached_occurrences.userId = :userId AND cached_occurrences.status = 'pending'
          AND NOT EXISTS (
            SELECT 1 FROM pending_completions
            WHERE pending_completions.userId = :userId AND pending_completions.occurrenceId = cached_occurrences.id
          )
        ORDER BY cached_occurrences.scheduledFor ASC
    """)
    suspend fun pendingSnapshot(userId: String): List<CachedOccurrence>

    @Query("""
        SELECT cached_occurrences.* FROM cached_occurrences
        WHERE cached_occurrences.userId = :userId AND (
          (cached_occurrences.status = 'pending' AND NOT EXISTS (
            SELECT 1 FROM pending_completions
            WHERE pending_completions.userId = :userId AND pending_completions.occurrenceId = cached_occurrences.id
          ) AND (
            (cached_occurrences.scheduledFor >= :dayStart AND cached_occurrences.scheduledFor < :nextDayStart)
            OR cached_occurrences.id = (
              SELECT previous.id FROM cached_occurrences AS previous
              WHERE previous.userId = :userId AND previous.status = 'pending'
                AND previous.scheduledFor >= :activeSince AND previous.scheduledFor < :dayStart
                AND previous.allDay = 0 AND previous.durationMinutes IS NOT NULL
                AND CAST(strftime('%s', previous.scheduledFor) AS INTEGER) + previous.durationMinutes * 60 > :nowEpochSeconds
                AND NOT EXISTS (
                  SELECT 1 FROM pending_completions
                  WHERE pending_completions.userId = :userId AND pending_completions.occurrenceId = previous.id
                )
              ORDER BY previous.scheduledFor ASC, previous.id ASC
              LIMIT 1
            )
            OR cached_occurrences.id = (
              SELECT future.id FROM cached_occurrences AS future
              WHERE future.userId = :userId AND future.status = 'pending'
                AND future.scheduledFor >= :nextDayStart
                AND NOT EXISTS (
                  SELECT 1 FROM pending_completions
                  WHERE pending_completions.userId = :userId AND pending_completions.occurrenceId = future.id
                )
              ORDER BY future.scheduledFor ASC, future.id ASC
              LIMIT 1
            )
          ))
          OR (cached_occurrences.status = 'completed'
            AND cached_occurrences.completedAt >= :dayStart
            AND cached_occurrences.completedAt < :nextDayStart
            AND cached_occurrences.scheduledFor >= :dayStart
            AND cached_occurrences.scheduledFor < :nextDayStart)
        )
        ORDER BY CASE
                   WHEN cached_occurrences.scheduledFor >= :nextDayStart THEN 0
                   WHEN cached_occurrences.scheduledFor < :dayStart THEN 1
                   ELSE 2
                 END,
                 cached_occurrences.scheduledFor ASC
        LIMIT 128
    """)
    suspend fun widgetSnapshotRows(userId: String, activeSince: String, nowEpochSeconds: Long, dayStart: String, nextDayStart: String): List<CachedOccurrence>

    @Query("""
        SELECT COUNT(*) FROM cached_occurrences
        WHERE userId = :userId AND status = 'pending'
          AND scheduledFor >= :dayStart AND scheduledFor < :nextDayStart
          AND NOT EXISTS (
            SELECT 1 FROM pending_completions
            WHERE pending_completions.userId = :userId AND pending_completions.occurrenceId = cached_occurrences.id
          )
    """)
    suspend fun widgetTodayPendingCount(userId: String, dayStart: String, nextDayStart: String): Int

    @Query("""
        SELECT COUNT(*) FROM cached_occurrences
        WHERE userId = :userId AND status = 'completed'
          AND completedAt >= :dayStart AND completedAt < :nextDayStart
          AND scheduledFor >= :dayStart AND scheduledFor < :nextDayStart
    """)
    suspend fun widgetTodayCompletedCount(userId: String, dayStart: String, nextDayStart: String): Int

    @Query("""
        SELECT COALESCE(SUM(durationMinutes), 0) FROM cached_occurrences
        WHERE userId = :userId AND allDay = 0 AND durationMinutes BETWEEN 5 AND 1440
          AND ((status = 'pending' AND scheduledFor >= :dayStart AND scheduledFor < :nextDayStart
            AND NOT EXISTS (
              SELECT 1 FROM pending_completions
              WHERE pending_completions.userId = :userId AND pending_completions.occurrenceId = cached_occurrences.id
            ))
            OR (status = 'completed' AND completedAt >= :dayStart AND completedAt < :nextDayStart
              AND scheduledFor >= :dayStart AND scheduledFor < :nextDayStart))
    """)
    suspend fun widgetTodayPlannedMinutes(userId: String, dayStart: String, nextDayStart: String): Long

    @Transaction
    suspend fun widgetSnapshot(
        userId: String,
        activeSince: String,
        nowEpochSeconds: Long,
        dayStart: String,
        nextDayStart: String,
    ): WidgetOccurrenceSnapshot = WidgetOccurrenceSnapshot(
        occurrences = widgetSnapshotRows(userId, activeSince, nowEpochSeconds, dayStart, nextDayStart),
        todayPendingCount = widgetTodayPendingCount(userId, dayStart, nextDayStart),
        todayCompletedCount = widgetTodayCompletedCount(userId, dayStart, nextDayStart),
        plannedMinutes = widgetTodayPlannedMinutes(userId, dayStart, nextDayStart),
    )

    @Query("DELETE FROM cached_occurrences WHERE userId = :userId AND status = 'completed' AND (completedAt IS NULL OR completedAt < :dayStart OR completedAt >= :nextDayStart) AND id NOT IN (SELECT occurrenceId FROM pending_completions WHERE userId = :userId)")
    suspend fun deleteCompletedOutsideDay(userId: String, dayStart: String, nextDayStart: String)

    @Query("SELECT * FROM cached_occurrences WHERE userId = :userId AND scheduledFor >= :dayStart AND scheduledFor < :nextDayStart")
    suspend fun occurrencesForDateRange(userId: String, dayStart: String, nextDayStart: String): List<CachedOccurrence>

    /** Room-backed, bounded planner read: at most 700 scheduled rows and 100 flexible tasks. */
    @Query("""
        WITH scheduled_rows AS (
          SELECT cached_occurrences.* FROM cached_occurrences
          WHERE userId = :userId AND status IN ('pending', 'completed')
            AND scheduledFor >= :rangeStart AND scheduledFor < :rangeEnd
            AND (status <> 'pending' OR NOT EXISTS (
              SELECT 1 FROM pending_completions
              WHERE pending_completions.userId = :userId
                AND pending_completions.occurrenceId = cached_occurrences.id
            ))
          ORDER BY scheduledFor ASC, id ASC
          LIMIT 700
        ), flexible_rows AS (
          SELECT cached_occurrences.* FROM cached_occurrences
          WHERE userId = :userId AND status = 'pending' AND scheduleType = 'FLEXIBLE'
            AND scheduledFor IS NULL
            AND NOT EXISTS (
              SELECT 1 FROM pending_completions
              WHERE pending_completions.userId = :userId
                AND pending_completions.occurrenceId = cached_occurrences.id
            )
            AND (
              (deadlineAt IS NULL AND earliestStart IS NULL AND latestEnd IS NULL)
              OR (deadlineAt >= :rangeStart AND deadlineAt < :rangeEnd)
              OR (earliestStart < :rangeEnd AND (latestEnd IS NULL OR latestEnd >= :rangeStart))
              OR (latestEnd >= :rangeStart AND latestEnd < :rangeEnd)
            )
          ORDER BY COALESCE(deadlineAt, latestEnd, earliestStart) ASC, id ASC
          LIMIT 100
        )
        SELECT * FROM (
          SELECT * FROM scheduled_rows
          UNION ALL
          SELECT * FROM flexible_rows
        )
        ORDER BY COALESCE(scheduledFor, deadlineAt, latestEnd, earliestStart) ASC, id ASC
    """)
    fun observePlannerRange(userId: String, rangeStart: String, rangeEnd: String): Flow<List<CachedOccurrence>>

    @Query("""
        WITH scheduled_rows AS (
          SELECT cached_occurrences.* FROM cached_occurrences
          WHERE userId = :userId AND status IN ('pending', 'completed')
            AND scheduledFor >= :rangeStart AND scheduledFor < :rangeEnd
            AND (status <> 'pending' OR NOT EXISTS (
              SELECT 1 FROM pending_completions
              WHERE pending_completions.userId = :userId
                AND pending_completions.occurrenceId = cached_occurrences.id
            ))
          ORDER BY scheduledFor ASC, id ASC
          LIMIT 700
        ), flexible_rows AS (
          SELECT cached_occurrences.* FROM cached_occurrences
          WHERE userId = :userId AND status = 'pending' AND scheduleType = 'FLEXIBLE'
            AND scheduledFor IS NULL
            AND NOT EXISTS (
              SELECT 1 FROM pending_completions
              WHERE pending_completions.userId = :userId
                AND pending_completions.occurrenceId = cached_occurrences.id
            )
            AND (
              (deadlineAt IS NULL AND earliestStart IS NULL AND latestEnd IS NULL)
              OR (deadlineAt >= :rangeStart AND deadlineAt < :rangeEnd)
              OR (earliestStart < :rangeEnd AND (latestEnd IS NULL OR latestEnd >= :rangeStart))
              OR (latestEnd >= :rangeStart AND latestEnd < :rangeEnd)
            )
          ORDER BY COALESCE(deadlineAt, latestEnd, earliestStart) ASC, id ASC
          LIMIT 100
        )
        SELECT * FROM (
          SELECT * FROM scheduled_rows
          UNION ALL
          SELECT * FROM flexible_rows
        )
        ORDER BY COALESCE(scheduledFor, deadlineAt, latestEnd, earliestStart) ASC, id ASC
    """)
    suspend fun plannerRangeSnapshot(userId: String, rangeStart: String, rangeEnd: String): List<CachedOccurrence>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOccurrenceHistory(rows: List<OccurrenceHistory>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOccurrenceHistory(row: OccurrenceHistory)

    @Query("SELECT * FROM occurrence_history WHERE userId = :userId AND scheduledDate >= :startDate AND scheduledDate <= :endDate ORDER BY scheduledDate ASC, scheduledFor ASC")
    suspend fun occurrenceHistoryRange(userId: String, startDate: String, endDate: String): List<OccurrenceHistory>

    @Query("SELECT * FROM occurrence_history WHERE userId = :userId AND kind = 'habit' AND scheduledDate >= :startDate AND scheduledDate <= :endDate ORDER BY scheduledDate ASC, scheduledFor ASC")
    suspend fun habitHistoryRange(userId: String, startDate: String, endDate: String): List<OccurrenceHistory>

    @Query("SELECT * FROM focus_session_history WHERE userId = :userId AND startedAtEpochMillis >= :startMillis AND startedAtEpochMillis < :endMillis ORDER BY startedAtEpochMillis ASC")
    suspend fun focusHistoryRange(userId: String, startMillis: Long, endMillis: Long): List<FocusSessionHistory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFocusHistory(item: FocusSessionHistory)

    @Query("UPDATE focus_session_history SET occurrenceId = :newOccurrenceId WHERE userId = :userId AND occurrenceId = :oldOccurrenceId")
    suspend fun rewriteFocusHistoryOccurrence(userId: String, oldOccurrenceId: String, newOccurrenceId: String)

    @Query("SELECT * FROM daily_reviews WHERE userId = :userId AND reviewDate = :reviewDate LIMIT 1")
    suspend fun dailyReview(userId: String, reviewDate: String): DailyReviewEntry?

    @Query("SELECT * FROM daily_reviews WHERE userId = :userId AND syncPending = 1 ORDER BY reviewDate ASC")
    suspend fun pendingDailyReviews(userId: String): List<DailyReviewEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyReview(item: DailyReviewEntry)

    @Query("UPDATE daily_reviews SET syncPending = 0 WHERE userId = :userId AND reviewDate = :reviewDate AND completedAtEpochMillis = :savedAtEpochMillis")
    suspend fun markDailyReviewSynced(userId: String, reviewDate: String, savedAtEpochMillis: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPendingHabitSkip(item: PendingHabitSkip)

    @Query("SELECT * FROM pending_habit_skips WHERE userId = :userId AND nextAttemptAt <= :now ORDER BY queuedAt ASC LIMIT :limit")
    suspend fun dueHabitSkips(userId: String, now: Long, limit: Int): List<PendingHabitSkip>

    @Query("SELECT EXISTS(SELECT 1 FROM pending_habit_skips WHERE userId = :userId AND occurrenceId = :occurrenceId)")
    suspend fun hasPendingHabitSkip(userId: String, occurrenceId: String): Boolean

    @Query("SELECT COUNT(*) FROM pending_habit_skips WHERE userId = :userId")
    suspend fun pendingHabitSkipCount(userId: String): Int

    @Query("DELETE FROM pending_habit_skips WHERE userId = :userId AND operationId = :operationId")
    suspend fun deletePendingHabitSkip(userId: String, operationId: String)

    @Query("UPDATE pending_habit_skips SET nextAttemptAt = :nextAttemptAt WHERE userId = :userId AND operationId = :operationId")
    suspend fun retryPendingHabitSkip(userId: String, operationId: String, nextAttemptAt: Long)

    @Transaction
    suspend fun storeHabitSkip(item: PendingHabitSkip, occurrence: CachedOccurrence): Boolean {
        if (item.userId.isBlank() || item.userId != occurrence.userId || occurrence.kind != "habit") return false
        if (occurrence.status != "pending" || hasPendingHabitSkip(item.userId, occurrence.id)) return false
        insertPendingHabitSkip(item)
        val skipped = occurrence.copy(status = "skipped", completedAt = null)
        replaceOccurrences(listOf(skipped))
        upsertOccurrenceHistory(OccurrenceHistory.from(skipped))
        return true
    }

    @Query("DELETE FROM occurrence_history WHERE userId = :userId AND occurrenceId = :occurrenceId")
    suspend fun deleteOccurrenceHistory(userId: String, occurrenceId: String)

    @Transaction
    suspend fun pruneCompletedOutsideDay(userId: String, dayStart: String, nextDayStart: String) {
        val completed = completedOutsideDay(userId, dayStart, nextDayStart)
        if (completed.isNotEmpty()) upsertOccurrenceHistory(completed.map { OccurrenceHistory.from(it) })
        deleteCompletedOutsideDay(userId, dayStart, nextDayStart)
    }

    @Query("SELECT * FROM cached_occurrences WHERE userId = :userId AND status = 'completed' AND (completedAt IS NULL OR completedAt < :dayStart OR completedAt >= :nextDayStart) AND id NOT IN (SELECT occurrenceId FROM pending_completions WHERE userId = :userId)")
    suspend fun completedOutsideDay(userId: String, dayStart: String, nextDayStart: String): List<CachedOccurrence>

    @Query("SELECT * FROM cached_occurrences WHERE userId = :userId AND id = :id LIMIT 1")
    suspend fun occurrence(userId: String, id: String): CachedOccurrence?

    @Query("SELECT * FROM cached_occurrences WHERE userId = :userId AND id IN (:ids)")
    suspend fun occurrences(userId: String, ids: List<String>): List<CachedOccurrence>

    @Query("SELECT EXISTS(SELECT 1 FROM pending_completions WHERE userId = :userId AND occurrenceId = :occurrenceId)")
    suspend fun hasQueuedCompletion(userId: String, occurrenceId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceOccurrences(rows: List<CachedOccurrence>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStagedOccurrences(rows: List<BootstrapOccurrence>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPendingMutation(item: PendingMutation)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun addPendingCompletion(item: PendingCompletion)

    @Transaction
    suspend fun storeQueuedCompletion(item: PendingCompletion, occurrence: CachedOccurrence): Boolean {
        if (item.userId.isBlank() || item.userId != occurrence.userId) return false
        if (hasQueuedCompletion(item.userId, item.occurrenceId)) return false
        addPendingCompletion(item)
        replaceOccurrences(listOf(occurrence))
        upsertOccurrenceHistory(OccurrenceHistory.from(occurrence))
        return true
    }

    @Transaction
    suspend fun storeCreateMutation(item: PendingMutation, occurrence: CachedOccurrence): Boolean {
        if (item.userId.isBlank() || item.userId != occurrence.userId || item.operationType != "create_task") return false
        if (item.localOccurrenceId != occurrence.id || !occurrence.id.startsWith("local:")) return false
        insertPendingMutation(item.copy(queueOrder = nextMutationQueueOrder()))
        replaceOccurrences(listOf(occurrence))
        return true
    }

    @Transaction
    suspend fun storeRescheduleMutation(item: PendingMutation, occurrence: CachedOccurrence): Boolean {
        if (item.userId.isBlank() || item.userId != occurrence.userId || item.operationType != "reschedule_occurrence") return false
        if (item.targetOccurrenceId != occurrence.id || occurrence.status != "pending") return false
        if (hasQueuedCompletion(item.userId, occurrence.id)) return false
        insertPendingMutation(item.copy(queueOrder = nextMutationQueueOrder()))
        replaceOccurrences(listOf(occurrence))
        return true
    }

    /** Queue an approved multi-item proposal as one local transaction. */
    @Transaction
    suspend fun storeRescheduleMutations(items: List<PendingMutation>, occurrences: List<CachedOccurrence>): Boolean {
        if (items.isEmpty() || items.size != occurrences.size) return false
        val userId = items.first().userId
        if (userId.isBlank() || items.any { it.userId != userId }) return false
        if (items.any { it.targetOccurrenceId == null }) return false
        val targetIds = items.mapNotNull(PendingMutation::targetOccurrenceId)
        if (targetIds.toSet().size != targetIds.size) return false
        for (index in items.indices) {
            val item = items[index]
            val proposed = occurrences[index]
            val current = occurrence(userId, proposed.id) ?: return false
            if (item.operationType != "reschedule_occurrence" || item.targetOccurrenceId != proposed.id ||
                proposed.userId != userId || proposed.status != "pending" || proposed.scheduleType != "SOFT" ||
                current.status != "pending" || current.scheduleType == "HARD" ||
                item.scheduledFor != proposed.scheduledFor || hasQueuedCompletion(userId, proposed.id) ||
                hasPendingMutationForOccurrence(userId, proposed.id)
            ) return false
            insertPendingMutation(item.copy(queueOrder = nextMutationQueueOrder()))
        }
        replaceOccurrences(occurrences)
        return true
    }

    @Query("SELECT COALESCE(MAX(queueOrder), 0) + 1 FROM pending_mutations")
    suspend fun nextMutationQueueOrder(): Long

    @Query("SELECT * FROM pending_mutations WHERE userId = :userId ORDER BY queueOrder ASC")
    suspend fun pendingMutations(userId: String): List<PendingMutation>

    @Query("""
        SELECT candidate.* FROM pending_mutations AS candidate
        WHERE candidate.userId = :userId AND candidate.state = 'queued'
          AND candidate.nextAttemptAt <= :now
          AND NOT EXISTS (
            SELECT 1 FROM pending_mutations AS earlier
            WHERE earlier.userId = candidate.userId
              AND earlier.queueOrder < candidate.queueOrder
              AND earlier.state IN ('queued', 'terminal')
              AND (
                (candidate.localOccurrenceId IS NOT NULL AND candidate.localOccurrenceId IN (earlier.localOccurrenceId, earlier.serverOccurrenceId, earlier.targetOccurrenceId)) OR
                (candidate.serverOccurrenceId IS NOT NULL AND candidate.serverOccurrenceId IN (earlier.localOccurrenceId, earlier.serverOccurrenceId, earlier.targetOccurrenceId)) OR
                (candidate.targetOccurrenceId IS NOT NULL AND candidate.targetOccurrenceId IN (earlier.localOccurrenceId, earlier.serverOccurrenceId, earlier.targetOccurrenceId))
              )
          )
        ORDER BY candidate.queueOrder ASC
        LIMIT 1
    """)
    suspend fun nextDuePendingMutation(userId: String, now: Long): PendingMutation?

    @Query("""
        SELECT MIN(candidate.nextAttemptAt) FROM pending_mutations AS candidate
        WHERE candidate.userId = :userId AND candidate.state = 'queued'
          AND NOT EXISTS (
            SELECT 1 FROM pending_mutations AS earlier
            WHERE earlier.userId = candidate.userId
              AND earlier.queueOrder < candidate.queueOrder
              AND earlier.state IN ('queued', 'terminal')
              AND (
                (candidate.localOccurrenceId IS NOT NULL AND candidate.localOccurrenceId IN (earlier.localOccurrenceId, earlier.serverOccurrenceId, earlier.targetOccurrenceId)) OR
                (candidate.serverOccurrenceId IS NOT NULL AND candidate.serverOccurrenceId IN (earlier.localOccurrenceId, earlier.serverOccurrenceId, earlier.targetOccurrenceId)) OR
                (candidate.targetOccurrenceId IS NOT NULL AND candidate.targetOccurrenceId IN (earlier.localOccurrenceId, earlier.serverOccurrenceId, earlier.targetOccurrenceId))
              )
          )
    """)
    suspend fun nextMutationRetryAt(userId: String): Long?

    @Query("SELECT * FROM pending_focus_reconciliations WHERE userId = :userId ORDER BY queuedAt ASC, operationId ASC")
    suspend fun pendingFocusReconciliations(userId: String): List<PendingFocusReconciliation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueFocusReconciliation(item: PendingFocusReconciliation)

    @Query("DELETE FROM pending_focus_reconciliations WHERE userId = :userId AND operationId = :operationId")
    suspend fun removeFocusReconciliation(userId: String, operationId: String)

    @Query("SELECT * FROM pending_mutations WHERE userId = :userId AND operationId = :operationId LIMIT 1")
    suspend fun pendingMutation(userId: String, operationId: String): PendingMutation?

    @Query("SELECT EXISTS(SELECT 1 FROM pending_mutations WHERE userId = :userId AND (targetOccurrenceId = :occurrenceId OR localOccurrenceId = :occurrenceId OR serverOccurrenceId = :occurrenceId))")
    suspend fun hasPendingMutationForOccurrence(userId: String, occurrenceId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM pending_mutations WHERE userId = :userId AND operationId <> :operationId AND (targetOccurrenceId = :occurrenceId OR localOccurrenceId = :occurrenceId OR serverOccurrenceId = :occurrenceId))")
    suspend fun hasOtherPendingMutationForOccurrence(userId: String, occurrenceId: String, operationId: String): Boolean

    @Query("UPDATE pending_mutations SET serverOccurrenceId = :serverOccurrenceId WHERE userId = :userId AND operationId = :operationId AND operationType = 'create_task'")
    suspend fun attachCreatedServerOccurrence(userId: String, operationId: String, serverOccurrenceId: String)

    @Query("SELECT * FROM sync_checkpoints WHERE userId = :userId LIMIT 1")
    suspend fun syncCheckpoint(userId: String): SyncCheckpoint?

    @Query("SELECT * FROM bootstrap_occurrences WHERE userId = :userId AND generation = :generation ORDER BY id ASC")
    suspend fun stagedOccurrences(userId: String, generation: String): List<BootstrapOccurrence>

    @Query("DELETE FROM bootstrap_occurrences WHERE userId = :userId")
    suspend fun clearStagedOccurrences(userId: String)

    @Query("DELETE FROM bootstrap_occurrences WHERE userId = :userId AND generation = :generation")
    suspend fun deleteStagedOccurrences(userId: String, generation: String)

    @Query("DELETE FROM pending_mutations WHERE userId = :userId AND operationId = :operationId")
    suspend fun deletePendingMutation(userId: String, operationId: String)

    @Query("UPDATE pending_mutations SET targetOccurrenceId = :newOccurrenceId WHERE userId = :userId AND targetOccurrenceId = :oldOccurrenceId")
    suspend fun rewriteMutationTargets(userId: String, oldOccurrenceId: String, newOccurrenceId: String)

    @Query("UPDATE pending_completions SET occurrenceId = :newOccurrenceId WHERE userId = :userId AND occurrenceId = :oldOccurrenceId")
    suspend fun rewriteCompletionOccurrence(userId: String, oldOccurrenceId: String, newOccurrenceId: String)

    @Query("UPDATE pending_mutations SET retryCount = :retryCount, lastAttemptAt = :attemptAt, nextAttemptAt = :nextAttemptAt, lastErrorCode = :errorCode, lastError = :message WHERE userId = :userId AND operationId = :operationId AND state = 'queued'")
    suspend fun markMutationRetry(userId: String, operationId: String, retryCount: Int, attemptAt: Long, nextAttemptAt: Long, errorCode: String?, message: String?)

    @Query("UPDATE pending_mutations SET nextAttemptAt = 0, lastErrorCode = NULL, lastError = NULL WHERE userId = :userId AND state = 'queued' AND lastErrorCode = 'AUTH_REQUIRED'")
    suspend fun resetAuthRequiredMutations(userId: String)

    @Query("UPDATE pending_mutations SET state = :state, lastAttemptAt = :attemptAt, lastErrorCode = :errorCode, lastError = :message WHERE userId = :userId AND operationId = :operationId AND state = 'queued'")
    suspend fun markMutationTerminal(userId: String, operationId: String, state: String, attemptAt: Long, errorCode: String?, message: String?)

    @Transaction
    suspend fun applyDeltaPage(userId: String, changes: List<OccurrenceDelta>, revision: Long) {
        for (change in changes) {
            if (change.occurrenceId.isBlank()) continue
            if (hasPendingMutationForOccurrence(userId, change.occurrenceId) || hasQueuedCompletion(userId, change.occurrenceId) || hasPendingHabitSkip(userId, change.occurrenceId)) continue
            if (change.isDeleted) {
                deleteOccurrence(userId, change.occurrenceId)
            } else {
                change.occurrence?.takeIf { it.userId == userId && it.id == change.occurrenceId }?.let {
                    replaceOccurrences(listOf(it))
                    if (it.status == "completed" || it.status == "skipped") {
                        upsertOccurrenceHistory(OccurrenceHistory.from(it))
                    }
                }
            }
        }
        writeSyncCheckpoint(SyncCheckpoint(userId, revision, bootstrapped = true))
    }

    @Transaction
    suspend fun finishBootstrap(userId: String, generation: String, revision: Long) {
        clearRemoteRowsNotOverlaid(userId)
        mergeStagedRows(userId, generation)
        writeSyncCheckpoint(SyncCheckpoint(userId, revision, bootstrapped = true))
        deleteStagedOccurrences(userId, generation)
    }

    @Transaction
    suspend fun reconcileCreatedOccurrence(
        userId: String,
        operationId: String,
        localOccurrenceId: String,
        canonical: CachedOccurrence,
    ) {
        val local = occurrence(userId, localOccurrenceId)
        val hasCompletion = hasQueuedCompletion(userId, localOccurrenceId)
        val hasFollowingMutation = hasPendingMutationForOccurrence(userId, localOccurrenceId)
        val resolved = if (local != null && (hasCompletion || hasFollowingMutation)) {
            canonical.copy(
                title = local.title,
                notes = local.notes,
                scheduledFor = local.scheduledFor,
                allDay = local.allDay,
                kind = local.kind,
                status = local.status,
                completedAt = local.completedAt,
                durationMinutes = local.durationMinutes,
                scheduleType = local.scheduleType,
                priority = local.priority,
                deadlineAt = local.deadlineAt,
                earliestStart = local.earliestStart,
                latestEnd = local.latestEnd,
                preferredStart = local.preferredStart,
            )
        } else canonical

        rewriteMutationTargets(userId, localOccurrenceId, canonical.id)
        rewriteCompletionOccurrence(userId, localOccurrenceId, canonical.id)
        rewriteFocusHistoryOccurrence(userId, localOccurrenceId, canonical.id)
        if (resolved.status == "completed" || resolved.status == "skipped") {
            upsertOccurrenceHistory(OccurrenceHistory.from(resolved))
        }
        if (localOccurrenceId != canonical.id) {
            enqueueFocusReconciliation(
                PendingFocusReconciliation(
                    operationId = operationId,
                    userId = userId,
                    oldOccurrenceId = localOccurrenceId,
                    newOccurrenceId = canonical.id,
                ),
            )
        }
        if (localOccurrenceId != canonical.id) deleteOccurrence(userId, localOccurrenceId)
        replaceOccurrences(listOf(resolved))
        deletePendingMutation(userId, operationId)
    }

    @Transaction
    suspend fun reconcileRescheduledOccurrence(userId: String, operationId: String, canonical: CachedOccurrence) {
        if (!hasOtherPendingMutationForOccurrence(userId, canonical.id, operationId) && !hasQueuedCompletion(userId, canonical.id)) {
            replaceOccurrences(listOf(canonical))
        }
        deletePendingMutation(userId, operationId)
    }

    @Transaction
    suspend fun clearSyncState(userId: String) {
        clearCheckpoint(userId)
        clearStagedOccurrences(userId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeSyncCheckpoint(checkpoint: SyncCheckpoint)

    @Query("DELETE FROM sync_checkpoints WHERE userId = :userId")
    suspend fun clearCheckpoint(userId: String)

    @Query("DELETE FROM cached_occurrences WHERE userId = :userId AND id NOT IN (SELECT occurrenceId FROM pending_completions WHERE userId = :userId) AND id NOT IN (SELECT targetOccurrenceId FROM pending_mutations WHERE userId = :userId AND targetOccurrenceId IS NOT NULL) AND id NOT IN (SELECT localOccurrenceId FROM pending_mutations WHERE userId = :userId AND localOccurrenceId IS NOT NULL) AND id NOT IN (SELECT serverOccurrenceId FROM pending_mutations WHERE userId = :userId AND serverOccurrenceId IS NOT NULL)")
    suspend fun clearRemoteRowsNotOverlaid(userId: String)

    @Query("INSERT OR REPLACE INTO cached_occurrences (id, taskId, title, notes, scheduledFor, allDay, kind, status, completedAt, userId, durationMinutes, scheduleType, priority, deadlineAt, earliestStart, latestEnd, preferredStart) SELECT staged.id, staged.taskId, staged.title, staged.notes, staged.scheduledFor, staged.allDay, staged.kind, staged.status, staged.completedAt, staged.userId, staged.durationMinutes, staged.scheduleType, staged.priority, staged.deadlineAt, staged.earliestStart, staged.latestEnd, staged.preferredStart FROM bootstrap_occurrences AS staged WHERE staged.userId = :userId AND staged.generation = :generation AND staged.id NOT IN (SELECT occurrenceId FROM pending_completions WHERE userId = :userId) AND staged.id NOT IN (SELECT targetOccurrenceId FROM pending_mutations WHERE userId = :userId AND targetOccurrenceId IS NOT NULL) AND staged.id NOT IN (SELECT localOccurrenceId FROM pending_mutations WHERE userId = :userId AND localOccurrenceId IS NOT NULL) AND staged.id NOT IN (SELECT serverOccurrenceId FROM pending_mutations WHERE userId = :userId AND serverOccurrenceId IS NOT NULL)")
    suspend fun mergeStagedRows(userId: String, generation: String)

    @Query("SELECT * FROM pending_completions WHERE userId = :userId ORDER BY queuedAt ASC")
    suspend fun pendingCompletions(userId: String): List<PendingCompletion>

    @Query("""
        SELECT pending_completions.* FROM pending_completions
        WHERE pending_completions.userId = :userId AND pending_completions.state = 'queued'
          AND pending_completions.nextAttemptAt <= :now
          AND NOT EXISTS (
            SELECT 1 FROM pending_mutations
            WHERE pending_mutations.userId = :userId AND (
              pending_mutations.localOccurrenceId = pending_completions.occurrenceId OR
              pending_mutations.serverOccurrenceId = pending_completions.occurrenceId OR
              pending_mutations.targetOccurrenceId = pending_completions.occurrenceId
            )
          )
        ORDER BY pending_completions.queuedAt ASC
    """)
    suspend fun dueCompletions(userId: String, now: Long): List<PendingCompletion>

    @Query("DELETE FROM pending_completions WHERE userId = :userId AND idempotencyKey = :key")
    suspend fun removePendingCompletion(userId: String, key: String)

    @Query("UPDATE pending_completions SET retryCount = :retryCount, lastAttemptAt = :attemptAt, nextAttemptAt = :nextAttemptAt, lastErrorCode = :errorCode, lastError = :message WHERE userId = :userId AND idempotencyKey = :key AND state = 'queued'")
    suspend fun markCompletionRetry(userId: String, key: String, retryCount: Int, attemptAt: Long, nextAttemptAt: Long, errorCode: String?, message: String)

    @Query("UPDATE pending_completions SET state = :state, lastAttemptAt = :attemptAt, lastErrorCode = :errorCode, lastError = :message WHERE userId = :userId AND idempotencyKey = :key AND state = 'queued'")
    suspend fun markCompletionTerminal(userId: String, key: String, state: String, attemptAt: Long, errorCode: String?, message: String)

    @Query("UPDATE pending_completions SET nextAttemptAt = 0, lastErrorCode = NULL, lastError = NULL WHERE userId = :userId AND state = 'queued' AND lastErrorCode = 'AUTH_REQUIRED'")
    suspend fun resetAuthRequiredCompletions(userId: String)

    @Query("SELECT occurrenceId FROM pending_completions WHERE userId = :userId AND idempotencyKey = :key AND state <> 'queued'")
    suspend fun terminalOccurrenceId(userId: String, key: String): String?

    @Query("UPDATE cached_occurrences SET status = 'pending', completedAt = NULL WHERE userId = :userId AND id = :occurrenceId")
    suspend fun restorePendingOccurrence(userId: String, occurrenceId: String)

    @Query("DELETE FROM pending_completions WHERE idempotencyKey = :key AND userId = :userId AND state <> 'queued'")
    suspend fun deleteTerminalCompletion(userId: String, key: String)

    @Transaction
    suspend fun dismissTerminalCompletion(userId: String, key: String) {
        val occurrenceId = terminalOccurrenceId(userId, key) ?: return
        restorePendingOccurrence(userId, occurrenceId)
        deleteTerminalCompletion(userId, key)
    }

    @Query("UPDATE cached_occurrences SET userId = :userId WHERE userId = ''")
    suspend fun claimLegacyOccurrences(userId: String)

    @Query("UPDATE pending_completions SET userId = :userId WHERE userId = ''")
    suspend fun claimLegacyCompletions(userId: String)

    @Query("DELETE FROM cached_occurrences WHERE userId = :userId")
    suspend fun clearUserOccurrences(userId: String)

    @Query("DELETE FROM cached_occurrences WHERE userId = :userId AND id = :occurrenceId")
    suspend fun deleteOccurrence(userId: String, occurrenceId: String)

    @Query("SELECT COUNT(*) FROM pending_mutations WHERE userId = :userId")
    suspend fun pendingMutationCount(userId: String): Int

    @Transaction
    suspend fun replaceOccurrencesSnapshot(userId: String, rows: List<CachedOccurrence>) {
        clearUserOccurrences(userId)
        if (rows.isNotEmpty()) replaceOccurrences(rows)
    }

}

internal data class OccurrenceDelta(
    val revision: Long,
    val occurrenceId: String,
    val isDeleted: Boolean,
    val occurrence: CachedOccurrence?,
)

@Database(
    entities = [CachedOccurrence::class, PendingCompletion::class, PendingMutation::class, SyncCheckpoint::class, BootstrapOccurrence::class, PendingFocusReconciliation::class, OccurrenceHistory::class, FocusSessionHistory::class, DailyReviewEntry::class, PendingHabitSkip::class],
    version = 8,
    exportSchema = true,
)
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
                db.execSQL("""
                    DELETE FROM pending_completions
                    WHERE rowid NOT IN (
                        SELECT MIN(rowid) FROM pending_completions GROUP BY occurrenceId
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE UNIQUE INDEX index_pending_completions_occurrenceId " +
                        "ON pending_completions(occurrenceId)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_occurrences ADD COLUMN userId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pending_completions ADD COLUMN state TEXT NOT NULL DEFAULT 'queued'")
                db.execSQL("ALTER TABLE pending_completions ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_completions ADD COLUMN lastAttemptAt INTEGER")
                db.execSQL("ALTER TABLE pending_completions ADD COLUMN nextAttemptAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_completions ADD COLUMN lastErrorCode TEXT")
                db.execSQL("ALTER TABLE pending_completions ADD COLUMN userId TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_occurrences ADD COLUMN durationMinutes INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cached_occurrences_userId_status_scheduledFor " +
                        "ON cached_occurrences(userId, status, scheduledFor)",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_mutations (
                      operationId TEXT NOT NULL PRIMARY KEY,
                      userId TEXT NOT NULL,
                      operationType TEXT NOT NULL,
                      queueOrder INTEGER NOT NULL,
                      queuedAt INTEGER NOT NULL,
                      state TEXT NOT NULL DEFAULT 'queued',
                      retryCount INTEGER NOT NULL DEFAULT 0,
                      lastAttemptAt INTEGER,
                      nextAttemptAt INTEGER NOT NULL DEFAULT 0,
                      lastErrorCode TEXT,
                      lastError TEXT,
                      localOccurrenceId TEXT,
                      serverOccurrenceId TEXT,
                      targetOccurrenceId TEXT,
                      title TEXT,
                      notes TEXT,
                      scheduledFor TEXT,
                      allDay INTEGER,
                      durationMinutes INTEGER,
                      CHECK (queueOrder > 0),
                      CHECK (state IN ('queued', 'terminal')),
                      CHECK (retryCount >= 0),
                      CHECK (nextAttemptAt >= 0),
                      CHECK (
                        (operationType = 'create_task' AND localOccurrenceId IS NOT NULL
                          AND targetOccurrenceId IS NULL AND title IS NOT NULL AND title <> ''
                          AND allDay IS NOT NULL AND (allDay = 0 OR scheduledFor IS NOT NULL)
                          AND (durationMinutes IS NULL OR durationMinutes BETWEEN 5 AND 1440))
                        OR
                        (operationType = 'reschedule_occurrence' AND targetOccurrenceId IS NOT NULL
                          AND localOccurrenceId IS NULL AND scheduledFor IS NOT NULL
                          AND serverOccurrenceId IS NULL AND title IS NULL AND notes IS NULL
                          AND allDay IS NULL AND durationMinutes IS NULL)
                      )
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_mutations_userId_state_nextAttemptAt_queueOrder ON pending_mutations(userId, state, nextAttemptAt, queueOrder)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_mutations_queueOrder ON pending_mutations(queueOrder)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_checkpoints (
                      userId TEXT NOT NULL PRIMARY KEY,
                      revision INTEGER NOT NULL,
                      bootstrapped INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bootstrap_occurrences (
                      userId TEXT NOT NULL,
                      generation TEXT NOT NULL,
                      id TEXT NOT NULL,
                      taskId TEXT NOT NULL,
                      title TEXT NOT NULL,
                      notes TEXT,
                      scheduledFor TEXT,
                      allDay INTEGER NOT NULL,
                      kind TEXT NOT NULL,
                      status TEXT NOT NULL,
                      completedAt TEXT,
                      durationMinutes INTEGER,
                      PRIMARY KEY(userId, generation, id)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_focus_reconciliations (
                      operationId TEXT NOT NULL PRIMARY KEY,
                      userId TEXT NOT NULL,
                      oldOccurrenceId TEXT NOT NULL,
                      newOccurrenceId TEXT NOT NULL,
                      queuedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_focus_reconciliations_userId_queuedAt ON pending_focus_reconciliations(userId, queuedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_occurrences_userId_status_completedAt ON cached_occurrences(userId, status, completedAt)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS occurrence_history (
                      userId TEXT NOT NULL,
                      occurrenceId TEXT NOT NULL,
                      taskId TEXT NOT NULL,
                      title TEXT NOT NULL,
                      scheduledDate TEXT,
                      scheduledFor TEXT,
                      allDay INTEGER NOT NULL,
                      kind TEXT NOT NULL,
                      status TEXT NOT NULL,
                      completedAt TEXT,
                      durationMinutes INTEGER,
                      PRIMARY KEY(userId, occurrenceId)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_occurrence_history_userId_scheduledDate ON occurrence_history(userId, scheduledDate)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_occurrence_history_userId_kind_scheduledDate ON occurrence_history(userId, kind, scheduledDate)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS focus_session_history (
                      sessionId TEXT NOT NULL PRIMARY KEY,
                      userId TEXT NOT NULL,
                      occurrenceId TEXT NOT NULL,
                      occurrenceTitle TEXT NOT NULL,
                      plannedDurationSeconds INTEGER NOT NULL,
                      startedAtEpochMillis INTEGER NOT NULL,
                      finishedAtEpochMillis INTEGER,
                      status TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_focus_session_history_userId_startedAtEpochMillis ON focus_session_history(userId, startedAtEpochMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_focus_session_history_userId_occurrenceId ON focus_session_history(userId, occurrenceId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS daily_reviews (
                      userId TEXT NOT NULL,
                      reviewDate TEXT NOT NULL,
                      note TEXT,
                      completedAtEpochMillis INTEGER NOT NULL,
                      syncPending INTEGER NOT NULL DEFAULT 1,
                      PRIMARY KEY(userId, reviewDate)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_daily_reviews_userId_syncPending_reviewDate ON daily_reviews(userId, syncPending, reviewDate)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_habit_skips (
                      operationId TEXT NOT NULL PRIMARY KEY,
                      userId TEXT NOT NULL,
                      occurrenceId TEXT NOT NULL,
                      queuedAt INTEGER NOT NULL,
                      nextAttemptAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_habit_skips_userId_queuedAt ON pending_habit_skips(userId, queuedAt)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_habit_skips_userId_occurrenceId ON pending_habit_skips(userId, occurrenceId)")
            }
        }

        /** Scheduling fields are additive; Batch 4's version-7 history tables are preserved. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_mutations ADD COLUMN scheduleType TEXT")
                db.execSQL("ALTER TABLE pending_mutations ADD COLUMN priority TEXT")
                db.execSQL("ALTER TABLE pending_mutations ADD COLUMN deadlineAt TEXT")
                db.execSQL("ALTER TABLE pending_mutations ADD COLUMN earliestStart TEXT")
                db.execSQL("ALTER TABLE pending_mutations ADD COLUMN latestEnd TEXT")
                db.execSQL("ALTER TABLE pending_mutations ADD COLUMN preferredStart TEXT")

                db.execSQL("ALTER TABLE cached_occurrences ADD COLUMN scheduleType TEXT NOT NULL DEFAULT 'SOFT'")
                db.execSQL("ALTER TABLE cached_occurrences ADD COLUMN priority TEXT NOT NULL DEFAULT 'NORMAL'")
                db.execSQL("ALTER TABLE cached_occurrences ADD COLUMN deadlineAt TEXT")
                db.execSQL("ALTER TABLE cached_occurrences ADD COLUMN earliestStart TEXT")
                db.execSQL("ALTER TABLE cached_occurrences ADD COLUMN latestEnd TEXT")
                db.execSQL("ALTER TABLE cached_occurrences ADD COLUMN preferredStart TEXT")
                db.execSQL("UPDATE cached_occurrences SET scheduleType = 'FLEXIBLE' WHERE scheduledFor IS NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_occurrences_userId_scheduleType_scheduledFor ON cached_occurrences(userId, scheduleType, scheduledFor)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_occurrences_userId_deadlineAt_status ON cached_occurrences(userId, deadlineAt, status)")

                db.execSQL("ALTER TABLE bootstrap_occurrences ADD COLUMN scheduleType TEXT NOT NULL DEFAULT 'SOFT'")
                db.execSQL("ALTER TABLE bootstrap_occurrences ADD COLUMN priority TEXT NOT NULL DEFAULT 'NORMAL'")
                db.execSQL("ALTER TABLE bootstrap_occurrences ADD COLUMN deadlineAt TEXT")
                db.execSQL("ALTER TABLE bootstrap_occurrences ADD COLUMN earliestStart TEXT")
                db.execSQL("ALTER TABLE bootstrap_occurrences ADD COLUMN latestEnd TEXT")
                db.execSQL("ALTER TABLE bootstrap_occurrences ADD COLUMN preferredStart TEXT")
                db.execSQL("UPDATE bootstrap_occurrences SET scheduleType = 'FLEXIBLE' WHERE scheduledFor IS NULL")
            }
        }
    }
}
