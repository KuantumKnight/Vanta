package dev.system.app.data

import android.content.Context
import android.content.Intent
import dev.system.app.BuildConfig
import dev.system.app.widget.SystemWidget
import dev.system.app.sync.CompletionReplayOutcome
import dev.system.app.sync.CompletionReplayPolicy
import dev.system.app.sync.SyncWorker
import androidx.glance.appwidget.updateAll
import com.google.firebase.FirebaseApp
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

internal const val OCCURRENCE_SELECT =
    "id,task_id,scheduled_for,all_day,status,completed_at,task:tasks!inner(title,notes,kind,archived_at)"

@OptIn(ExperimentalCoroutinesApi::class)
class SystemRepository(private val context: Context, private val database: SystemDatabase) {
    private val dao = database.systemDao()
    private val syncMutex = Mutex()
    private val mutableSyncState = MutableStateFlow(SyncState())
    val syncState = mutableSyncState.asStateFlow()
    private val mutableAuthLinkError = MutableStateFlow<String?>(null)
    val authLinkError = mutableAuthLinkError.asStateFlow()
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
        if (status == SessionStatus.Initializing) return@map null
        cacheUserId(status) != null
    }
    private val activeUserId = client.auth.sessionStatus.map { status ->
        cacheUserId(status)
    }.distinctUntilChanged()
    val pendingOccurrences: Flow<List<CachedOccurrence>> = activeUserId.flatMapLatest { userId ->
        if (userId == null) flowOf(emptyList()) else dao.observePending(userId)
    }
    val completionQueue: Flow<List<PendingCompletion>> = activeUserId.flatMapLatest { userId ->
        if (userId == null) flowOf(emptyList()) else dao.observeCompletionQueue(userId)
    }
    val isConfigured: Boolean get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank() &&
        BuildConfig.SUPABASE_URL.startsWith("https://") && !BuildConfig.SUPABASE_URL.contains("example.")

    suspend fun requestEmailSignIn(email: String) {
        check(isConfigured) { "Add SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY to android/local.properties first." }
        client.auth.signInWith(OTP, redirectUrl = AUTH_REDIRECT_URI) {
            this.email = email.trim()
            createUser = true
        }
    }

    suspend fun verifyEmailSignInCode(email: String, token: String) {
        client.auth.verifyEmailOtp(
            type = OtpType.Email.EMAIL,
            email = email.trim(),
            token = token.trim(),
        )
    }

    fun handleAuthDeeplink(intent: Intent) {
        mutableAuthLinkError.value = null
        client.handleDeeplinks(
            intent = intent,
            onSessionSuccess = { mutableAuthLinkError.value = null },
            onError = {
                mutableAuthLinkError.value = "This older sign-in link couldn’t be verified. Use the code from a fresh email instead."
            },
        )
    }

    fun clearAuthLinkError() {
        mutableAuthLinkError.value = null
    }

    suspend fun signOut() = syncMutex.withLock {
        val userId = requireAuthenticatedUserId()
        if (dao.pendingCompletions(userId).isNotEmpty()) {
            try {
                syncLocked()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw SignOutBlockedException(
                    "Connect to the internet and sync saved completions before signing out.",
                    error,
                )
            }
            if (dao.pendingCompletions(userId).isNotEmpty()) {
                throw SignOutBlockedException("Sync saved completions before signing out.")
            }
        }

        unregisterFcmTokens()
        client.auth.signOut()
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().remove(LOCAL_USER_ID_PREFERENCE).apply()
        dao.clearOccurrences(userId)
        SystemWidget().updateAll(context)
    }

    suspend fun complete(occurrenceId: String) = syncMutex.withLock {
        val userId = requireLocalUserId()
        val row = dao.occurrence(userId, occurrenceId) ?: return@withLock
        if (row.status != "pending") return@withLock
        val key = UUID.randomUUID().toString()
        dao.queueCompletion(
            PendingCompletion(idempotencyKey = key, userId = userId, occurrenceId = occurrenceId),
            row.copy(status = "completed", completedAt = Instant.now().toString()),
        )
        SystemWidget().updateAll(context)
        try {
            val result = syncLocked()
            if (result.retryablePending) SyncWorker.enqueue(context)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            dev.system.app.sync.SyncWorker.enqueue(context)
            throw error
        }
    }

    suspend fun queueCompletion(occurrenceId: String) = syncMutex.withLock {
        val userId = requireLocalUserId()
        val row = dao.occurrence(userId, occurrenceId) ?: return@withLock
        if (row.status != "pending") return@withLock
        dao.queueCompletion(
            PendingCompletion(UUID.randomUUID().toString(), userId, occurrenceId),
            row.copy(status = "completed", completedAt = Instant.now().toString()),
        )
        SystemWidget().updateAll(context)
    }

    suspend fun pendingSnapshotForCurrentUser(): List<CachedOccurrence> {
        val userId = localUserId(loadFromStorage = true) ?: return emptyList()
        return dao.pendingSnapshot(userId)
    }

    suspend fun syncNow(): SyncResult = syncMutex.withLock { syncLocked() }

    private suspend fun syncLocked(): SyncResult {
        return withContext(Dispatchers.IO) {
            val userId = authenticatedUserId(loadFromStorage = true)
            if (userId == null) {
                val localUserId = localUserId(loadFromStorage = false)
                if (localUserId != null) {
                    dao.markCompletionsAuthRequired(
                        localUserId,
                        System.currentTimeMillis(),
                        "Sign in again to sync this saved completion.",
                    )
                }
                mutableSyncState.value = SyncState(
                    syncing = false,
                    error = if (localUserId == null) null else "Sign in again to sync. Saved tasks remain available on this phone.",
                )
                return@withContext SyncResult(authRequired = localUserId != null)
            }
            mutableSyncState.value = mutableSyncState.value.copy(syncing = true, error = null)
            try {
                for (item in dao.legacyPendingCompletions()) {
                    val visibleToCurrentUser = client.from("task_occurrences")
                        .select(columns = Columns.raw("id")) {
                            filter { eq("id", item.occurrenceId) }
                        }
                        .decodeList<OccurrenceIdResponse>()
                        .isNotEmpty()
                    if (visibleToCurrentUser) dao.adoptLegacyCompletion(userId, item.idempotencyKey)
                }
                val queued = dao.eligibleCompletions(userId, System.currentTimeMillis())
                var authRequired = false
                for (item in queued) {
                    val attemptedAt = System.currentTimeMillis()
                    dao.markCompletionAttempt(userId, item.idempotencyKey, attemptedAt)
                    try {
                        client.postgrest.rpc(
                            "complete_task_occurrence",
                            CompletionParams(item.occurrenceId, item.idempotencyKey),
                        )
                        dao.removePendingCompletion(userId, item.idempotencyKey)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        val failure = CompletionReplayPolicy.describe(error)
                        val outcome = CompletionReplayPolicy.classify(failure)
                        if (outcome == CompletionReplayOutcome.ALREADY_APPLIED || outcome == CompletionReplayOutcome.SUCCESS) {
                            dao.removePendingCompletion(userId, item.idempotencyKey)
                            continue
                        }

                        val retryable = outcome == CompletionReplayOutcome.RETRYABLE
                        val nextAttemptAt = if (retryable) {
                            attemptedAt + CompletionReplayPolicy.retryDelayMillis(item.retryCount + 1)
                        } else {
                            null
                        }
                        val errorCode = failure.code ?: failure.status?.toString() ?: when (outcome) {
                            CompletionReplayOutcome.AUTH_REQUIRED -> "AUTH_REQUIRED"
                            CompletionReplayOutcome.RETRYABLE -> "RETRYABLE"
                            CompletionReplayOutcome.PERMANENT_FAILURE -> "PERMANENT_FAILURE"
                            CompletionReplayOutcome.CONFLICT -> "CONFLICT"
                            else -> null
                        }
                        dao.setCompletionOutcome(
                            userId = userId,
                            key = item.idempotencyKey,
                            state = outcome.name,
                            retryCount = item.retryCount + if (retryable) 1 else 0,
                            attemptedAt = attemptedAt,
                            nextAttemptAt = nextAttemptAt,
                            errorCode = errorCode,
                            message = CompletionReplayPolicy.userMessage(outcome, failure),
                        )
                        if (outcome == CompletionReplayOutcome.AUTH_REQUIRED) {
                            dao.markCompletionsAuthRequired(
                                userId,
                                attemptedAt,
                                CompletionReplayPolicy.userMessage(outcome, failure),
                            )
                            authRequired = true
                            break
                        }
                    }
                }

                if (authRequired) {
                    mutableSyncState.value = SyncState(
                        syncing = false,
                        error = "Sign in again to sync. Saved tasks remain available on this phone.",
                    )
                    return@withContext SyncResult(authRequired = true)
                }

                val columns = Columns.raw(OCCURRENCE_SELECT)
                val rows = mutableListOf<CachedOccurrence>()
                var offset = 0
                while (true) {
                    val page = client.from("task_occurrences").select(columns = columns) {
                        filter { eq("status", "pending") }
                        order("id", Order.ASCENDING)
                        range(from = offset.toLong(), to = (offset + SYNC_PAGE_SIZE - 1).toLong())
                    }.decodeList<OccurrenceResponse>()
                    rows += page.filter { it.task.archivedAt == null }.map { it.toCache(userId) }
                    if (page.size < SYNC_PAGE_SIZE) break
                    offset += page.size
                }

                dao.replaceOccurrencesSnapshot(userId, rows)
                dao.clearLegacyOccurrences()
                registerFcmToken()
                SystemWidget().updateAll(context)
                val savedCompletions = dao.pendingCompletions(userId)
                val retryablePending = savedCompletions.any { it.state == "PENDING" || it.state == "RETRYABLE" }
                val needsReview = savedCompletions.firstOrNull {
                    it.state == "PERMANENT_FAILURE" || it.state == "CONFLICT"
                }
                mutableSyncState.value = SyncState(
                    syncing = false,
                    error = when {
                        needsReview != null -> "A saved completion needs review."
                        retryablePending -> "Some saved completions are waiting to retry."
                        else -> null
                    },
                    lastSyncedAt = Instant.now().toString(),
                )
                return@withContext SyncResult(retryablePending = retryablePending)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableSyncState.value = mutableSyncState.value.copy(
                    syncing = false,
                    error = "Could not sync tasks.",
                )
                throw error
            }
        }
    }

    private suspend fun requireAuthenticatedUserId(): String =
        authenticatedUserId(loadFromStorage = true)
            ?: throw IllegalStateException("Sign in to access your SYSTEM tasks.")

    private suspend fun authenticatedUserId(loadFromStorage: Boolean): String? {
        client.auth.awaitInitialization()
        if (loadFromStorage && currentUser == null) client.auth.loadFromStorage(autoRefresh = false)
        if (client.auth.sessionStatus.value !is SessionStatus.Authenticated) return null
        return currentUser?.id?.also(::rememberLocalUserId)
    }

    private suspend fun requireLocalUserId(): String =
        localUserId(loadFromStorage = true)
            ?: throw IllegalStateException("Sign in to access your SYSTEM tasks.")

    private suspend fun localUserId(loadFromStorage: Boolean): String? {
        client.auth.awaitInitialization()
        if (loadFromStorage && currentUser == null) client.auth.loadFromStorage(autoRefresh = false)
        return cacheUserId(client.auth.sessionStatus.value)
    }

    private fun cacheUserId(status: SessionStatus): String? {
        val identityState = when (status) {
            is SessionStatus.Authenticated -> RemoteIdentityState.AUTHENTICATED
            is SessionStatus.RefreshFailure -> RemoteIdentityState.REFRESH_FAILURE
            SessionStatus.Initializing, is SessionStatus.NotAuthenticated -> RemoteIdentityState.OTHER
        }
        return LocalCacheIdentity.userId(identityState, currentUser?.id, savedLocalUserId())
            ?.also { if (identityState != RemoteIdentityState.OTHER) rememberLocalUserId(it) }
    }

    private fun savedLocalUserId(): String? = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getString(LOCAL_USER_ID_PREFERENCE, null)

    private fun rememberLocalUserId(userId: String) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putString(LOCAL_USER_ID_PREFERENCE, userId).apply()
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
            client.postgrest.rpc("system_register_device_token", DeviceTokenParams(token))
        } catch (error: CancellationException) {
            throw error
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
                if (error is CancellationException) throw error
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
            if (error is CancellationException) throw error
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
        private const val SYNC_PAGE_SIZE = 1000
        private const val AUTH_SCHEME = "dev.system.app"
        private const val AUTH_HOST = "auth"
        private const val AUTH_REDIRECT_URI = "$AUTH_SCHEME://$AUTH_HOST"
        private const val FCM_TOKENS_PREFERENCE = "registered_fcm_tokens"
        private const val PREFERENCES_NAME = "system"
        private const val LOCAL_USER_ID_PREFERENCE = "local_cache_user_id"
    }
}

class SignOutBlockedException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

data class SyncState(
    val syncing: Boolean = true,
    val error: String? = null,
    val lastSyncedAt: String? = null,
)

data class SyncResult(
    val retryablePending: Boolean = false,
    val authRequired: Boolean = false,
)

@Serializable
internal data class OccurrenceResponse(
    val id: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("scheduled_for") val scheduledFor: String?,
    @SerialName("all_day") val allDay: Boolean,
    val status: String,
    @SerialName("completed_at") val completedAt: String?,
    val task: TaskResponse,
) {
    fun toCache(userId: String) = CachedOccurrence(id, userId, taskId, task.title, task.notes, scheduledFor, allDay, task.kind, status, completedAt)
}

@Serializable
internal data class TaskResponse(
    val title: String,
    val notes: String?,
    val kind: String,
    @SerialName("archived_at") val archivedAt: String?,
)

@Serializable
private data class OccurrenceIdResponse(val id: String)

@Serializable
private data class DeviceTokenParams(@SerialName("p_token") val token: String)

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
