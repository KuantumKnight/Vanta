package dev.system.app.data

import android.content.Context
import android.content.Intent
import dev.system.app.BuildConfig
import dev.system.app.widget.SystemWidget
import androidx.glance.appwidget.updateAll
import com.google.firebase.FirebaseApp
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.handleDeeplinks
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.util.UUID

class SystemRepository(private val context: Context, private val database: SystemDatabase) {
    private val dao = database.systemDao()
    private val syncMutex = Mutex()
    private val mutableSyncState = MutableStateFlow(SyncState())
    val syncState = mutableSyncState.asStateFlow()
    private val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    ) {
        install(Auth) {
            flowType = FlowType.PKCE
            scheme = AUTH_SCHEME
            host = AUTH_HOST
        }
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

    suspend fun requestEmailSignIn(email: String) {
        check(isConfigured) { "Add SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY to android/local.properties first." }
        client.auth.signInWith(OTP, redirectUrl = AUTH_REDIRECT_URI) {
            this.email = email.trim()
            createUser = true
        }
    }

    fun handleAuthDeeplink(intent: Intent) = client.handleDeeplinks(intent)

    suspend fun signOut() = syncMutex.withLock {
        if (dao.pendingCompletions().isNotEmpty()) {
            try {
                syncLocked()
            } catch (error: Throwable) {
                throw SignOutBlockedException(
                    "Connect to the internet and sync saved completions before signing out.",
                    error,
                )
            }
            if (dao.pendingCompletions().isNotEmpty()) {
                throw SignOutBlockedException("Sync saved completions before signing out.")
            }
        }

        unregisterFcmTokens()
        client.auth.signOut()
        dao.clearOccurrences()
        SystemWidget().updateAll(context)
    }

    suspend fun complete(occurrenceId: String) = syncMutex.withLock {
        val row = dao.occurrence(occurrenceId) ?: return@withLock
        if (row.status != "pending") return@withLock
        val key = UUID.randomUUID().toString()
        dao.queueCompletion(
            PendingCompletion(idempotencyKey = key, occurrenceId = occurrenceId),
            row.copy(status = "completed", completedAt = Instant.now().toString()),
        )
        SystemWidget().updateAll(context)
        try {
            syncLocked()
        } catch (error: Throwable) {
            dev.system.app.sync.SyncWorker.enqueue(context)
            throw error
        }
    }

    suspend fun queueCompletion(occurrenceId: String) = syncMutex.withLock {
        val row = dao.occurrence(occurrenceId) ?: return@withLock
        if (row.status != "pending") return@withLock
        dao.queueCompletion(
            PendingCompletion(UUID.randomUUID().toString(), occurrenceId),
            row.copy(status = "completed", completedAt = Instant.now().toString()),
        )
        SystemWidget().updateAll(context)
    }

    suspend fun syncNow() = syncMutex.withLock { syncLocked() }

    private suspend fun syncLocked() {
        withContext(Dispatchers.IO) {
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
                        dao.setCompletionError(item.idempotencyKey, "Could not sync completion yet")
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
                    error = "Could not sync tasks.",
                )
                throw error
            }
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
            rememberFcmToken(token)
            client.from("device_tokens").upsert(DeviceTokenInsert(token = token)) {
                onConflict = "token"
            }
        } catch (_: Throwable) {
            // Firebase is not configured until google-services.json is added.
        }
    }

    private suspend fun unregisterFcmTokens() {
        val preferences = context.getSharedPreferences("system", Context.MODE_PRIVATE)
        val tokens = preferences.getStringSet(FCM_TOKENS_PREFERENCE, emptySet()).orEmpty().toMutableSet()
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            val currentToken = try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
            } catch (error: Throwable) {
                if (tokens.isEmpty()) {
                    throw SignOutBlockedException(
                        "Connect to the internet before signing out so SYSTEM can disable reminders on this device.",
                        error,
                    )
                }
                null
            }
            if (currentToken != null) tokens += currentToken
        }
        if (tokens.isEmpty()) return

        try {
            tokens.forEach { token ->
                client.from("device_tokens").update(DeviceTokenStatus(enabled = false)) {
                    filter { eq("token", token) }
                }
            }
        } catch (error: Throwable) {
            throw SignOutBlockedException(
                "Connect to the internet before signing out so SYSTEM can disable reminders on this device.",
                error,
            )
        }
        preferences.edit().remove(FCM_TOKENS_PREFERENCE).commit()
    }

    private fun rememberFcmToken(token: String) {
        val preferences = context.getSharedPreferences("system", Context.MODE_PRIVATE)
        val tokens = preferences.getStringSet(FCM_TOKENS_PREFERENCE, emptySet()).orEmpty().toMutableSet()
        tokens += token
        check(preferences.edit().putStringSet(FCM_TOKENS_PREFERENCE, tokens).commit()) {
            "Could not save the notification token for sign-out cleanup."
        }
    }

    companion object {
        private const val AUTH_SCHEME = "dev.system.app"
        private const val AUTH_HOST = "auth"
        private const val AUTH_REDIRECT_URI = "$AUTH_SCHEME://$AUTH_HOST"
        private const val FCM_TOKENS_PREFERENCE = "registered_fcm_tokens"
    }
}

class SignOutBlockedException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

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
