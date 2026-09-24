package dev.system.app.data

import android.content.Context
import dev.system.app.BuildConfig
import dev.system.app.widget.SystemWidget
import androidx.glance.appwidget.updateAll
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.util.UUID

class SystemRepository(private val context: Context, private val database: SystemDatabase) {
    private val dao = database.systemDao()
    private val mutableSyncState = MutableStateFlow(SyncState())
    val syncState = mutableSyncState.asStateFlow()
    private val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    ) {
        install(Auth)
        install(Postgrest)
    }

    val currentUser get() = client.auth.currentUserOrNull()
    val isAuthenticated = client.auth.sessionStatus.map { status ->
        when (status) {
            SessionStatus.Initializing -> null
            is SessionStatus.Authenticated -> true
            is SessionStatus.NotAuthenticated, is SessionStatus.RefreshFailure -> false
        }
    }
    val pendingOccurrences: Flow<List<CachedOccurrence>> get() = dao.observePending()
    val completionQueue: Flow<List<PendingCompletion>> get() = dao.observeCompletionQueue()
    val isConfigured: Boolean get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank() &&
        BuildConfig.SUPABASE_URL.startsWith("https://") && !BuildConfig.SUPABASE_URL.contains("example.")

    suspend fun requestEmailCode(email: String) {
        check(isConfigured) { "Add SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY to android/local.properties first." }
        client.auth.signInWith(OTP) {
            this.email = email.trim()
            createUser = true
        }
    }

    suspend fun verifyEmailCode(email: String, code: String) {
        client.auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = email.trim(), token = code.trim())
    }

    suspend fun signOut() {
        runCatching {
            val token = com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
            client.from("device_tokens").update(DeviceTokenStatus(enabled = false)) {
                filter { eq("token", token) }
            }
        }
        client.auth.signOut()
        dao.clearOccurrences()
        dao.clearCompletionQueue()
        SystemWidget().updateAll(context)
    }

    suspend fun complete(occurrenceId: String) {
        val row = dao.occurrence(occurrenceId) ?: return
        if (row.status != "pending") return
        val key = UUID.randomUUID().toString()
        dao.addPendingCompletion(PendingCompletion(idempotencyKey = key, occurrenceId = occurrenceId))
        dao.replaceOccurrences(listOf(row.copy(status = "completed", completedAt = Instant.now().toString())))
        SystemWidget().updateAll(context)
        try {
            syncNow()
        } catch (error: Throwable) {
            dev.system.app.sync.SyncWorker.enqueue(context)
            throw error
        }
    }

    suspend fun queueCompletion(occurrenceId: String) {
        val row = dao.occurrence(occurrenceId) ?: return
        if (row.status != "pending") return
        dao.addPendingCompletion(PendingCompletion(UUID.randomUUID().toString(), occurrenceId))
        dao.replaceOccurrences(listOf(row.copy(status = "completed", completedAt = Instant.now().toString())))
        SystemWidget().updateAll(context)
    }

    suspend fun syncNow() = withContext(Dispatchers.IO) {
        client.auth.awaitInitialization()
        if (currentUser == null) client.auth.loadFromStorage(autoRefresh = true)
        if (currentUser == null) return@withContext
        mutableSyncState.value = mutableSyncState.value.copy(syncing = true, error = null)
        try {
            val queued = dao.pendingCompletions()
            for (item in queued) {
                try {
                    client.postgrest.rpc(
                        "complete_task_occurrence",
                        CompletionParams(item.occurrenceId, item.idempotencyKey),
                    )
                    dao.removePendingCompletion(item.idempotencyKey)
                } catch (error: Throwable) {
                    dao.setCompletionError(item.idempotencyKey, error.message ?: "Could not sync completion")
                    throw error
                }
            }

            val upperBound = Instant.now().plusSeconds(120 * 86400L).toString()
            val columns = Columns.raw("""
            id,task_id,scheduled_for,all_day,status,completed_at,
            task:tasks!inner(title,notes,archived_at)
        """.trimIndent())
            val datedRows = client.from("task_occurrences").select(columns = columns) {
                filter {
                    eq("status", "pending")
                    filter("scheduled_for", FilterOperator.LTE, upperBound)
                }
                order("scheduled_for", Order.ASCENDING)
                limit(250)
            }
                .decodeList<OccurrenceResponse>()
                .filter { it.task.archivedAt == null }
                .map { it.toCache() }
            val unscheduledRows = client.from("task_occurrences").select(columns = columns) {
                filter {
                    eq("status", "pending")
                    exact("scheduled_for", null)
                }
                limit(100)
            }
                .decodeList<OccurrenceResponse>()
                .filter { it.task.archivedAt == null }
                .map { it.toCache() }
            val rows = datedRows + unscheduledRows

            dao.clearOccurrences()
            if (rows.isNotEmpty()) dao.replaceOccurrences(rows)
            registerFcmToken()
            SystemWidget().updateAll(context)
            mutableSyncState.value = SyncState(syncing = false, lastSyncedAt = Instant.now().toString())
        } catch (error: Throwable) {
            mutableSyncState.value = mutableSyncState.value.copy(
                syncing = false,
                error = error.message ?: "Could not sync tasks.",
            )
            throw error
        }
    }

    suspend fun loadGoals(): List<GoalProgress> = client.from("goal_progress")
        .select(columns = Columns.raw("goal_id,title,description,completed_count,target_count,completed")) {
            order("title", Order.ASCENDING)
        }
        .decodeList()

    fun retryCompletions() = dev.system.app.sync.SyncWorker.enqueue(context)

    private suspend fun registerFcmToken() {
        try {
            val token = com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
            client.from("device_tokens").upsert(DeviceTokenInsert(token = token)) {
                onConflict = "token"
            }
        } catch (_: Throwable) {
            // Firebase is not configured until google-services.json is added.
        }
    }
}

data class SyncState(
    val syncing: Boolean = true,
    val error: String? = null,
    val lastSyncedAt: String? = null,
)

@Serializable
private data class OccurrenceResponse(
    val id: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("scheduled_for") val scheduledFor: String?,
    @SerialName("all_day") val allDay: Boolean,
    val status: String,
    @SerialName("completed_at") val completedAt: String?,
    val task: TaskResponse,
) {
    fun toCache() = CachedOccurrence(id, taskId, task.title, task.notes, scheduledFor, allDay, task.kind, status, completedAt)
}

@Serializable
private data class TaskResponse(
    val title: String,
    val notes: String?,
    val kind: String,
    @SerialName("archived_at") val archivedAt: String?,
)

@Serializable
private data class DeviceTokenInsert(val token: String, val platform: String = "android", val enabled: Boolean = true)

@Serializable
private data class DeviceTokenStatus(val enabled: Boolean)

@Serializable
private data class CompletionParams(
    @SerialName("p_occurrence_id") val occurrenceId: String,
    @SerialName("p_idempotency_key") val idempotencyKey: String,
)

@Serializable
data class GoalProgress(
    @SerialName("goal_id") val goalId: String,
    val title: String,
    val description: String?,
    @SerialName("completed_count") val completedCount: Int,
    @SerialName("target_count") val targetCount: Int,
    val completed: Boolean,
)
