package dev.system.app.data

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.google.firebase.FirebaseApp
import dev.system.app.BuildConfig
import dev.system.app.focus.FocusSessionStore
import dev.system.app.focus.FocusSession
import dev.system.app.settings.UserPreferencesRepository
import dev.system.app.planner.PlanProposal
import dev.system.app.planner.SchedulePriority
import dev.system.app.planner.ScheduleType
import io.github.jan.supabase.auth.currentAccessTokenOrNull
import dev.system.app.sync.SyncWorker
import dev.system.app.sync.SyncPassCoordinator
import dev.system.app.widget.SystemWidget
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private const val SYNC_PAGE_SIZE = 250L
private const val MAX_DELTA_PAGES_PER_PASS = 20
private const val MAX_MUTATIONS_PER_PASS = 50
private const val HISTORY_PAGE_SIZE = 500
private const val MAX_HISTORY_ROWS_PER_LOAD = 5_000
private val VANTA_DATA_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

class SystemRepository(
    private val context: Context,
    private val database: SystemDatabase,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    private val appContext = context.applicationContext
    private val dao = database.systemDao()
    private val accountMutationMutex = Mutex()
    private val syncPassCoordinator = SyncPassCoordinator()
    private val focusSessionStore = FocusSessionStore(appContext)
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = appContext.getSharedPreferences(SYSTEM_PREFERENCES, Context.MODE_PRIVATE)
    private val mutableSyncState = MutableStateFlow(SyncState())
    val syncState = mutableSyncState.asStateFlow()
    private val mutableAuthCallbackError = MutableStateFlow<String?>(null)
    val authCallbackError = mutableAuthCallbackError.asStateFlow()
    private val mutableAccountContext = MutableStateFlow(
        LocalAccountContext(preferences.getString(LOCAL_USER_ID_PREFERENCE, null), RemoteAuthAvailability.INITIALIZING),
    )
    val accountContext = mutableAccountContext.asStateFlow()
    val isConfigured: Boolean get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank() &&
        BuildConfig.SUPABASE_URL.startsWith("https://") && !BuildConfig.SUPABASE_URL.contains("example.")
    private val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    ) {
        install(Auth) {
            flowType = FlowType.PKCE
            scheme = "dev.system.app"
            host = "auth"
        }
        install(Postgrest)
    }

    private val mutableRemoteUserId = MutableStateFlow(currentUser?.id)
    private val visibleLocalUserId = combine(accountContext, mutableRemoteUserId) { account, remoteUserId ->
        account.visibleUserId(remoteUserId)
    }.distinctUntilChanged()
    val isAuthenticated = combine(accountContext, mutableRemoteUserId) { account, remoteUserId ->
        account.canShowAccount(remoteUserId) &&
            (account.hasLocalAccount || account.remoteAuth == RemoteAuthAvailability.AVAILABLE)
    }
    val pendingOccurrences: Flow<List<CachedOccurrence>> = visibleLocalUserId.flatMapLatest { userId ->
        userId?.let { dao.observePending(it) } ?: flowOf(emptyList<CachedOccurrence>())
    }
    val completionQueue: Flow<List<PendingCompletion>> = visibleLocalUserId.flatMapLatest { userId ->
        userId?.let { dao.observeCompletionQueue(it) } ?: flowOf(emptyList<PendingCompletion>())
    }

    private fun visibleAccountUserId(): String? =
        mutableAccountContext.value.visibleUserId(currentUser?.id)

    val currentUser get() = client.auth.currentUserOrNull()

    init {
        repositoryScope.launch {
            client.auth.sessionStatus.collect { status ->
                mutableRemoteUserId.value = currentUser?.id
                when (status) {
                    SessionStatus.Initializing -> setRemoteAuth(RemoteAuthAvailability.INITIALIZING)
                    is SessionStatus.Authenticated -> currentUser?.id?.let {
                        val mayClaimLegacy = mutableAccountContext.value.remoteAuth == RemoteAuthAvailability.INITIALIZING
                        rememberAuthenticatedUser(it, claimLegacyCache = mayClaimLegacy)
                    }
                    is SessionStatus.RefreshFailure -> {
                        val sessionUserId = currentUser?.id
                        if (sessionUserId != null && mutableAccountContext.value.userId == null) {
                            val mayClaimLegacy = mutableAccountContext.value.remoteAuth == RemoteAuthAvailability.INITIALIZING
                            rememberAuthenticatedUser(
                                sessionUserId,
                                RemoteAuthAvailability.TEMPORARY_FAILURE,
                                claimLegacyCache = mayClaimLegacy,
                            )
                        } else {
                            mutableAccountContext.value = localContextAfterRefreshFailure(mutableAccountContext.value)
                        }
                        mutableSyncState.value = mutableSyncState.value.copy(
                            syncing = false,
                            error = "Sign-in or network refresh is temporarily unavailable. Your offline tasks remain available.",
                        )
                    }
                    is SessionStatus.NotAuthenticated -> {
                        setRemoteAuth(RemoteAuthAvailability.REAUTH_REQUIRED)
                        if (mutableAccountContext.value.userId != null) {
                            mutableSyncState.value = mutableSyncState.value.copy(
                                syncing = false,
                                error = "Sign in again to sync. Your offline tasks remain available.",
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun requestEmailLink(email: String) {
        check(isConfigured) { "Add SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY to android/local.properties first." }
        client.auth.signInWith(OTP, redirectUrl = "dev.system.app://auth") {
            this.email = email.trim()
            createUser = true
        }
    }

    fun signInRequestFailureMessage(error: Throwable): String {
        val details = error.message.orEmpty().lowercase()
        return when {
            "over_email_send_rate_limit" in details || "rate limit" in details || "too many requests" in details ->
                "Too many sign-in link requests. Wait before trying again."
            "network" in details || "connection" in details || "timeout" in details ->
                "Could not reach sign-in. Check your connection and try again."
            else -> "Could not send the sign-in link. Check the email address and try again."
        }
    }

    fun handleAuthDeepLink(intent: Intent) {
        val callback = intent.data ?: return
        if (callback.scheme != "dev.system.app" || callback.host != "auth") return

        mutableAuthCallbackError.value = null
        client.handleDeeplinks(
            intent = intent,
            onError = { error ->
                mutableAuthCallbackError.value = if (
                    error.message.orEmpty().contains("verifier", ignoreCase = true)
                ) {
                    "That sign-in link is expired or was opened on another device. Request a new link here."
                } else {
                    "This sign-in link could not be verified or may have expired. Request a new one here."
                }
            },
        )
    }

    fun clearAuthCallbackError() {
        mutableAuthCallbackError.value = null
    }

    suspend fun signOut() = syncPassCoordinator.run {
        currentUser?.id?.let { remoteUserId ->
            if (mutableAccountContext.value.userId != remoteUserId) {
                val mayClaimLegacy = mutableAccountContext.value.remoteAuth == RemoteAuthAvailability.INITIALIZING
                rememberAuthenticatedUser(remoteUserId, claimLegacyCache = mayClaimLegacy)
            }
        }
        accountMutationMutex.withLock {
            val localAccount = mutableAccountContext.value
            check(localAccount.canShowAccount(currentUser?.id)) {
                "Wait for the signed-in account to finish switching before signing out."
            }
            val localUserId = localAccount.userId
            if (localUserId != null) {
                check(dao.pendingCompletions(localUserId).isEmpty()) {
                    "Resolve saved completions before signing out so offline work is not lost."
                }
                check(dao.pendingMutationCount(localUserId) == 0) {
                    "Resolve saved task changes before signing out so offline work is not lost."
                }
                drainFocusReconciliations(localUserId)
            }
            if (localUserId != null) unregisterFcmTokens()
            client.auth.signOut()
            if (localUserId != null) {
                dao.clearUserOccurrences(localUserId)
                dao.clearSyncState(localUserId)
            }
            check(preferences.edit().remove(LOCAL_USER_ID_PREFERENCE).commit()) {
                "Could not clear the saved local account after signing out."
            }
            mutableAccountContext.value = LocalAccountContext(null, RemoteAuthAvailability.REAUTH_REQUIRED)
            SystemWidget().updateAll(appContext)
        }
    }

    suspend fun complete(occurrenceId: String) {
        val queued = accountMutationMutex.withLock {
            val userId = visibleAccountUserId() ?: return@withLock false
            val pendingRow = dao.occurrence(userId, occurrenceId) ?: return@withLock false
            if (pendingRow.status != "pending") return@withLock false
            val key = UUID.randomUUID().toString()
            val stored = dao.storeQueuedCompletion(
                PendingCompletion(idempotencyKey = key, occurrenceId = occurrenceId, userId = userId),
                pendingRow.copy(status = "completed", completedAt = Instant.now().toString()),
            )
            if (!stored) return@withLock false
            SystemWidget().updateAll(appContext)
            true
        }
        if (!queued) return
        try {
            syncNow()
        } catch (cancelled: CancellationException) {
            SyncWorker.enqueue(appContext)
            throw cancelled
        } catch (_: Throwable) {
            SyncWorker.enqueue(appContext)
        }
        if (mutableAccountContext.value.userId?.let { dao.pendingCompletions(it).isNotEmpty() } == true) {
            SyncWorker.enqueue(appContext)
        }
    }

    suspend fun queueCompletion(occurrenceId: String) {
        accountMutationMutex.withLock {
            val userId = visibleAccountUserId() ?: return@withLock
            val row = dao.occurrence(userId, occurrenceId) ?: return@withLock
            if (row.status != "pending") return@withLock
            val stored = dao.storeQueuedCompletion(
                PendingCompletion(UUID.randomUUID().toString(), occurrenceId, userId = userId),
                row.copy(status = "completed", completedAt = Instant.now().toString()),
            )
            if (stored) SystemWidget().updateAll(appContext)
        }
    }

    suspend fun createTask(
        title: String,
        notes: String?,
        scheduledFor: Instant?,
        allDay: Boolean,
        durationMinutes: Int?,
        scheduleType: ScheduleType? = null,
        priority: SchedulePriority = SchedulePriority.NORMAL,
        deadlineAt: Instant? = null,
        earliestStart: Instant? = null,
        latestEnd: Instant? = null,
        preferredStart: Instant? = null,
    ) {
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotEmpty() && normalizedTitle.length <= 200) { "Task title must be between 1 and 200 characters." }
        require(notes == null || notes.length <= 4000) { "Task notes must be 4,000 characters or fewer." }
        require(durationMinutes == null || durationMinutes in 5..1440) { "Duration must be between 5 and 1,440 minutes." }
        require(!allDay || scheduledFor != null) { "All-day tasks need a scheduled date." }
        val effectiveScheduleType = scheduleType ?: if (scheduledFor == null) ScheduleType.FLEXIBLE else ScheduleType.SOFT
        require(effectiveScheduleType == ScheduleType.FLEXIBLE && scheduledFor == null ||
            effectiveScheduleType != ScheduleType.FLEXIBLE && scheduledFor != null) {
            "Flexible tasks stay unscheduled; hard and soft commitments need a start time."
        }
        require(!allDay || effectiveScheduleType == ScheduleType.HARD) { "Only hard commitments can be all-day." }
        require(earliestStart == null || latestEnd == null || latestEnd >= earliestStart) {
            "The allowed scheduling window ends before it begins."
        }
        require(earliestStart == null || deadlineAt == null || deadlineAt >= earliestStart) {
            "The deadline must be at or after the earliest start."
        }

        val queued = accountMutationMutex.withLock {
            val userId = visibleAccountUserId() ?: return@withLock false
            val operationId = UUID.randomUUID().toString()
            val localOccurrenceId = "local:$operationId"
            val timestamp = scheduledFor?.toString()
            val mutation = PendingMutation(
                operationId = operationId,
                userId = userId,
                operationType = MUTATION_CREATE_TASK,
                localOccurrenceId = localOccurrenceId,
                title = normalizedTitle,
                notes = notes,
                scheduledFor = timestamp,
                allDay = allDay,
                durationMinutes = durationMinutes,
                scheduleType = effectiveScheduleType.name,
                priority = priority.name,
                deadlineAt = deadlineAt?.toString(),
                earliestStart = earliestStart?.toString(),
                latestEnd = latestEnd?.toString(),
                preferredStart = preferredStart?.toString(),
            )
            val optimistic = CachedOccurrence(
                id = localOccurrenceId,
                taskId = localOccurrenceId,
                title = normalizedTitle,
                notes = notes,
                scheduledFor = timestamp,
                allDay = allDay,
                kind = "task",
                status = "pending",
                completedAt = null,
                userId = userId,
                durationMinutes = durationMinutes,
                scheduleType = effectiveScheduleType.name,
                priority = priority.name,
                deadlineAt = deadlineAt?.toString(),
                earliestStart = earliestStart?.toString(),
                latestEnd = latestEnd?.toString(),
                preferredStart = preferredStart?.toString(),
            )
            dao.storeCreateMutation(mutation, optimistic)
        }
        check(queued) { "Sign in before creating a task offline." }
        SystemWidget().updateAll(appContext)
        SyncWorker.enqueue(appContext)
    }

    suspend fun rescheduleOccurrence(occurrenceId: String, scheduledFor: Instant) {
        val queued = accountMutationMutex.withLock {
            val userId = visibleAccountUserId() ?: return@withLock false
            val occurrence = dao.occurrence(userId, occurrenceId) ?: return@withLock false
            if (occurrence.status != "pending") return@withLock false
            val operationId = UUID.randomUUID().toString()
            val targetTime = scheduledFor.toString()
            val mutation = PendingMutation(
                operationId = operationId,
                userId = userId,
                operationType = MUTATION_RESCHEDULE_OCCURRENCE,
                targetOccurrenceId = occurrenceId,
                scheduledFor = targetTime,
            )
            dao.storeRescheduleMutation(
                mutation,
                occurrence.copy(
                    scheduledFor = targetTime,
                    scheduleType = if (occurrence.scheduleType == ScheduleType.FLEXIBLE.name) ScheduleType.SOFT.name else occurrence.scheduleType,
                ),
            )
        }
        if (!queued) return
        SystemWidget().updateAll(appContext)
        SyncWorker.enqueue(appContext)
    }

    /** A week-only Room flow. The DAO limits this to 700 dated rows and 100 flexible tasks. */
    fun observePlannerWeek(weekStart: LocalDate): Flow<List<CachedOccurrence>> {
        val rangeStart = weekStart.atStartOfDay(VANTA_DATA_ZONE).toInstant().toString()
        val rangeEnd = weekStart.plusDays(7).atStartOfDay(VANTA_DATA_ZONE).toInstant().toString()
        return visibleLocalUserId.flatMapLatest { userId ->
            userId?.let { dao.observePlannerRange(it, rangeStart, rangeEnd) }
                ?: flowOf(emptyList<CachedOccurrence>())
        }
    }

    /** Bounded Room snapshot for the final local validation of an approved plan. */
    suspend fun plannerWeekSnapshot(weekStart: LocalDate): List<CachedOccurrence> {
        val userId = visibleAccountUserId() ?: return emptyList()
        val rangeStart = weekStart.atStartOfDay(VANTA_DATA_ZONE).toInstant().toString()
        val rangeEnd = weekStart.plusDays(7).atStartOfDay(VANTA_DATA_ZONE).toInstant().toString()
        return dao.plannerRangeSnapshot(userId, rangeStart, rangeEnd)
    }

    /** Re-read a small set of occurrences before applying a previewed planner proposal. */
    suspend fun plannerOccurrences(ids: List<String>): List<CachedOccurrence> {
        require(ids.isNotEmpty() && ids.size <= 100 && ids.distinct().size == ids.size) {
            "A plan must contain between one and 100 unique tasks."
        }
        val userId = visibleAccountUserId() ?: return emptyList()
        return dao.occurrences(userId, ids)
    }

    /** Expose only an ephemeral session token to authenticated, optional planner requests. */
    suspend fun planningAccessToken(): String? =
        if (currentUser == null) null else client.auth.currentAccessTokenOrNull()

    /** Stores an explicitly approved multi-item replan atomically in Room, then uses the existing sync queue. */
    suspend fun applyReschedulePlan(
        proposal: PlanProposal,
        planningStart: Instant,
        planningEnd: Instant,
        now: Instant = Instant.now(),
    ) {
        val placements = proposal.placements
        if (placements.isEmpty()) return
        require(placements.size <= 50 && placements.map { it.occurrenceId }.distinct().size == placements.size) {
            "A plan must contain no more than 50 unique placements."
        }
        require(planningEnd > planningStart) { "The planning window is invalid." }
        require(placements.none { left -> placements.any { right ->
            left.occurrenceId != right.occurrenceId && left.start < right.end && right.start < left.end
        } }) { "The proposed placements overlap each other." }

        val userId = visibleAccountUserId() ?: error("Sign in before applying a plan.")
        val rows = dao.occurrences(userId, placements.map { it.occurrenceId })
        require(rows.size == placements.size) { "Some tasks changed. Refresh the plan and try again." }
        val byId = rows.associateBy(CachedOccurrence::id)
        val optimistic = placements.map { placement ->
            val row = byId[placement.occurrenceId] ?: error("Some tasks changed. Refresh the plan and try again.")
            val duration = row.durationMinutes?.takeIf { it in 5..1440 }
                ?: error("Set a valid duration before scheduling ${row.title}.")
            require(row.status == "pending" && row.scheduleType != ScheduleType.HARD.name) {
                "${row.title} is completed or marked as a hard commitment. Refresh the plan and try again."
            }
            require(placement.start >= planningStart && placement.end <= planningEnd && placement.start >= now) {
                "The plan includes a past or out-of-hours time. Review it again."
            }
            require(placement.end == placement.start.plusSeconds(duration * 60L)) {
                "The proposed duration no longer matches ${row.title}."
            }
            row.earliestStart?.let { value ->
                require(placement.start >= Instant.parse(value)) { "${row.title} starts before its allowed window." }
            }
            row.latestEnd?.let { value ->
                require(placement.end <= Instant.parse(value)) { "${row.title} ends after its allowed window." }
            }
            row.deadlineAt?.let { value ->
                require(placement.end <= Instant.parse(value)) { "${row.title} would miss its deadline." }
            }
            row.copy(scheduledFor = placement.start.toString(), scheduleType = ScheduleType.SOFT.name)
        }
        val mutations = placements.map { placement ->
            PendingMutation(
                operationId = UUID.randomUUID().toString(),
                userId = userId,
                operationType = MUTATION_RESCHEDULE_OCCURRENCE,
                targetOccurrenceId = placement.occurrenceId,
                scheduledFor = placement.start.toString(),
            )
        }
        check(dao.storeRescheduleMutations(mutations, optimistic)) {
            "Some tasks already have saved changes. Sync or refresh before applying this plan."
        }
        SystemWidget().updateAll(appContext)
        SyncWorker.enqueue(appContext)
    }

    suspend fun dismissTerminalCompletion(idempotencyKey: String) {
        val userId = visibleAccountUserId() ?: return
        dao.dismissTerminalCompletion(userId, idempotencyKey)
        if (currentUser?.id == userId) runCatching { syncNow() }
        SystemWidget().updateAll(appContext)
    }

    /** Stores the existing Focus Mode session locally for private, bounded analytics. */
    suspend fun recordFocusSession(session: FocusSession) {
        val userId = visibleAccountUserId() ?: return
        if (session.userId != userId) return
        dao.upsertFocusHistory(session.toHistory())
    }

    /** Applies the local reminder preference to every push token owned by the signed-in account. */
    suspend fun syncTaskReminderPreference() {
        val userId = currentUser?.id ?: return
        if (!isConfigured) return
        if (FirebaseApp.getApps(appContext).isEmpty()) return
        val enabled = userPreferencesRepository.preferences.first().taskRemindersEnabled
        try {
            val token = com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
            ensureSameAuthenticatedUser(userId)
            client.postgrest.rpc(
                "system_set_task_reminders_enabled",
                TaskReminderPreference(token, enabled),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The typed preference remains in DataStore. A later sync pass retries this idempotent RPC.
            SyncWorker.enqueueRetry(appContext, 60_000L)
        }
    }

    /** Loads occurrences for one local calendar day from Room, including completed history. */
    suspend fun occurrencesForDate(date: LocalDate): List<CachedOccurrence> {
        val rows = occurrenceHistoryForRange(date, date, kind = null)
        return rows.map(OccurrenceHistory::toCachedOccurrence)
            .distinctBy(CachedOccurrence::id)
            .sortedWith(compareBy<CachedOccurrence>({ it.scheduledFor.orEmpty() }, { it.title.lowercase() }))
    }

    /**
     * A bounded Room-first history read. When online, at most 5,000 rows from the requested date
     * window are backfilled through an owner-scoped RPC; any network failure leaves local history
     * available to the caller.
     */
    suspend fun occurrenceHistoryForRange(
        startDate: LocalDate,
        endDate: LocalDate,
        kind: String? = null,
    ): List<OccurrenceHistory> {
        require(!endDate.isBefore(startDate)) { "The history end date must not be before its start date." }
        require(kind == null || kind == "task" || kind == "habit") { "Unknown history category." }
        val userId = visibleAccountUserId() ?: return emptyList()
        if (currentUser?.id == userId && isConfigured) {
            runCatching { backfillOccurrenceHistory(userId, startDate, endDate, kind) }
        }
        val start = startDate.atStartOfDay(VANTA_DATA_ZONE).toInstant().toString()
        val nextDay = endDate.plusDays(1).atStartOfDay(VANTA_DATA_ZONE).toInstant().toString()
        val cached = dao.occurrencesForDateRange(userId, start, nextDay)
            .map { OccurrenceHistory.from(it, VANTA_DATA_ZONE) }
        val history = dao.occurrenceHistoryRange(userId, startDate.toString(), endDate.toString())
        return (history + cached)
            .asSequence()
            .filter { kind == null || it.kind == kind }
            .distinctBy(OccurrenceHistory::occurrenceId)
            .sortedWith(compareBy<OccurrenceHistory>({ it.scheduledDate.orEmpty() }, { it.scheduledFor.orEmpty() }))
            .toList()
    }

    /** Reads a review note from Room first and uses the signed-in user's RLS-protected row as fallback. */
    suspend fun dailyReviewFor(date: LocalDate): DailyReviewEntry? {
        val userId = visibleAccountUserId() ?: return null
        val local = dao.dailyReview(userId, date.toString())
        if (currentUser?.id != userId || !isConfigured) return local
        return runCatching {
            val remote = client.from("daily_reviews")
                .select(columns = Columns.raw("review_date,note,completed_at")) {
                    filter { eq("review_date", date.toString()) }
                    limit(1)
                }
                .decodeList<DailyReviewRemote>()
                .firstOrNull() ?: return@runCatching local
            val remoteAt = runCatching { Instant.parse(remote.completedAt).toEpochMilli() }
                .getOrDefault(System.currentTimeMillis())
            if (local?.syncPending == true) return@runCatching local
            if (local != null && local.completedAtEpochMillis >= remoteAt) return@runCatching local
            DailyReviewEntry(
                userId = userId,
                reviewDate = remote.reviewDate,
                note = remote.note,
                completedAtEpochMillis = remoteAt,
                syncPending = false,
            ).also { dao.upsertDailyReview(it) }
        }.getOrElse { local }
    }

    /** Saves an optional daily note and review marker immediately to Room, then queues safe sync. */
    suspend fun finishDailyReview(date: LocalDate, note: String?) {
        require(note == null || note.length <= 4_000) { "Review notes must be 4,000 characters or fewer." }
        val userId = visibleAccountUserId() ?: error("Sign in to save a daily review.")
        dao.upsertDailyReview(
            DailyReviewEntry(
                userId = userId,
                reviewDate = date.toString(),
                note = note?.trim()?.takeIf(String::isNotEmpty),
                completedAtEpochMillis = System.currentTimeMillis(),
                syncPending = true,
            ),
        )
        SyncWorker.enqueue(appContext)
    }

    /** Queues an explicit habit skip optimistically so the choice remains safe offline. */
    suspend fun skipHabitOccurrence(occurrenceId: String): Boolean {
        val userId = visibleAccountUserId() ?: return false
        val row = dao.occurrence(userId, occurrenceId) ?: return false
        val operationId = UUID.randomUUID().toString()
        val stored = dao.storeHabitSkip(
            PendingHabitSkip(operationId, userId, occurrenceId),
            row,
        )
        if (stored) SyncWorker.enqueue(appContext)
        return stored
    }

    suspend fun focusHistoryForRange(startDate: LocalDate, endDate: LocalDate): List<FocusSessionHistory> {
        require(!endDate.isBefore(startDate)) { "The history end date must not be before its start date." }
        val userId = visibleAccountUserId() ?: return emptyList()
        val start = startDate.atStartOfDay(VANTA_DATA_ZONE).toInstant().toEpochMilli()
        val end = endDate.plusDays(1).atStartOfDay(VANTA_DATA_ZONE).toInstant().toEpochMilli()
        return dao.focusHistoryRange(userId, start, end)
    }

    suspend fun pendingSnapshot(): List<CachedOccurrence> =
        visibleAccountUserId()?.let { dao.pendingSnapshot(it) }.orEmpty()

    suspend fun widgetSnapshot(now: Instant = Instant.now()): WidgetOccurrenceSnapshot {
        val userId = visibleAccountUserId() ?: return WidgetOccurrenceSnapshot(
            occurrences = emptyList(),
            todayPendingCount = 0,
            todayCompletedCount = 0,
            plannedMinutes = 0L,
        )
        val zone = ZoneId.of("Asia/Kolkata")
        val today = now.atZone(zone).toLocalDate()
        val activeSince = now.minusSeconds(24 * 60 * 60).toString()
        val start = today.atStartOfDay(zone).toInstant().toString()
        val nextStart = today.plusDays(1).atStartOfDay(zone).toInstant().toString()
        return dao.widgetSnapshot(userId, activeSince, now.epochSecond, start, nextStart)
    }

    suspend fun syncNow() = syncPassCoordinator.run {
        withContext(Dispatchers.IO) {
            client.auth.awaitInitialization()
            if (currentUser == null) runCatching { client.auth.loadFromStorage(autoRefresh = true) }
            val userId = currentUser?.id
            mutableRemoteUserId.value = userId
            if (userId == null) {
                val state = mutableAccountContext.value
                if (state.remoteAuth == RemoteAuthAvailability.INITIALIZING) {
                    setRemoteAuth(RemoteAuthAvailability.REAUTH_REQUIRED)
                }
                mutableSyncState.value = SyncState(
                    syncing = false,
                    error = if (state.userId != null) "Sign in to sync saved changes. Your offline tasks are still available." else null,
                )
                SystemWidget().updateAll(appContext)
                return@withContext
            }

            rememberAuthenticatedUser(userId)
            mutableSyncState.value = mutableSyncState.value.copy(syncing = true, error = null)
            try {
                // Apply the local opt-out before task reminder claiming can run after reconnect.
                syncTaskReminderPreference()
                drainFocusReconciliations(userId)
                pruneCompletedCache(userId)
                if (dao.syncCheckpoint(userId)?.bootstrapped != true) bootstrapOccurrences(userId)

                dao.resetAuthRequiredMutations(userId)
                val mutationSummary = replayPendingMutations(userId)
                if (mutationSummary.authRequired) {
                    setRemoteAuth(RemoteAuthAvailability.REAUTH_REQUIRED)
                    mutableSyncState.value = SyncState(
                        syncing = false,
                        error = "Sign in again to sync saved changes. Your offline tasks remain available.",
                    )
                    SystemWidget().updateAll(appContext)
                    return@withContext
                }

                dao.resetAuthRequiredCompletions(userId)
                val replaySummary = CompletionQueueReplayer(dao) { item ->
                    val response = client.postgrest.rpc(
                        "system_complete_task_occurrence",
                        CompletionParams(item.occurrenceId, item.idempotencyKey),
                    ).decodeAs<CompletionReplayResponse>()
                    when (response.outcome) {
                        "success" -> ReplayResult(ReplayOutcome.SUCCESS)
                        "already_applied" -> ReplayResult(ReplayOutcome.ALREADY_APPLIED)
                        else -> ReplayResult(
                            ReplayOutcome.PERMANENT_FAILURE,
                            "INVALID_SERVER_RESPONSE",
                            "The server returned an unknown completion result.",
                        )
                    }
                }.replay(userId)

                if (replaySummary.authRequired) {
                    setRemoteAuth(RemoteAuthAvailability.REAUTH_REQUIRED)
                    mutableSyncState.value = SyncState(
                        syncing = false,
                        error = "Sign in again to sync saved completions. Your offline tasks remain available.",
                    )
                    SystemWidget().updateAll(appContext)
                    return@withContext
                }

                val deltasInitiallyCaughtUp = syncDeltaChanges(userId)
                val skipsReplayed = replayPendingHabitSkips(userId)
                val deltasCaughtUp = if (skipsReplayed) syncDeltaChanges(userId) else deltasInitiallyCaughtUp
                syncPendingDailyReviews(userId)
                SystemWidget().updateAll(appContext)
                registerFcmToken(userId)
                syncTaskReminderPreference()
                val nextRetry = listOfNotNull(replaySummary.nextRetryAt, mutationSummary.nextRetryAt).minOrNull()
                nextRetry?.let { retryAt ->
                    SyncWorker.enqueueRetry(appContext, (retryAt - System.currentTimeMillis()).coerceAtLeast(0))
                }
                if (!deltasCaughtUp) SyncWorker.enqueueRetry(appContext, 30_000L)
                mutableSyncState.value = SyncState(
                    syncing = false,
                    error = when {
                        mutationSummary.permanentFailures > 0 ->
                            "Some saved task changes could not sync yet. They remain available on this device."
                        replaySummary.permanentFailures > 0 || replaySummary.conflicts > 0 ->
                            "Some saved completions need your attention. Review the queue below."
                        replaySummary.nextRetryAt != null || mutationSummary.nextRetryAt != null ->
                            "Some saved changes will retry automatically."
                        !deltasCaughtUp -> "Sync is continuing in the background."
                        else -> null
                    },
                    lastSyncedAt = Instant.now().toString(),
                )
            } catch (error: Throwable) {
                val classification = classifyReplayError(error)
                if (classification.outcome == ReplayOutcome.AUTH_REQUIRED) setRemoteAuth(RemoteAuthAvailability.REAUTH_REQUIRED)
                else if (classification.outcome == ReplayOutcome.RETRYABLE) setRemoteAuth(RemoteAuthAvailability.TEMPORARY_FAILURE)
                mutableSyncState.value = mutableSyncState.value.copy(
                    syncing = false,
                    error = when (classification.outcome) {
                        ReplayOutcome.AUTH_REQUIRED -> "Sign in again to sync. Your offline tasks remain available."
                        ReplayOutcome.RETRYABLE -> "Could not reach the server. Your offline tasks remain available."
                        else -> "Could not sync tasks. Review your connection and try again."
                    },
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

    fun retryCompletions() = SyncWorker.enqueue(appContext)

    private suspend fun rememberAuthenticatedUser(
        userId: String,
        remoteAuth: RemoteAuthAvailability = RemoteAuthAvailability.AVAILABLE,
        claimLegacyCache: Boolean = false,
    ) = accountMutationMutex.withLock {
        val previousUserId = mutableAccountContext.value.userId
        if (previousUserId == null && claimLegacyCache) {
            dao.claimLegacyOccurrences(userId)
            dao.claimLegacyCompletions(userId)
        }
        check(preferences.edit().putString(LOCAL_USER_ID_PREFERENCE, userId).commit()) {
            "Could not save the local account identity."
        }
        mutableAccountContext.value = LocalAccountContext(userId, remoteAuth)
    }

    private fun setRemoteAuth(availability: RemoteAuthAvailability) {
        mutableAccountContext.value = mutableAccountContext.value.copy(remoteAuth = availability)
    }

    private suspend fun bootstrapOccurrences(userId: String) {
        val revision = client.postgrest.rpc("system_current_sync_revision")
            .decodeAs<SyncRevisionResponse>().revision
        val generation = UUID.randomUUID().toString()
        dao.clearStagedOccurrences(userId)
        val zone = ZoneId.of("Asia/Kolkata")
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toString()
        val nextDayStart = today.plusDays(1).atStartOfDay(zone).toInstant().toString()

        suspend fun stagePages(completed: Boolean) {
            var lastId: String? = null
            while (true) {
                val page = client.from("task_occurrences").select(columns = occurrenceColumns()) {
                    filter {
                        if (completed) {
                            eq("status", "completed")
                            gte("completed_at", dayStart)
                            lt("completed_at", nextDayStart)
                        } else {
                            eq("status", "pending")
                        }
                        lastId?.let { gt("id", it) }
                    }
                    order("id", Order.ASCENDING)
                    range(0, SYNC_PAGE_SIZE - 1)
                }.decodeList<OccurrenceResponse>()
                if (page.isEmpty()) break
                stageOccurrencePage(userId, generation, page)
                lastId = page.last().id
                if (page.size.toLong() < SYNC_PAGE_SIZE) break
            }
        }

        stagePages(completed = false)
        stagePages(completed = true)
        ensureSameAuthenticatedUser(userId)
        dao.finishBootstrap(userId, generation, revision)
    }

    private suspend fun stageOccurrencePage(userId: String, generation: String, page: List<OccurrenceResponse>) {
        dao.insertStagedOccurrences(
            page.filter { it.task.archivedAt == null }.map { response ->
                val cached = response.toCache(userId)
                BootstrapOccurrence(
                    userId = userId,
                    generation = generation,
                    id = cached.id,
                    taskId = cached.taskId,
                    title = cached.title,
                    notes = cached.notes,
                    scheduledFor = cached.scheduledFor,
                    allDay = cached.allDay,
                    kind = cached.kind,
                    status = cached.status,
                    completedAt = cached.completedAt,
                    durationMinutes = cached.durationMinutes,
                    scheduleType = cached.scheduleType,
                    priority = cached.priority,
                    deadlineAt = cached.deadlineAt,
                    earliestStart = cached.earliestStart,
                    latestEnd = cached.latestEnd,
                    preferredStart = cached.preferredStart,
                )
            },
        )
    }

    private suspend fun pruneCompletedCache(userId: String) {
        val zone = ZoneId.of("Asia/Kolkata")
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant().toString()
        val nextStart = today.plusDays(1).atStartOfDay(zone).toInstant().toString()
        dao.pruneCompletedOutsideDay(userId, start, nextStart)
    }

    private suspend fun syncDeltaChanges(userId: String, throughRevision: Long? = null): Boolean {
        var pagesRead = 0
        while (pagesRead < MAX_DELTA_PAGES_PER_PASS) {
            val checkpoint = dao.syncCheckpoint(userId)?.revision ?: 0L
            if (throughRevision != null && checkpoint >= throughRevision) return true
            val page = client.postgrest.rpc(
                "system_get_occurrence_changes",
                DeltaRequest(checkpoint, SYNC_PAGE_SIZE.toInt()),
            ).decodeAs<OccurrenceDeltaPage>()
            val changes = page.changes.map { change ->
                val row = change.occurrence?.toCache(userId)
                OccurrenceDelta(
                    revision = change.revision,
                    occurrenceId = change.occurrenceId,
                    isDeleted = change.operation == "delete" || row == null,
                    occurrence = row,
                )
            }
            check(page.currentRevision >= checkpoint) {
                "The server sync revision moved behind the saved local checkpoint."
            }
            check(!page.hasMore || changes.size == SYNC_PAGE_SIZE.toInt()) {
                "The server returned a truncated change page while more revisions remain."
            }
            if (changes.isEmpty()) {
                check(page.currentRevision == checkpoint) {
                    "The server change log is missing revisions required by this checkpoint."
                }
            } else {
                var expectedRevision = checkpoint + 1
                changes.forEach { change ->
                    check(change.revision == expectedRevision) {
                        "The server change log has a gap or duplicate revision."
                    }
                    expectedRevision++
                }
                check(changes.last().revision <= page.currentRevision) {
                    "The server returned a change newer than its current revision."
                }
                if (!page.hasMore) {
                    check(changes.last().revision == page.currentRevision) {
                        "The server change log is missing revisions required by this checkpoint."
                    }
                }
            }
            val nextRevision = if (page.hasMore) {
                changes.lastOrNull()?.revision ?: checkpoint
            } else {
                maxOf(checkpoint, page.currentRevision)
            }
            ensureSameAuthenticatedUser(userId)
            dao.applyDeltaPage(userId, changes, nextRevision)
            pagesRead++

            val applied = dao.syncCheckpoint(userId)?.revision ?: 0L
            if (throughRevision != null && applied >= throughRevision) return true
            if (!page.hasMore) {
                if (throughRevision == null) return true
                if (page.currentRevision < throughRevision && changes.isEmpty()) {
                    error("The server change log is missing revisions required to reconcile a saved mutation.")
                }
                if (applied >= throughRevision) return true
            }
        }
        val applied = dao.syncCheckpoint(userId)?.revision ?: 0L
        return throughRevision != null && applied >= throughRevision
    }

    private suspend fun backfillOccurrenceHistory(
        userId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        kind: String?,
    ) {
        val start = startDate.atStartOfDay(VANTA_DATA_ZONE).toInstant().toString()
        val end = endDate.plusDays(1).atStartOfDay(VANTA_DATA_ZONE).toInstant().toString()
        var offset = 0
        var loaded = 0
        while (loaded < MAX_HISTORY_ROWS_PER_LOAD) {
            ensureSameAuthenticatedUser(userId)
            val page = client.postgrest.rpc(
                "system_list_occurrence_history",
                OccurrenceHistoryParams(
                    start = start,
                    end = end,
                    kind = kind,
                    limit = HISTORY_PAGE_SIZE,
                    offset = offset,
                ),
            ).decodeList<OccurrenceHistoryResponse>()
            if (page.isEmpty()) break
            val rows = page.map { it.toHistory(userId) }
            dao.upsertOccurrenceHistory(rows)
            loaded += rows.size
            offset += rows.size
            if (page.size < HISTORY_PAGE_SIZE) break
        }
    }

    private suspend fun replayPendingHabitSkips(userId: String): Boolean {
        val due = dao.dueHabitSkips(userId, System.currentTimeMillis(), limit = 25)
        var replayed = false
        for (item in due) {
            try {
                ensureSameAuthenticatedUser(userId)
                client.postgrest.rpc(
                    "mcp_skip_habit_occurrence",
                    SkipHabitOccurrenceParams(item.operationId, item.occurrenceId),
                ).decodeAs<SkipHabitResponse>()
                dao.deletePendingHabitSkip(userId, item.operationId)
                replayed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                dao.retryPendingHabitSkip(userId, item.operationId, System.currentTimeMillis() + 60_000L)
                SyncWorker.enqueueRetry(appContext, 60_000L)
                break
            }
        }
        if (dao.pendingHabitSkipCount(userId) > 0 && due.size >= 25) {
            SyncWorker.enqueueRetry(appContext, 30_000L)
        }
        return replayed
    }

    private suspend fun syncPendingDailyReviews(userId: String) {
        for (review in dao.pendingDailyReviews(userId)) {
            try {
                ensureSameAuthenticatedUser(userId)
                client.postgrest.rpc(
                    "system_save_daily_review",
                    SaveDailyReviewParams(review.reviewDate, review.note),
                )
                dao.markDailyReviewSynced(userId, review.reviewDate, review.completedAtEpochMillis)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                SyncWorker.enqueueRetry(appContext, 60_000L)
                break
            }
        }
    }

    private suspend fun replayPendingMutations(userId: String): MutationReplaySummary {
        var permanentFailures = 0
        var operationsApplied = 0
        var attempts = 0
        while (attempts < MAX_MUTATIONS_PER_PASS) {
            val now = System.currentTimeMillis()
            val item = dao.nextDuePendingMutation(userId, now) ?: break
            attempts++
            val attemptTime = System.currentTimeMillis()
            try {
                ensureSameAuthenticatedUser(userId)
                when (item.operationType) {
                    MUTATION_CREATE_TASK -> replayCreateMutation(item)
                    MUTATION_RESCHEDULE_OCCURRENCE -> replayRescheduleMutation(item)
                    else -> error("Unknown local mutation type.")
                }
                operationsApplied++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val result = classifyReplayError(error)
                val retryCount = item.retryCount + 1
                val nextAttempt = if (result.outcome == ReplayOutcome.AUTH_REQUIRED) {
                    Long.MAX_VALUE
                } else {
                    attemptTime + mutationRetryDelayMs(retryCount)
                }
                dao.markMutationRetry(
                    userId,
                    item.operationId,
                    retryCount,
                    attemptTime,
                    nextAttempt,
                    result.errorCode ?: "TEMPORARY_FAILURE",
                    result.message ?: "Could not sync this saved change yet.",
                )
                if (result.outcome == ReplayOutcome.AUTH_REQUIRED) {
                    return MutationReplaySummary(
                        dao.nextMutationRetryAt(userId),
                        authRequired = true,
                        permanentFailures = permanentFailures,
                    )
                }
                if (result.outcome == ReplayOutcome.PERMANENT_FAILURE || result.outcome == ReplayOutcome.CONFLICT) {
                    permanentFailures++
                }
            }
        }
        val now = System.currentTimeMillis()
        val nextRetryAt = dao.nextMutationRetryAt(userId)
        val stillDue = dao.nextDuePendingMutation(userId, now) != null
        val scheduledRetryAt = if (attempts == MAX_MUTATIONS_PER_PASS && stillDue) {
            minOf(nextRetryAt ?: (now + 30_000L), now + 30_000L)
        } else nextRetryAt
        return MutationReplaySummary(scheduledRetryAt, permanentFailures = permanentFailures)
    }

    private suspend fun replayCreateMutation(item: PendingMutation) {
        val localId = requireNotNull(item.localOccurrenceId)
        val inferredType = if (item.scheduledFor == null) "FLEXIBLE" else "SOFT"
        val scheduleType = item.scheduleType ?: inferredType
        val priority = item.priority ?: "NORMAL"
        val hasExtendedSemantics = scheduleType != inferredType || priority != "NORMAL" ||
            item.deadlineAt != null || item.earliestStart != null || item.latestEnd != null || item.preferredStart != null
        val response = if (hasExtendedSemantics) {
            client.postgrest.rpc(
                "mcp_create_task_with_scheduling",
                CreateTaskParams(
                operationId = item.operationId,
                title = requireNotNull(item.title),
                notes = item.notes,
                scheduledFor = item.scheduledFor,
                allDay = requireNotNull(item.allDay),
                reminderAt = null,
                durationMinutes = item.durationMinutes,
                scheduleType = scheduleType,
                priority = priority,
                deadlineAt = item.deadlineAt,
                earliestStart = item.earliestStart,
                latestEnd = item.latestEnd,
                preferredStart = item.preferredStart,
                ),
            ).decodeAs<CreateTaskReplayResponse>()
        } else {
            client.postgrest.rpc(
                "mcp_create_task",
                LegacyCreateTaskParams(
                operationId = item.operationId,
                title = requireNotNull(item.title),
                notes = item.notes,
                scheduledFor = item.scheduledFor,
                allDay = requireNotNull(item.allDay),
                reminderAt = null,
                durationMinutes = item.durationMinutes,
                ),
            ).decodeAs<CreateTaskReplayResponse>()
        }
        val occurrenceId = response.occurrence.id
        dao.attachCreatedServerOccurrence(item.userId, item.operationId, occurrenceId)
        val revision = client.postgrest.rpc("system_current_sync_revision")
            .decodeAs<SyncRevisionResponse>().revision
        if (!syncDeltaChanges(item.userId, throughRevision = revision)) {
            error("Occurrence changes are still catching up before the new task can be reconciled.")
        }
        val canonical = CachedOccurrence(
            id = occurrenceId,
            taskId = response.task.id,
            title = response.task.title,
            notes = item.notes,
            scheduledFor = response.occurrence.scheduledFor,
            allDay = response.occurrence.allDay,
            kind = response.task.kind,
            status = response.occurrence.status,
            completedAt = response.occurrence.completedAt,
            userId = item.userId,
            durationMinutes = response.occurrence.durationMinutes ?: item.durationMinutes,
            scheduleType = response.occurrence.scheduleType,
            priority = response.occurrence.priority,
            deadlineAt = response.occurrence.deadlineAt,
            earliestStart = response.occurrence.earliestStart,
            latestEnd = response.occurrence.latestEnd,
            preferredStart = response.occurrence.preferredStart,
        )
        dao.reconcileCreatedOccurrence(item.userId, item.operationId, localId, canonical)
        drainFocusReconciliations(item.userId)
    }

    private suspend fun drainFocusReconciliations(userId: String) {
        for (item in dao.pendingFocusReconciliations(userId)) {
            focusSessionStore.reconcileOccurrenceId(
                userId = item.userId,
                oldOccurrenceId = item.oldOccurrenceId,
                newOccurrenceId = item.newOccurrenceId,
            )
            // FocusSessionStore commits the preference write before the durable Room outbox row is removed.
            dao.removeFocusReconciliation(userId, item.operationId)
        }
    }

    private suspend fun replayRescheduleMutation(item: PendingMutation) {
        val response = client.postgrest.rpc(
            "system_reschedule_occurrence",
            RescheduleParams(
                occurrenceId = requireNotNull(item.targetOccurrenceId),
                scheduledFor = requireNotNull(item.scheduledFor),
                operationId = item.operationId,
            ),
        ).decodeAs<RescheduleReplayResponse>()
        if (!syncDeltaChanges(item.userId, throughRevision = response.revision)) {
            error("Occurrence changes are still catching up before the schedule change can be reconciled.")
        }
        dao.reconcileRescheduledOccurrence(item.userId, item.operationId, response.occurrence.toCache(item.userId))
    }

    private fun occurrenceColumns() = Columns.raw("""
        id,task_id,scheduled_for,all_day,duration_minutes,schedule_type,priority,
        deadline_at,earliest_start,latest_end,preferred_start,status,completed_at,
        task:tasks!inner(title,notes,kind,archived_at)
    """.trimIndent())

    private suspend fun ensureSameAuthenticatedUser(userId: String) {
        check(currentUser?.id == userId) { "The signed-in account changed during synchronization." }
    }

    private suspend fun registerFcmToken(userId: String) {
        if (FirebaseApp.getApps(appContext).isEmpty()) return

        val token = com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
        val previouslyRegistered = preferences.getStringSet(FCM_TOKENS_PREFERENCE, emptySet()).orEmpty().toSet()
        if (
            preferences.getString(FCM_REGISTERED_USER_PREFERENCE, null) == userId &&
            previouslyRegistered == setOf(token)
        ) return
        client.postgrest.rpc("system_register_device_token", DeviceTokenRegistration(token))

        val knownTokens = previouslyRegistered + token
        check(preferences.edit().putStringSet(FCM_TOKENS_PREFERENCE, knownTokens).commit()) {
            "Could not save the registered device token for sign-out cleanup."
        }
        for (oldToken in previouslyRegistered - token) {
            client.from("device_tokens").update(DeviceTokenStatus(enabled = false)) {
                filter { eq("token", oldToken) }
            }
        }
        check(preferences.edit().putStringSet(FCM_TOKENS_PREFERENCE, setOf(token)).commit()) {
            "Could not save the current device token for sign-out cleanup."
        }
        check(preferences.edit().putString(FCM_REGISTERED_USER_PREFERENCE, userId).commit()) {
            "Could not save the device account for token registration."
        }
    }

    private suspend fun unregisterFcmTokens() {
        val tokens = preferences.getStringSet(FCM_TOKENS_PREFERENCE, emptySet()).orEmpty().toMutableSet()
        if (FirebaseApp.getApps(appContext).isNotEmpty()) {
            val currentToken = try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
            } catch (error: Throwable) {
                throw SignOutBlockedException(
                    "Connect to the internet before signing out so Vanta can disable this device's notifications.",
                    error,
                )
            }
            tokens += currentToken
        }

        try {
            tokens.forEach { token ->
                client.from("device_tokens").update(DeviceTokenStatus(enabled = false)) {
                    filter { eq("token", token) }
                }
            }
        } catch (error: Throwable) {
            throw SignOutBlockedException(
                "Connect to the internet before signing out so Vanta can disable this device's notifications.",
                error,
            )
        }
        check(preferences.edit().remove(FCM_TOKENS_PREFERENCE).commit()) {
            "Could not clear the saved device token after signing out."
        }
        preferences.edit().remove(FCM_REGISTERED_USER_PREFERENCE).commit()
    }

    companion object {
        private const val SYSTEM_PREFERENCES = "system"
        private const val LOCAL_USER_ID_PREFERENCE = "local_account_user_id"
        private const val FCM_TOKENS_PREFERENCE = "registered_fcm_tokens"
        private const val FCM_REGISTERED_USER_PREFERENCE = "registered_fcm_user_id"
        private const val MUTATION_CREATE_TASK = "create_task"
        private const val MUTATION_RESCHEDULE_OCCURRENCE = "reschedule_occurrence"
    }
}

data class SyncState(
    val syncing: Boolean = true,
    val error: String? = null,
    val lastSyncedAt: String? = null,
)

class SignOutBlockedException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

private data class MutationReplaySummary(
    val nextRetryAt: Long? = null,
    val authRequired: Boolean = false,
    val permanentFailures: Int = 0,
)

@Serializable
private data class OccurrenceHistoryParams(
    @SerialName("p_start") val start: String,
    @SerialName("p_end") val end: String,
    @SerialName("p_kind") val kind: String?,
    @SerialName("p_limit") val limit: Int,
    @SerialName("p_offset") val offset: Int,
)

@Serializable
private data class OccurrenceHistoryResponse(
    val id: String,
    @SerialName("task_id") val taskId: String,
    val title: String,
    val notes: String? = null,
    @SerialName("scheduled_for") val scheduledFor: String?,
    @SerialName("all_day") val allDay: Boolean,
    val kind: String,
    val status: String,
    @SerialName("completed_at") val completedAt: String?,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
) {
    fun toHistory(userId: String): OccurrenceHistory {
        val cache = CachedOccurrence(
            id = id,
            taskId = taskId,
            title = title,
            notes = notes,
            scheduledFor = scheduledFor,
            allDay = allDay,
            kind = kind,
            status = status,
            completedAt = completedAt,
            userId = userId,
            durationMinutes = durationMinutes,
        )
        return OccurrenceHistory.from(cache, VANTA_DATA_ZONE)
    }
}

@Serializable
private data class DailyReviewRemote(
    @SerialName("review_date") val reviewDate: String,
    val note: String?,
    @SerialName("completed_at") val completedAt: String,
)

@Serializable
private data class SaveDailyReviewParams(
    @SerialName("p_review_date") val reviewDate: String,
    @SerialName("p_note") val note: String?,
)

@Serializable
private data class SkipHabitOccurrenceParams(
    @SerialName("p_operation_id") val operationId: String,
    @SerialName("p_occurrence_id") val occurrenceId: String,
)

@Serializable
private data class SkipHabitResponse(val skipped: Boolean)

private fun mutationRetryDelayMs(retryCount: Int): Long {
    val exponent = (retryCount - 1).coerceIn(0, 10)
    return (30_000L * (1L shl exponent)).coerceAtMost(6 * 60 * 60 * 1000L)
}

@Serializable
private data class OccurrenceResponse(
    val id: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("scheduled_for") val scheduledFor: String?,
    @SerialName("all_day") val allDay: Boolean,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    @SerialName("schedule_type") val scheduleType: String = if (scheduledFor == null) "FLEXIBLE" else "SOFT",
    val priority: String = "NORMAL",
    @SerialName("deadline_at") val deadlineAt: String? = null,
    @SerialName("earliest_start") val earliestStart: String? = null,
    @SerialName("latest_end") val latestEnd: String? = null,
    @SerialName("preferred_start") val preferredStart: String? = null,
    val status: String,
    @SerialName("completed_at") val completedAt: String?,
    val task: TaskResponse,
) {
    fun toCache(userId: String) = CachedOccurrence(
        id, taskId, task.title, task.notes, scheduledFor, allDay, task.kind, status, completedAt, userId, durationMinutes,
        scheduleType, priority, deadlineAt, earliestStart, latestEnd, preferredStart,
    )
}

@Serializable
private data class TaskResponse(
    val title: String,
    val notes: String?,
    val kind: String,
    @SerialName("archived_at") val archivedAt: String?,
)

@Serializable
private data class DeviceTokenRegistration(@SerialName("p_token") val token: String)

@Serializable
private data class DeviceTokenStatus(val enabled: Boolean)

@Serializable
private data class TaskReminderPreference(
    @SerialName("p_token") val token: String,
    @SerialName("p_enabled") val enabled: Boolean,
)

@Serializable
private data class CompletionParams(
    @SerialName("p_occurrence_id") val occurrenceId: String,
    @SerialName("p_idempotency_key") val idempotencyKey: String,
)

@Serializable
private data class CompletionReplayResponse(val outcome: String)

@Serializable
private data class SyncRevisionResponse(@SerialName("revision") val revision: Long)

@Serializable
private data class DeltaRequest(
    @SerialName("p_after_revision") val afterRevision: Long,
    @SerialName("p_limit") val limit: Int,
)

@Serializable
private data class OccurrenceDeltaPage(
    @SerialName("current_revision") val currentRevision: Long,
    @SerialName("has_more") val hasMore: Boolean,
    val changes: List<OccurrenceDeltaResponse>,
)

@Serializable
private data class OccurrenceDeltaResponse(
    val revision: Long,
    val operation: String,
    @SerialName("occurrence_id") val occurrenceId: String,
    val occurrence: DeltaOccurrenceResponse? = null,
)

@Serializable
private data class DeltaOccurrenceResponse(
    val id: String,
    @SerialName("task_id") val taskId: String,
    val title: String,
    val notes: String?,
    val kind: String,
    @SerialName("scheduled_for") val scheduledFor: String?,
    @SerialName("all_day") val allDay: Boolean,
    @SerialName("duration_minutes") val durationMinutes: Int?,
    @SerialName("schedule_type") val scheduleType: String = if (scheduledFor == null) "FLEXIBLE" else "SOFT",
    val priority: String = "NORMAL",
    @SerialName("deadline_at") val deadlineAt: String? = null,
    @SerialName("earliest_start") val earliestStart: String? = null,
    @SerialName("latest_end") val latestEnd: String? = null,
    @SerialName("preferred_start") val preferredStart: String? = null,
    val status: String,
    @SerialName("completed_at") val completedAt: String?,
) {
    fun toCache(userId: String) = CachedOccurrence(
        id, taskId, title, notes, scheduledFor, allDay, kind, status, completedAt, userId, durationMinutes,
        scheduleType, priority, deadlineAt, earliestStart, latestEnd, preferredStart,
    )
}

@Serializable
private data class CreateTaskParams(
    @SerialName("p_operation_id") val operationId: String,
    @SerialName("p_title") val title: String,
    @SerialName("p_notes") val notes: String?,
    @SerialName("p_scheduled_for") val scheduledFor: String?,
    @SerialName("p_all_day") val allDay: Boolean,
    @SerialName("p_reminder_at") val reminderAt: String?,
    @SerialName("p_duration_minutes") val durationMinutes: Int?,
    @SerialName("p_schedule_type") val scheduleType: String,
    @SerialName("p_priority") val priority: String,
    @SerialName("p_deadline_at") val deadlineAt: String?,
    @SerialName("p_earliest_start") val earliestStart: String?,
    @SerialName("p_latest_end") val latestEnd: String?,
    @SerialName("p_preferred_start") val preferredStart: String?,
)

@Serializable
private data class LegacyCreateTaskParams(
    @SerialName("p_operation_id") val operationId: String,
    @SerialName("p_title") val title: String,
    @SerialName("p_notes") val notes: String?,
    @SerialName("p_scheduled_for") val scheduledFor: String?,
    @SerialName("p_all_day") val allDay: Boolean,
    @SerialName("p_reminder_at") val reminderAt: String?,
    @SerialName("p_duration_minutes") val durationMinutes: Int?,
)

@Serializable
private data class CreateTaskReplayResponse(
    val task: CreatedTaskResponse,
    val occurrence: CreatedOccurrenceResponse,
)

@Serializable
private data class CreatedTaskResponse(
    val id: String,
    val title: String,
    val kind: String,
)

@Serializable
private data class CreatedOccurrenceResponse(
    val id: String,
    @SerialName("scheduled_for") val scheduledFor: String?,
    @SerialName("all_day") val allDay: Boolean,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    @SerialName("schedule_type") val scheduleType: String = if (scheduledFor == null) "FLEXIBLE" else "SOFT",
    val priority: String = "NORMAL",
    @SerialName("deadline_at") val deadlineAt: String? = null,
    @SerialName("earliest_start") val earliestStart: String? = null,
    @SerialName("latest_end") val latestEnd: String? = null,
    @SerialName("preferred_start") val preferredStart: String? = null,
    val status: String,
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
private data class RescheduleParams(
    @SerialName("p_occurrence_id") val occurrenceId: String,
    @SerialName("p_scheduled_for") val scheduledFor: String,
    @SerialName("p_operation_id") val operationId: String,
)

@Serializable
private data class RescheduleReplayResponse(
    val occurrence: DeltaOccurrenceResponse,
    val revision: Long,
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
