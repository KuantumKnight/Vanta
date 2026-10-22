package dev.system.app.data

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import dev.system.app.BuildConfig
import dev.system.app.MainActivity
import dev.system.app.SystemApplication
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

private data class FixtureAccount(val email: String, val password: String, val userId: String)

private object OfflineE2ESupport {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun app() = context.applicationContext as SystemApplication

    private fun secrets(): JSONObject = JSONObject(
        File(context.filesDir, "offline-e2e-secrets.json").readText()
    )

    fun assumeLocalSecrets() {
        org.junit.Assume.assumeTrue("local E2E fixture credentials are supplied on the test device only",
            File(context.filesDir, "offline-e2e-secrets.json").exists()
        )
    }

    fun account(letter: String): FixtureAccount {
        val row = secrets().getJSONObject(letter)
        return FixtureAccount(
            row.getString("email"),
            row.getString("password"),
            row.getString("user_id"),
        )
    }

    fun stateFile() = File(context.filesDir, "offline-e2e-state.json")

    fun state(): JSONObject {
        val file = stateFile()
        check(file.exists()) { "E2E state file is missing." }
        return JSONObject(file.readText())
    }

    fun saveState(state: JSONObject) {
        stateFile().writeText(state.toString())
    }

    fun appClient(app: SystemApplication = app()): SupabaseClient {
        val field = SystemRepository::class.java.getDeclaredField("client")
        field.isAccessible = true
        return field.get(app.repository) as SupabaseClient
    }

    suspend fun signInA(): SupabaseClient {
        val app = app()
        val client = appClient(app)
        val account = account("a")
        client.auth.awaitInitialization()
        if (client.auth.currentUserOrNull()?.id != account.userId) {
            client.auth.signInWith(Email) {
                email = account.email
                password = account.password
            }
        }
        assertEquals(account.userId, client.auth.currentUserOrNull()?.id)
        app.repository.syncNow()
        withTimeout(20_000) {
            app.repository.accountContext.first { it.userId == account.userId }
        }
        return client
    }

    fun signedInBClient(): Pair<SupabaseClient, FixtureAccount> {
        val account = account("b")
        val client = createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ) {
            install(Auth) {
                flowType = io.github.jan.supabase.auth.FlowType.PKCE
                scheme = "dev.system.app"
                host = "auth"
            }
            install(Postgrest)
        }
        return client to account
    }

    fun weekStart() = LocalDate.now(ZoneId.of("Asia/Calcutta"))
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

@Serializable
private data class OfflineRpcUpdate(
    @SerialName("p_operation_id") val operationId: String,
    @SerialName("p_task_id") val taskId: String,
    @SerialName("p_title") val title: String,
    @SerialName("p_update_title") val updateTitle: Boolean,
    @SerialName("p_notes") val notes: String?,
    @SerialName("p_update_notes") val updateNotes: Boolean,
    @SerialName("p_scheduled_for") val scheduledFor: String?,
    @SerialName("p_update_schedule") val updateSchedule: Boolean,
    @SerialName("p_all_day") val allDay: Boolean,
    @SerialName("p_reminder_at") val reminderAt: String?,
    @SerialName("p_update_reminder") val updateReminder: Boolean,
    @SerialName("p_archived") val archived: Boolean,
    @SerialName("p_update_archived") val updateArchived: Boolean,
    @SerialName("p_duration_minutes") val durationMinutes: Int?,
    @SerialName("p_update_duration") val updateDuration: Boolean,
    @SerialName("p_scheduling") val scheduling: JsonObject,
)

@Serializable
private data class RemoteTaskRead(val id: String, val title: String, val notes: String?)

@Serializable
private data class RemoteOccurrenceRead(val id: String, val priority: String)

@Serializable
private data class RemoteOccurrenceId(val id: String)

private fun rpcUpdate(
    taskId: String,
    title: String = "",
    updateTitle: Boolean = false,
    scheduling: JsonObject = buildJsonObject { },
    operationId: String = UUID.randomUUID().toString(),
) = OfflineRpcUpdate(
    operationId = operationId,
    taskId = taskId,
    title = title,
    updateTitle = updateTitle,
    notes = null,
    updateNotes = false,
    scheduledFor = null,
    updateSchedule = false,
    allDay = false,
    reminderAt = null,
    updateReminder = false,
    archived = false,
    updateArchived = false,
    durationMinutes = null,
    updateDuration = false,
    scheduling = scheduling,
)

class OfflineUpdateFixtureSetupTest {
    @Test
    fun phase1CreateAndSyncTaskAsAuthenticatedUserA() = runBlocking(Dispatchers.IO) {
        OfflineE2ESupport.assumeLocalSecrets()
        val app = OfflineE2ESupport.app()
        val client = OfflineE2ESupport.signInA()
        val repo = app.repository
        val originalTitle = "B51 E2E Original " + UUID.randomUUID().toString().take(8)
        val editedTitle = "B51 E2E Edited " + UUID.randomUUID().toString().take(8)
        val scheduled = Instant.now().plusSeconds(900).truncatedTo(ChronoUnit.MINUTES)

        repo.createTask(
            title = originalTitle,
            notes = "Disposable offline update fixture",
            scheduledFor = scheduled,
            allDay = false,
            durationMinutes = 25,
        )
        repo.syncNow()

        val row = withTimeout(30_000) {
            while (true) {
                val match = repo.plannerWeekSnapshot(OfflineE2ESupport.weekStart())
                    .firstOrNull { it.title == originalTitle }
                if (match != null) return@withTimeout match
                delay(250)
            }
            error("unreachable")
        }
        val remoteTask = client.from("tasks")
            .select(columns = Columns.raw("id,title,notes")) {
                filter { eq("id", row.taskId) }
            }
            .decodeSingle<RemoteTaskRead>()
        val remoteOccurrence = client.from("task_occurrences")
            .select(columns = Columns.raw("id,priority")) {
                filter { eq("id", row.id) }
            }
            .decodeSingle<RemoteOccurrenceRead>()

        assertEquals(originalTitle, row.title)
        assertEquals(originalTitle, remoteTask.title)
        assertEquals(row.taskId, remoteTask.id)
        assertEquals(row.id, remoteOccurrence.id)

        OfflineE2ESupport.saveState(
            JSONObject()
                .put("userId", client.auth.currentUserOrNull()?.id)
                .put("taskId", row.taskId)
                .put("occurrenceId", row.id)
                .put("originalTitle", originalTitle)
                .put("editedTitle", editedTitle)
                .put("initialPriority", remoteOccurrence.priority)
                .put("scheduledFor", scheduled.toString())
                .put("createdAt", Instant.now().toString())
        )
    }
}

class OfflineUpdateOfflineUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun phase2EditOfflineFromTaskDetailAndPersistRetry() {
        OfflineE2ESupport.assumeLocalSecrets()
        val app = OfflineE2ESupport.app()
        val state = OfflineE2ESupport.state()
        val userId = state.getString("userId")
        val occurrenceId = state.getString("occurrenceId")
        val originalTitle = state.getString("originalTitle")
        val editedTitle = state.getString("editedTitle")
        val dao = app.database.systemDao()

        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText(originalTitle).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Open details for " + originalTitle).performClick()
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithContentDescription("Task title").performTextReplacement(editedTitle)
        composeRule.onNodeWithText("Save changes").performScrollTo().performClick()
        composeRule.waitUntil(20_000) {
            runBlocking(Dispatchers.IO) {
                dao.occurrence(userId, occurrenceId)?.title == editedTitle &&
                    dao.pendingMutations(userId).any { it.operationType == "update_task" }
            }
        }

        val local = runBlocking(Dispatchers.IO) { dao.occurrence(userId, occurrenceId) }
        assertEquals(editedTitle, local?.title)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(editedTitle).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(editedTitle).assertIsDisplayed()

        val beforeAttempt = System.currentTimeMillis()
        runBlocking(Dispatchers.IO) { runCatching { app.repository.syncNow() } }
        val pending = runBlocking(Dispatchers.IO) {
            dao.pendingMutations(userId).single { it.operationType == "update_task" }
        }
        assertTrue(pending.retryCount >= 1)
        assertTrue(pending.nextAttemptAt >= pending.lastAttemptAt + 30_000L)
        assertTrue(pending.nextAttemptAt > beforeAttempt)
        OfflineE2ESupport.saveState(
            state.put("updateOperationId", pending.operationId)
                .put("retryCountAfterOfflineAttempt", pending.retryCount)
                .put("nextAttemptAtAfterOfflineAttempt", pending.nextAttemptAt)
        )
    }
}

class OfflineUpdateProcessDeathTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun phase3PendingEditSurvivesForceStopAndRestart() {
        OfflineE2ESupport.assumeLocalSecrets()
        val app = OfflineE2ESupport.app()
        val state = OfflineE2ESupport.state()
        val userId = state.getString("userId")
        val occurrenceId = state.getString("occurrenceId")
        val editedTitle = state.getString("editedTitle")
        val dao = app.database.systemDao()

        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText(editedTitle).fetchSemanticsNodes().isNotEmpty()
        }
        val local = runBlocking(Dispatchers.IO) {
            dao.occurrence(userId, occurrenceId)
        }
        val pending = runBlocking(Dispatchers.IO) {
            dao.pendingMutations(userId).single { it.operationType == "update_task" }
        }
        assertEquals(editedTitle, local?.title)
        assertEquals(state.getString("updateOperationId"), pending.operationId)
        assertTrue(pending.retryCount >= state.getInt("retryCountAfterOfflineAttempt"))
        composeRule.onNodeWithText(editedTitle).assertIsDisplayed()
        OfflineE2ESupport.saveState(state.put("verifiedAfterRestart", true))
    }
}

class OfflineUpdateReplayAndDeltaTest {
    @Test
    fun phase4RemoteDeltaDoesNotReplacePendingEditAndRetryConverges() = runBlocking(Dispatchers.IO) {
        OfflineE2ESupport.assumeLocalSecrets()
        val app = OfflineE2ESupport.app()
        val client = OfflineE2ESupport.signInA()
        val repo = app.repository
        val state = OfflineE2ESupport.state()
        val userId = state.getString("userId")
        val taskId = state.getString("taskId")
        val occurrenceId = state.getString("occurrenceId")
        val editedTitle = state.getString("editedTitle")
        val dao = app.database.systemDao()

        val pendingBefore = dao.pendingMutations(userId).single { it.operationType == "update_task" }
        assertEquals(editedTitle, dao.occurrence(userId, occurrenceId)?.title)
        assertTrue(pendingBefore.nextAttemptAt > System.currentTimeMillis())

        val remotePriority = "HIGH"
        val deltaUpdate = rpcUpdate(
            taskId = taskId,
            scheduling = buildJsonObject { put("priority", remotePriority) },
        )
        client.postgrest.rpc("mcp_update_task_with_scheduling", deltaUpdate)

        repo.syncNow()
        val afterDelta = dao.occurrence(userId, occurrenceId)
        val pendingAfterDelta = dao.pendingMutations(userId).singleOrNull { it.operationType == "update_task" }
        assertEquals(editedTitle, afterDelta?.title)
        assertNotNull(pendingAfterDelta)
        assertEquals(pendingBefore.operationId, pendingAfterDelta?.operationId)
        val deltaCheckpoint = dao.syncCheckpoint(userId)?.revision
        assertTrue(deltaCheckpoint != null && deltaCheckpoint > 0)

        OfflineE2ESupport.saveState(state.put("deltaRaceProtected", true)
            .put("deltaPriority", remotePriority)
            .put("deltaRevision", deltaCheckpoint))

        withTimeout(120_000) {
            while (true) {
                val current = dao.pendingMutations(userId).singleOrNull { it.operationType == "update_task" }
                    ?: break
                val waitMs = (current.nextAttemptAt - System.currentTimeMillis()).coerceAtLeast(100L)
                delay(waitMs + 150L)
                repo.syncNow()
            }
        }

        val local = dao.occurrence(userId, occurrenceId)
        val serverTask = client.from("tasks")
            .select(columns = Columns.raw("id,title,notes")) {
                filter { eq("id", taskId) }
            }
            .decodeSingle<RemoteTaskRead>()
        val serverOccurrence = client.from("task_occurrences")
            .select(columns = Columns.raw("id,priority")) {
                filter { eq("id", occurrenceId) }
            }
            .decodeSingle<RemoteOccurrenceRead>()

        assertEquals(editedTitle, local?.title)
        assertEquals(remotePriority, local?.priority)
        assertEquals(editedTitle, serverTask.title)
        assertEquals(remotePriority, serverOccurrence.priority)
        assertTrue(dao.pendingMutations(userId).none { it.operationType == "update_task" })
        OfflineE2ESupport.saveState(state.put("converged", true)
            .put("finalServerTitle", serverTask.title)
            .put("finalRoomTitle", local?.title)
            .put("finalPriority", serverOccurrence.priority))
    }
}

class OfflineUpdateSecurityAndIdempotencyTest {
    @Test
    fun phase5OwnerAllowlistAndSameOperationReplayUseAuthenticatedClients() = runBlocking(Dispatchers.IO) {
        OfflineE2ESupport.assumeLocalSecrets()
        val app = OfflineE2ESupport.app()
        val clientA = OfflineE2ESupport.signInA()
        val state = OfflineE2ESupport.state()
        val taskId = state.getString("taskId")
        val occurrenceId = state.getString("occurrenceId")
        val userA = OfflineE2ESupport.account("a")
        val (clientB, userB) = OfflineE2ESupport.signedInBClient()
        clientB.auth.signInWith(Email) {
            email = userB.email
            password = userB.password
        }
        assertEquals(userB.userId, clientB.auth.currentUserOrNull()?.id)

        val visibleToB = clientB.from("task_occurrences")
            .select(columns = Columns.raw("id")) {
                filter { eq("id", occurrenceId) }
            }
            .decodeList<RemoteOccurrenceId>()
        assertTrue(visibleToB.isEmpty())

        val unauthorized = runCatching {
            clientB.postgrest.rpc(
                "mcp_update_task_with_scheduling",
                rpcUpdate(taskId, title = "B51 unauthorized edit", updateTitle = true),
            )
        }
        assertTrue(unauthorized.isFailure)

        val forbiddenKeys = listOf("user_id", "owner_id", "sync_revision", "arbitrary_column")
        forbiddenKeys.forEach { key ->
            val scheduling = buildJsonObject { put(key, "forbidden") }
            val rejected = runCatching {
                clientA.postgrest.rpc(
                    "mcp_update_task_with_scheduling",
                    rpcUpdate(taskId, scheduling = scheduling),
                )
            }
            assertTrue(rejected.isFailure)
        }
        val unchanged = clientA.from("tasks")
            .select(columns = Columns.raw("id,title")) {
                filter { eq("id", taskId) }
            }
            .decodeSingle<RemoteTaskRead>()
        assertEquals(state.getString("editedTitle"), unchanged.title)

        val replayTaskTitle = "B51 Idempotent " + UUID.randomUUID().toString().take(8)
        val replayOperation = UUID.randomUUID().toString()
        val replayParams = rpcUpdate(
            taskId = taskId,
            title = replayTaskTitle,
            updateTitle = true,
            operationId = replayOperation,
        )
        val first = clientA.postgrest.rpc(
            "mcp_update_task_with_scheduling",
            replayParams,
        ).decodeAs<JsonObject>()
        val retry = clientA.postgrest.rpc(
            "mcp_update_task_with_scheduling",
            replayParams,
        ).decodeAs<JsonObject>()
        assertEquals(first, retry)
        val afterReplay = clientA.from("tasks")
            .select(columns = Columns.raw("id,title")) {
                filter { eq("id", taskId) }
            }
            .decodeSingle<RemoteTaskRead>()
        assertEquals(replayTaskTitle, afterReplay.title)

        clientB.auth.signOut()
        clientA.auth.signOut()
        val restoredA = app.repository
        val repoClient = OfflineE2ESupport.appClient(app)
        val accountA = OfflineE2ESupport.account("a")
        repoClient.auth.signInWith(Email) {
            email = accountA.email
            password = accountA.password
        }
        restoredA.syncNow()
        OfflineE2ESupport.saveState(
            state.put("crossUserDenied", true)
                .put("allowlistRejected", true)
                .put("sameOperationReplayEqual", first == retry)
                .put("ambiguousResponseActuallyLost", false)
                .put("idempotentTaskTitle", replayTaskTitle)
        )
    }
}

class OfflineUpdateConvergenceUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun phase6ConvergedTaskRemainsVisibleAfterReconnect() {
        OfflineE2ESupport.assumeLocalSecrets()
        val state = OfflineE2ESupport.state()
        val editedTitle = state.getString("editedTitle")
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText(editedTitle).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(editedTitle).assertExists()
    }
}
