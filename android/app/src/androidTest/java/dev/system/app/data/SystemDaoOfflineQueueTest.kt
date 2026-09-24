package dev.system.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SystemDaoOfflineQueueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "system-offline-${UUID.randomUUID()}.db"
    private lateinit var database: SystemDatabase

    @Before
    fun createDatabase() {
        database = openDatabase()
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized && database.isOpen) database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun queuedCompletionAndStableKeySurviveDatabaseReopen() = runBlocking {
        val occurrence = CachedOccurrence(
            id = "occurrence-1",
            taskId = "task-1",
            title = "50 push-ups",
            notes = null,
            scheduledFor = "2026-09-25T00:00:00Z",
            allDay = true,
            kind = "task",
            status = "pending",
            completedAt = null,
        )
        val request = PendingCompletion(
            idempotencyKey = "stable-operation-key",
            occurrenceId = occurrence.id,
            queuedAt = 1_790_280_000_000L,
        )

        database.systemDao().replaceOccurrences(listOf(occurrence))
        database.systemDao().queueCompletion(
            request,
            occurrence.copy(status = "completed", completedAt = "2026-09-24T12:00:00Z"),
        )
        database.close()

        database = openDatabase()
        val dao = database.systemDao()
        assertEquals("completed", dao.occurrence(occurrence.id)?.status)
        assertEquals(listOf(request), dao.pendingCompletions())

        dao.removePendingCompletion(request.idempotencyKey)
        assertTrue(dao.pendingCompletions().isEmpty())
    }

    private fun openDatabase() = Room.databaseBuilder(context, SystemDatabase::class.java, databaseName)
        .allowMainThreadQueries()
        .build()
}
