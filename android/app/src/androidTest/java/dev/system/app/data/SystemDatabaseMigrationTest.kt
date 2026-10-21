package dev.system.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SystemDatabaseMigrationTest {
    @Test
    fun oldestSupportedCacheMigratesThroughEveryVersionWithoutDroppingOfflineWork() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "system-v1-migration-${UUID.randomUUID()}.db"
        val legacy = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        legacy.execSQL("""
            CREATE TABLE cached_occurrences (
              id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, title TEXT NOT NULL,
              notes TEXT, scheduledFor TEXT, allDay INTEGER NOT NULL,
              status TEXT NOT NULL, completedAt TEXT
            )
        """.trimIndent())
        legacy.execSQL("""
            CREATE TABLE pending_completions (
              idempotencyKey TEXT NOT NULL PRIMARY KEY, occurrenceId TEXT NOT NULL,
              queuedAt INTEGER NOT NULL, lastError TEXT
            )
        """.trimIndent())
        legacy.execSQL("INSERT INTO cached_occurrences VALUES ('scheduled-v1', 'task-v1', 'Scheduled task', NULL, '2026-10-21T12:00:00Z', 0, 'pending', NULL)")
        legacy.execSQL("INSERT INTO cached_occurrences VALUES ('flexible-v1', 'task-flexible', 'Flexible task', NULL, NULL, 0, 'pending', NULL)")
        legacy.execSQL("INSERT INTO pending_completions VALUES ('stable-v1-key', 'scheduled-v1', 1000, NULL)")
        legacy.execSQL("INSERT INTO pending_completions VALUES ('duplicate-v1-key', 'scheduled-v1', 2000, NULL)")
        legacy.execSQL("PRAGMA user_version = 1")
        legacy.close()

        val migrated = Room.databaseBuilder(context, SystemDatabase::class.java, name)
            .addMigrations(
                SystemDatabase.MIGRATION_1_2,
                SystemDatabase.MIGRATION_2_3,
                SystemDatabase.MIGRATION_3_4,
                SystemDatabase.MIGRATION_4_5,
                SystemDatabase.MIGRATION_5_6,
                SystemDatabase.MIGRATION_6_7,
                SystemDatabase.MIGRATION_7_8,
            )
            .build()
        try {
            val dao = migrated.systemDao()
            migrated.openHelper.writableDatabase
            dao.claimLegacyOccurrences("user-a")
            dao.claimLegacyCompletions("user-a")

            val scheduled = dao.occurrence("user-a", "scheduled-v1")
            assertEquals("task", scheduled?.kind)
            assertEquals("SOFT", scheduled?.scheduleType)
            assertNull(scheduled?.durationMinutes)
            assertEquals("FLEXIBLE", dao.occurrence("user-a", "flexible-v1")?.scheduleType)
            assertEquals("stable-v1-key", dao.pendingCompletions("user-a").single().idempotencyKey)
            val batchFourTables = migrated.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('occurrence_history', 'focus_session_history', 'daily_reviews', 'pending_habit_skips')",
            )
            try {
                assertEquals(4, batchFourTables.count)
            } finally {
                batchFourTables.close()
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionThreeQueueAndCacheMigrateWithoutDroppingOfflineWork() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "system-migration-${UUID.randomUUID()}.db"
        val legacy = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        legacy.execSQL("""
            CREATE TABLE cached_occurrences (
              id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, title TEXT NOT NULL,
              notes TEXT, scheduledFor TEXT, allDay INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'task',
              status TEXT NOT NULL, completedAt TEXT
            )
        """.trimIndent())
        legacy.execSQL("""
            CREATE TABLE pending_completions (
              idempotencyKey TEXT NOT NULL PRIMARY KEY, occurrenceId TEXT NOT NULL,
              queuedAt INTEGER NOT NULL, lastError TEXT
            )
        """.trimIndent())
        legacy.execSQL("CREATE UNIQUE INDEX index_pending_completions_occurrenceId ON pending_completions(occurrenceId)")
        legacy.execSQL("INSERT INTO cached_occurrences VALUES ('occurrence-legacy', 'task-legacy', 'Legacy task', NULL, NULL, 1, 'task', 'completed', '2026-09-24T12:00:00Z')")
        legacy.execSQL("INSERT INTO pending_completions VALUES ('stable-legacy-key', 'occurrence-legacy', 1000, NULL)")
        legacy.execSQL("PRAGMA user_version = 3")
        legacy.close()

        val migrated = Room.databaseBuilder(context, SystemDatabase::class.java, name)
            .addMigrations(
                SystemDatabase.MIGRATION_1_2,
                SystemDatabase.MIGRATION_2_3,
                SystemDatabase.MIGRATION_3_4,
                SystemDatabase.MIGRATION_4_5,
                SystemDatabase.MIGRATION_5_6,
                SystemDatabase.MIGRATION_6_7,
                SystemDatabase.MIGRATION_7_8,
            )
            .build()
        try {
            val dao = migrated.systemDao()
            migrated.openHelper.writableDatabase
            dao.claimLegacyOccurrences("user-a")
            dao.claimLegacyCompletions("user-a")
            val completion = dao.pendingCompletions("user-a").single()
            assertEquals("stable-legacy-key", completion.idempotencyKey)
            assertEquals("queued", completion.state)
            assertEquals(0, completion.retryCount)
            assertEquals("user-a", completion.userId)
            val legacyOccurrence = dao.occurrence("user-a", "occurrence-legacy")
            assertEquals("user-a", legacyOccurrence?.userId)
            assertNull(legacyOccurrence?.durationMinutes)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionFourCacheMigratesWithNullDurationAndKeepsOfflineRows() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "system-v4-migration-${UUID.randomUUID()}.db"
        val legacy = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        legacy.execSQL("""
            CREATE TABLE cached_occurrences (
              id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, title TEXT NOT NULL,
              notes TEXT, scheduledFor TEXT, allDay INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'task',
              status TEXT NOT NULL, completedAt TEXT, userId TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        legacy.execSQL("""
            CREATE TABLE pending_completions (
              idempotencyKey TEXT NOT NULL PRIMARY KEY, occurrenceId TEXT NOT NULL,
              queuedAt INTEGER NOT NULL, lastError TEXT, state TEXT NOT NULL DEFAULT 'queued',
              retryCount INTEGER NOT NULL DEFAULT 0, lastAttemptAt INTEGER,
              nextAttemptAt INTEGER NOT NULL DEFAULT 0, lastErrorCode TEXT,
              userId TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        legacy.execSQL("CREATE UNIQUE INDEX index_pending_completions_occurrenceId ON pending_completions(occurrenceId)")
        legacy.execSQL("INSERT INTO cached_occurrences VALUES ('occurrence-v4', 'task-v4', 'Existing task', NULL, '2026-09-25T00:00:00Z', 0, 'task', 'pending', NULL, 'user-a')")
        legacy.execSQL("INSERT INTO pending_completions VALUES ('stable-v4-key', 'occurrence-v4', 1000, NULL, 'queued', 0, NULL, 0, NULL, 'user-a')")
        legacy.execSQL("PRAGMA user_version = 4")
        legacy.close()

        val migrated = Room.databaseBuilder(context, SystemDatabase::class.java, name)
            .addMigrations(
                SystemDatabase.MIGRATION_4_5,
                SystemDatabase.MIGRATION_5_6,
                SystemDatabase.MIGRATION_6_7,
                SystemDatabase.MIGRATION_7_8,
            )
            .build()
        try {
            val dao = migrated.systemDao()
            migrated.openHelper.writableDatabase
            assertNull(dao.occurrence("user-a", "occurrence-v4")?.durationMinutes)
            assertEquals("stable-v4-key", dao.pendingCompletions("user-a").single().idempotencyKey)
            val indexes = migrated.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_cached_occurrences_userId_status_scheduledFor'",
            )
            try {
                assertTrue(indexes.moveToFirst())
            } finally {
                indexes.close()
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun batchTwoVersionSixCacheMigratesToCurrentAndKeepsQueuedReschedule() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "system-v6-migration-${UUID.randomUUID()}.db"
        createVersionSixFixture(context, name)

        val migrated = Room.databaseBuilder(context, SystemDatabase::class.java, name)
            .addMigrations(SystemDatabase.MIGRATION_6_7, SystemDatabase.MIGRATION_7_8)
            .build()
        try {
            val dao = migrated.systemDao()
            migrated.openHelper.writableDatabase
            assertEquals("Batch 2 task", dao.occurrence("user-a", "occurrence-v6")?.title)
            assertEquals(45, dao.occurrence("user-a", "occurrence-v6")?.durationMinutes ?: -1)
            assertEquals("SOFT", dao.occurrence("user-a", "occurrence-v6")?.scheduleType)
            val queued = dao.pendingMutations("user-a").single()
            assertEquals("mutation-v6", queued.operationId)
            assertEquals("occurrence-v6", queued.targetOccurrenceId)
            assertNull(queued.scheduleType)
            val history = migrated.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('occurrence_history', 'focus_session_history', 'daily_reviews', 'pending_habit_skips')",
            )
            try {
                assertEquals(4, history.count)
            } finally {
                history.close()
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun batchFourVersionSevenDatabaseMigratesDirectlyToCurrentAndPreservesLocalData() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "system-v7-migration-${UUID.randomUUID()}.db"
        createVersionSixFixture(context, name)

        // Add the exact tables and indexes introduced by 6 -> 7, then mark this
        // as the persisted Batch 4 schema. The database is opened below using only
        // 7 -> 8 so Room validates the direct previous-release upgrade path.
        val legacy = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        legacy.execSQL("INSERT INTO cached_occurrences VALUES ('occurrence-v7-flexible', 'task-v7-flexible', 'Flexible V7 task', NULL, NULL, 0, 'task', 'pending', NULL, 'user-a', NULL)")
        legacy.execSQL("INSERT INTO sync_checkpoints VALUES ('user-a', 17, 1)")
        legacy.execSQL("""
            INSERT INTO bootstrap_occurrences (
              userId, generation, id, taskId, title, notes, scheduledFor, allDay, kind, status,
              completedAt, durationMinutes
            ) VALUES ('user-a', 'generation-v7', 'bootstrap-v7-flexible', 'task-bootstrap-v7',
              'Staged flexible task', NULL, NULL, 0, 'task', 'pending', NULL, NULL)
        """.trimIndent())
        legacy.execSQL("""
            CREATE TABLE occurrence_history (
              userId TEXT NOT NULL, occurrenceId TEXT NOT NULL, taskId TEXT NOT NULL, title TEXT NOT NULL,
              scheduledDate TEXT, scheduledFor TEXT, allDay INTEGER NOT NULL, kind TEXT NOT NULL,
              status TEXT NOT NULL, completedAt TEXT, durationMinutes INTEGER,
              PRIMARY KEY(userId, occurrenceId)
            )
        """.trimIndent())
        legacy.execSQL("CREATE INDEX index_occurrence_history_userId_scheduledDate ON occurrence_history(userId, scheduledDate)")
        legacy.execSQL("CREATE INDEX index_occurrence_history_userId_kind_scheduledDate ON occurrence_history(userId, kind, scheduledDate)")
        legacy.execSQL("""
            CREATE TABLE focus_session_history (
              sessionId TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, occurrenceId TEXT NOT NULL,
              occurrenceTitle TEXT NOT NULL, plannedDurationSeconds INTEGER NOT NULL,
              startedAtEpochMillis INTEGER NOT NULL, finishedAtEpochMillis INTEGER, status TEXT NOT NULL
            )
        """.trimIndent())
        legacy.execSQL("CREATE INDEX index_focus_session_history_userId_startedAtEpochMillis ON focus_session_history(userId, startedAtEpochMillis)")
        legacy.execSQL("CREATE INDEX index_focus_session_history_userId_occurrenceId ON focus_session_history(userId, occurrenceId)")
        legacy.execSQL("""
            CREATE TABLE daily_reviews (
              userId TEXT NOT NULL, reviewDate TEXT NOT NULL, note TEXT,
              completedAtEpochMillis INTEGER NOT NULL, syncPending INTEGER NOT NULL DEFAULT 1,
              PRIMARY KEY(userId, reviewDate)
            )
        """.trimIndent())
        legacy.execSQL("CREATE INDEX index_daily_reviews_userId_syncPending_reviewDate ON daily_reviews(userId, syncPending, reviewDate)")
        legacy.execSQL("""
            CREATE TABLE pending_habit_skips (
              operationId TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, occurrenceId TEXT NOT NULL,
              queuedAt INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        legacy.execSQL("CREATE INDEX index_pending_habit_skips_userId_queuedAt ON pending_habit_skips(userId, queuedAt)")
        legacy.execSQL("CREATE UNIQUE INDEX index_pending_habit_skips_userId_occurrenceId ON pending_habit_skips(userId, occurrenceId)")
        legacy.execSQL("""
            INSERT INTO occurrence_history VALUES (
              'user-a', 'history-v7', 'task-history-v7', 'Completed V7 task', '2026-10-20',
              '2026-10-20T09:00:00Z', 0, 'task', 'completed', '2026-10-20T09:45:00Z', 45
            )
        """.trimIndent())
        legacy.execSQL("""
            INSERT INTO focus_session_history VALUES (
              'session-v7', 'user-a', 'history-v7', 'Completed V7 task', 2700, 1792486800000, 1792489500000, 'COMPLETED'
            )
        """.trimIndent())
        legacy.execSQL("INSERT INTO daily_reviews (userId, reviewDate, note, completedAtEpochMillis) VALUES ('user-a', '2026-10-20', 'V7 review', 1792490000000)")
        legacy.execSQL("INSERT INTO pending_habit_skips (operationId, userId, occurrenceId, queuedAt) VALUES ('skip-v7', 'user-a', 'habit-occurrence-v7', 1792490000000)")
        legacy.execSQL("PRAGMA user_version = 7")
        legacy.close()

        val migrated = Room.databaseBuilder(context, SystemDatabase::class.java, name)
            .addMigrations(SystemDatabase.MIGRATION_7_8)
            .build()
        try {
            val dao = migrated.systemDao()
            migrated.openHelper.writableDatabase

            val scheduled = dao.occurrence("user-a", "occurrence-v6")
            assertEquals("Batch 2 task", scheduled?.title)
            assertEquals(45, scheduled?.durationMinutes)
            assertEquals("SOFT", scheduled?.scheduleType)
            assertEquals("NORMAL", scheduled?.priority)

            val flexible = dao.occurrence("user-a", "occurrence-v7-flexible")
            assertEquals("FLEXIBLE", flexible?.scheduleType)
            assertEquals("NORMAL", flexible?.priority)
            assertNull(flexible?.deadlineAt)

            val queued = dao.pendingMutations("user-a").single()
            assertEquals("mutation-v6", queued.operationId)
            assertEquals("occurrence-v6", queued.targetOccurrenceId)
            assertNull(queued.scheduleType)
            assertEquals(17L, dao.syncCheckpoint("user-a")?.revision)

            val history = dao.occurrenceHistoryRange("user-a", "2026-10-20", "2026-10-20").single()
            assertEquals("history-v7", history.occurrenceId)
            assertEquals("Completed V7 task", history.title)
            assertEquals("completed", history.status)
            val focus = dao.focusHistoryRange("user-a", 1792486800000, 1792489500001).single()
            assertEquals("session-v7", focus.sessionId)
            assertEquals("history-v7", focus.occurrenceId)
            val review = dao.dailyReview("user-a", "2026-10-20")
            assertEquals("V7 review", review?.note)
            assertEquals(true, review?.syncPending)
            assertEquals("skip-v7", dao.dueHabitSkips("user-a", Long.MAX_VALUE, 10).single().operationId)

            val staged = migrated.openHelper.writableDatabase.query(
                "SELECT scheduleType, priority FROM bootstrap_occurrences WHERE id = 'bootstrap-v7-flexible'",
            )
            try {
                assertTrue(staged.moveToFirst())
                assertEquals("FLEXIBLE", staged.getString(0))
                assertEquals("NORMAL", staged.getString(1))
            } finally {
                staged.close()
            }

            val tables = migrated.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('occurrence_history', 'focus_session_history', 'daily_reviews', 'pending_habit_skips')",
            )
            try {
                assertEquals(4, tables.count)
            } finally {
                tables.close()
            }

            val expectedIndexes = setOf(
                "index_cached_occurrences_userId_scheduleType_scheduledFor",
                "index_cached_occurrences_userId_deadlineAt_status",
                "index_occurrence_history_userId_scheduledDate",
                "index_occurrence_history_userId_kind_scheduledDate",
                "index_focus_session_history_userId_startedAtEpochMillis",
                "index_focus_session_history_userId_occurrenceId",
                "index_daily_reviews_userId_syncPending_reviewDate",
                "index_pending_habit_skips_userId_queuedAt",
                "index_pending_habit_skips_userId_occurrenceId",
            )
            val actualIndexes = mutableSetOf<String>()
            val indexes = migrated.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name IN (${expectedIndexes.joinToString(",") { "'${it}'" }})",
            )
            try {
                while (indexes.moveToNext()) actualIndexes += indexes.getString(0)
            } finally {
                indexes.close()
            }
            assertEquals(expectedIndexes, actualIndexes)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionFiveCacheMigratesWithTypedMutationQueueAndCheckpointTables() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "system-v5-migration-${UUID.randomUUID()}.db"
        val legacy = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        legacy.execSQL("""
            CREATE TABLE cached_occurrences (
              id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, title TEXT NOT NULL,
              notes TEXT, scheduledFor TEXT, allDay INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'task',
              status TEXT NOT NULL, completedAt TEXT, userId TEXT NOT NULL DEFAULT '', durationMinutes INTEGER
            )
        """.trimIndent())
        legacy.execSQL("""
            CREATE TABLE pending_completions (
              idempotencyKey TEXT NOT NULL PRIMARY KEY, occurrenceId TEXT NOT NULL,
              queuedAt INTEGER NOT NULL, lastError TEXT, state TEXT NOT NULL DEFAULT 'queued',
              retryCount INTEGER NOT NULL DEFAULT 0, lastAttemptAt INTEGER,
              nextAttemptAt INTEGER NOT NULL DEFAULT 0, lastErrorCode TEXT,
              userId TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        legacy.execSQL("CREATE UNIQUE INDEX index_pending_completions_occurrenceId ON pending_completions(occurrenceId)")
        legacy.execSQL("CREATE INDEX index_cached_occurrences_userId_status_scheduledFor ON cached_occurrences(userId, status, scheduledFor)")
        legacy.execSQL("INSERT INTO cached_occurrences VALUES ('occurrence-v5', 'task-v5', 'Existing v5 task', NULL, '2026-10-21T12:00:00Z', 0, 'task', 'pending', NULL, 'user-a', NULL)")
        legacy.execSQL("PRAGMA user_version = 5")
        legacy.close()

        val migrated = Room.databaseBuilder(context, SystemDatabase::class.java, name)
            .addMigrations(
                SystemDatabase.MIGRATION_5_6,
                SystemDatabase.MIGRATION_6_7,
                SystemDatabase.MIGRATION_7_8,
            )
            .build()
        try {
            val dao = migrated.systemDao()
            migrated.openHelper.writableDatabase
            assertEquals("Existing v5 task", dao.occurrence("user-a", "occurrence-v5")?.title)
            assertEquals("SOFT", dao.occurrence("user-a", "occurrence-v5")?.scheduleType)
            assertEquals("NORMAL", dao.occurrence("user-a", "occurrence-v5")?.priority)
            assertNull(dao.syncCheckpoint("user-a"))
            assertEquals(0, dao.pendingMutationCount("user-a"))

            val localId = "local:${UUID.randomUUID()}"
            val create = PendingMutation(
                operationId = localId.removePrefix("local:"),
                userId = "user-a",
                operationType = "create_task",
                localOccurrenceId = localId,
                title = "Offline task",
                scheduledFor = "2026-10-22T10:00:00Z",
                allDay = false,
            )
            val occurrence = CachedOccurrence(
                id = localId,
                taskId = localId,
                title = "Offline task",
                notes = null,
                scheduledFor = "2026-10-22T10:00:00Z",
                allDay = false,
                kind = "task",
                status = "pending",
                completedAt = null,
                userId = "user-a",
            )
            assertTrue(dao.storeCreateMutation(create, occurrence))
            val reschedule = PendingMutation(
                operationId = UUID.randomUUID().toString(),
                userId = "user-a",
                operationType = "reschedule_occurrence",
                targetOccurrenceId = localId,
                scheduledFor = "2026-10-22T11:00:00Z",
            )
            assertTrue(dao.storeRescheduleMutation(reschedule, occurrence.copy(scheduledFor = reschedule.scheduledFor)))
            val pending = dao.pendingMutations("user-a")
            assertEquals(listOf("create_task", "reschedule_occurrence"), pending.map(PendingMutation::operationType))
            assertTrue(pending[0].queueOrder < pending[1].queueOrder)
            assertEquals(pending[0].operationId, dao.nextDuePendingMutation("user-a", System.currentTimeMillis())?.operationId)

            val retryAt = System.currentTimeMillis() + 60_000L
            dao.markMutationRetry(
                userId = "user-a",
                operationId = pending[0].operationId,
                retryCount = 1,
                attemptAt = System.currentTimeMillis(),
                nextAttemptAt = retryAt,
                errorCode = "TEMPORARY_FAILURE",
                message = "retry later",
            )
            assertNull(dao.nextDuePendingMutation("user-a", System.currentTimeMillis()))
            assertEquals(retryAt, dao.nextMutationRetryAt("user-a"))

            val canonical = occurrence.copy(id = "server-occurrence", taskId = "server-task")
            dao.reconcileCreatedOccurrence("user-a", pending[0].operationId, localId, canonical)
            val focusMapping = dao.pendingFocusReconciliations("user-a").single()
            assertEquals(localId, focusMapping.oldOccurrenceId)
            assertEquals("server-occurrence", focusMapping.newOccurrenceId)
            assertEquals("server-occurrence", dao.pendingMutations("user-a").single().targetOccurrenceId)
            assertEquals("server-occurrence", dao.occurrence("user-a", "server-occurrence")?.id)

            val tables = migrated.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('pending_mutations', 'sync_checkpoints', 'bootstrap_occurrences', 'pending_focus_reconciliations')",
            )
            try {
                assertEquals(4, tables.count)
            } finally {
                tables.close()
            }

            val batchFourTables = migrated.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('occurrence_history', 'focus_session_history', 'daily_reviews', 'pending_habit_skips')",
            )
            try {
                assertEquals(4, batchFourTables.count)
            } finally {
                batchFourTables.close()
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    private fun createVersionSixFixture(context: Context, name: String) {
        val legacy = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        legacy.execSQL("""
            CREATE TABLE cached_occurrences (
              id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, title TEXT NOT NULL,
              notes TEXT, scheduledFor TEXT, allDay INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'task',
              status TEXT NOT NULL, completedAt TEXT, userId TEXT NOT NULL DEFAULT '', durationMinutes INTEGER
            )
        """.trimIndent())
        legacy.execSQL("""
            CREATE TABLE pending_completions (
              idempotencyKey TEXT NOT NULL PRIMARY KEY, occurrenceId TEXT NOT NULL,
              queuedAt INTEGER NOT NULL, lastError TEXT, state TEXT NOT NULL DEFAULT 'queued',
              retryCount INTEGER NOT NULL DEFAULT 0, lastAttemptAt INTEGER,
              nextAttemptAt INTEGER NOT NULL DEFAULT 0, lastErrorCode TEXT,
              userId TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        legacy.execSQL("CREATE UNIQUE INDEX index_pending_completions_occurrenceId ON pending_completions(occurrenceId)")
        legacy.execSQL("CREATE INDEX index_cached_occurrences_userId_status_scheduledFor ON cached_occurrences(userId, status, scheduledFor)")
        legacy.execSQL("CREATE INDEX index_cached_occurrences_userId_status_completedAt ON cached_occurrences(userId, status, completedAt)")
        legacy.execSQL("""
            CREATE TABLE pending_mutations (
              operationId TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, operationType TEXT NOT NULL,
              queueOrder INTEGER NOT NULL, queuedAt INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'queued',
              retryCount INTEGER NOT NULL DEFAULT 0, lastAttemptAt INTEGER,
              nextAttemptAt INTEGER NOT NULL DEFAULT 0, lastErrorCode TEXT, lastError TEXT,
              localOccurrenceId TEXT, serverOccurrenceId TEXT, targetOccurrenceId TEXT,
              title TEXT, notes TEXT, scheduledFor TEXT, allDay INTEGER, durationMinutes INTEGER,
              CHECK (queueOrder > 0), CHECK (state IN ('queued', 'terminal')), CHECK (retryCount >= 0),
              CHECK (nextAttemptAt >= 0),
              CHECK (
                (operationType = 'create_task' AND localOccurrenceId IS NOT NULL AND targetOccurrenceId IS NULL
                  AND title IS NOT NULL AND title <> '' AND allDay IS NOT NULL
                  AND (allDay = 0 OR scheduledFor IS NOT NULL)
                  AND (durationMinutes IS NULL OR durationMinutes BETWEEN 5 AND 1440))
                OR
                (operationType = 'reschedule_occurrence' AND targetOccurrenceId IS NOT NULL
                  AND localOccurrenceId IS NULL AND scheduledFor IS NOT NULL AND serverOccurrenceId IS NULL
                  AND title IS NULL AND notes IS NULL AND allDay IS NULL AND durationMinutes IS NULL)
              )
            )
        """.trimIndent())
        legacy.execSQL("CREATE INDEX index_pending_mutations_userId_state_nextAttemptAt_queueOrder ON pending_mutations(userId, state, nextAttemptAt, queueOrder)")
        legacy.execSQL("CREATE UNIQUE INDEX index_pending_mutations_queueOrder ON pending_mutations(queueOrder)")
        legacy.execSQL("""
            CREATE TABLE sync_checkpoints (
              userId TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, bootstrapped INTEGER NOT NULL
            )
        """.trimIndent())
        legacy.execSQL("""
            CREATE TABLE bootstrap_occurrences (
              userId TEXT NOT NULL, generation TEXT NOT NULL, id TEXT NOT NULL, taskId TEXT NOT NULL,
              title TEXT NOT NULL, notes TEXT, scheduledFor TEXT, allDay INTEGER NOT NULL,
              kind TEXT NOT NULL, status TEXT NOT NULL, completedAt TEXT, durationMinutes INTEGER,
              PRIMARY KEY(userId, generation, id)
            )
        """.trimIndent())
        legacy.execSQL("""
            CREATE TABLE pending_focus_reconciliations (
              operationId TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, oldOccurrenceId TEXT NOT NULL,
              newOccurrenceId TEXT NOT NULL, queuedAt INTEGER NOT NULL
            )
        """.trimIndent())
        legacy.execSQL("CREATE INDEX index_pending_focus_reconciliations_userId_queuedAt ON pending_focus_reconciliations(userId, queuedAt)")
        legacy.execSQL("INSERT INTO cached_occurrences VALUES ('occurrence-v6', 'task-v6', 'Batch 2 task', NULL, '2026-10-21T12:00:00Z', 0, 'task', 'pending', NULL, 'user-a', 45)")
        legacy.execSQL("""
            INSERT INTO pending_mutations (
              operationId, userId, operationType, queueOrder, queuedAt, targetOccurrenceId, scheduledFor
            ) VALUES ('mutation-v6', 'user-a', 'reschedule_occurrence', 1, 1000, 'occurrence-v6', '2026-10-21T13:00:00Z')
        """.trimIndent())
        legacy.execSQL("PRAGMA user_version = 6")
        legacy.close()
    }
}
