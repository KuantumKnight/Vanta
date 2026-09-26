package dev.system.app

import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.system.app.data.CachedOccurrence
import dev.system.app.data.GoalProgress
import dev.system.app.data.PendingCompletion
import dev.system.app.data.SystemRepository
import dev.system.app.data.SignOutBlockedException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val SystemZone = ZoneId.of("Asia/Kolkata")

private val SystemLightColors = lightColorScheme(
    primary = Color(0xFF006B61),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFD9),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4C635F),
    onSecondary = Color.White,
    background = Color(0xFFF6F8F6),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF9FBF9),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFE5ECE9),
    onSurfaceVariant = Color(0xFF404845),
    outline = Color(0xFF707975),
    error = Color(0xFFBA1A1A),
)

private val SystemDarkColors = darkColorScheme(
    primary = Color(0xFF80D5C9),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFFA0F2E5),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3531),
    background = Color(0xFF101614),
    onBackground = Color(0xFFE0E5E2),
    surface = Color(0xFF151C19),
    onSurface = Color(0xFFE0E5E2),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFC0C9C4),
    outline = Color(0xFF89938E),
    error = Color(0xFFFFB4AB),
)

private enum class DashboardTab(val label: String) {
    Today("Today"),
    Upcoming("Upcoming"),
    Goals("Goals"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createReminderChannel()
        processAuthIntent(intent)
        val app = application as SystemApplication
        setContent { SystemRoot(app, onSignedIn = ::requestNotificationPermissionIfNeeded) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processAuthIntent(intent)
    }

    private fun processAuthIntent(intent: Intent) {
        if (intent.data == null) return
        lifecycleScope.launch {
            runCatching { (application as SystemApplication).awaitRepository().handleAuthDeeplink(intent) }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("system", MODE_PRIVATE)
        if (!prefs.getBoolean("notification_prompted", false) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("notification_prompted", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 401)
        }
    }

    override fun onStart() {
        super.onStart()
        val repo = (application as SystemApplication).repositoryOrNull
        if (repo?.currentUser != null) lifecycleScope.launch { runCatching { repo.syncNow() } }
    }

    private fun createReminderChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel("system_reminders", "SYSTEM reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Deadline reminders for your SYSTEM tasks"
                },
            )
        }
    }
}

@Composable
private fun SystemRoot(app: SystemApplication, onSignedIn: () -> Unit) {
    val startup by app.startup.collectAsState()
    val colors = if (androidx.compose.foundation.isSystemInDarkTheme()) SystemDarkColors else SystemLightColors
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val state = startup) {
                StartupState.Initializing -> LoadingScreen()
                is StartupState.Ready -> SystemScreen(state.repository, onSignedIn)
                is StartupState.Failed -> StartupErrorScreen(onRetry = app::initialize)
            }
        }
    }
}

@Composable
private fun StartupErrorScreen(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("SYSTEM couldn’t start", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Your saved tasks are safe. Try opening SYSTEM again.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp).height(48.dp)) { Text("Try again") }
    }
}

@Composable
private fun SystemScreen(repository: SystemRepository, onSignedIn: () -> Unit) {
    val authenticated by repository.isAuthenticated.collectAsState(initial = null)
    val syncState by repository.syncState.collectAsState()
    val completionQueue by repository.completionQueue.collectAsState(initial = emptyList())
    val pending by repository.pendingOccurrences.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var selectedTab by remember { mutableStateOf(DashboardTab.Today) }
    var search by remember { mutableStateOf("") }
    var goals by remember { mutableStateOf<List<GoalProgress>>(emptyList()) }
    var goalsLoading by remember { mutableStateOf(false) }
    var goalsError by remember { mutableStateOf<String?>(null) }
    var syncBusy by remember { mutableStateOf(false) }

    val view = LocalView.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (dark) SystemDarkColors else SystemLightColors
    SideEffect {
        val window = (view.context as? ComponentActivity)?.window ?: return@SideEffect
        window.statusBarColor = colors.background.toArgb()
        window.navigationBarColor = colors.background.toArgb()
        WindowInsetsControllerCompat(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    suspend fun refreshGoals() {
        goalsLoading = true
        goalsError = null
        runCatching { repository.loadGoals() }
            .onSuccess { goals = it }
            .onFailure { goalsError = "Couldn’t load your goals. Check your connection and try again." }
        goalsLoading = false
    }

    LaunchedEffect(authenticated) {
        if (authenticated == true) {
            onSignedIn()
            runCatching { repository.syncNow() }
            refreshGoals()
        }
    }
    LaunchedEffect(selectedTab, syncState.lastSyncedAt) {
        if (authenticated == true && selectedTab == DashboardTab.Goals) refreshGoals()
    }

    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (authenticated) {
                null -> LoadingScreen()
                false -> SignInScreen(repository)
                true -> Scaffold(
                    topBar = {
                        DashboardTopBar(
                            syncing = syncState.syncing || syncBusy,
                            onSync = {
                                scope.launch {
                                    syncBusy = true
                                    runCatching { repository.syncNow() }
                                        .onFailure { snackbarHost.showSnackbar("Sync failed. Your saved tasks are still on this phone.") }
                                    syncBusy = false
                                }
                            },
                            onSignOut = {
                                scope.launch {
                                    runCatching { repository.signOut() }
                                        .onFailure { error ->
                                            snackbarHost.showSnackbar(
                                                (error as? SignOutBlockedException)?.message
                                                    ?: "Couldn’t sign out. Try again in a moment.",
                                            )
                                        }
                                }
                            },
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbarHost) },
                ) { insets ->
                    DashboardContent(
                        modifier = Modifier.padding(insets),
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                        search = search,
                        onSearchChanged = { search = it },
                        pending = pending,
                        completionQueue = completionQueue,
                        syncError = syncState.error,
                        syncing = syncState.syncing || syncBusy,
                        lastSyncedAt = syncState.lastSyncedAt,
                        goals = goals,
                        goalsLoading = goalsLoading,
                        goalsError = goalsError,
                        onRetrySync = { repository.retryCompletions() },
                        onRetryGoals = { scope.launch { refreshGoals() } },
                        onComplete = { row ->
                            scope.launch {
                                runCatching { repository.complete(row.id) }
                                    .onSuccess {
                                        snackbarHost.showSnackbar("Completed")
                                        runCatching { goals = repository.loadGoals() }
                                    }
                                    .onFailure {
                                        snackbarHost.showSnackbar("Saved on this phone. It will sync when you’re back online.")
                                    }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("Opening your workspace", modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SignInScreen(repository: SystemRepository) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var messageIsError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)) {
            Text("SYSTEM", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(
                "A clear head starts here.",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Your tasks, habits, and goals in one calm place.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                    Text(if (sent) "Check your inbox" else "Continue with email", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (sent) "Open the sign-in link sent to $email on this phone. It will bring you back to SYSTEM." else "We’ll email you a secure sign-in link for your private SYSTEM account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
                    )

                    if (!sent) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; message = "" },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Email address") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            enabled = repository.isConfigured && !busy && email.trim().contains("@"),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            onClick = {
                                busy = true
                                message = ""
                                scope.launch {
                                    runCatching { repository.requestEmailSignIn(email) }
                                        .onSuccess { sent = true; message = "Sign-in link sent. Open it on this phone to continue."; messageIsError = false }
                                        .onFailure { message = "Couldn’t send a sign-in link. Check the address and try again."; messageIsError = true }
                                    busy = false
                                }
                            },
                        ) {
                            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text("Email me a sign-in link")
                        }
                        if (!repository.isConfigured) {
                            Text(
                                "Connect Supabase to enable sign-in. Add the project URL and publishable key to android/local.properties.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    } else {
                        Button(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            onClick = {
                                busy = true
                                message = ""
                                scope.launch {
                                    runCatching { repository.requestEmailSignIn(email) }
                                        .onSuccess { message = "A fresh sign-in link is on its way."; messageIsError = false }
                                        .onFailure { message = "Couldn’t resend the link. Check your connection and try again."; messageIsError = true }
                                    busy = false
                                }
                            },
                        ) {
                            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text("Resend sign-in link")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(enabled = !busy, onClick = { sent = false; message = "" }) { Text("Change email") }
                        }
                    }

                    if (message.isNotBlank()) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp).semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardTopBar(
    syncing: Boolean,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text("SYSTEM", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    LocalDate.now(SystemZone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            TextButton(enabled = !syncing, onClick = onSync) { Text(if (syncing) "Syncing…" else "Sync") }
            Box {
                TextButton(onClick = { menuExpanded = true }) { Text("Account") }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Sign out") },
                        onClick = { menuExpanded = false; confirmSignOut = true },
                    )
                }
            }
        },
    )
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of SYSTEM?") },
            text = { Text("Saved completions will sync first, and reminders for this device will be turned off.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        onSignOut()
                    },
                ) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardContent(
    modifier: Modifier,
    selectedTab: DashboardTab,
    onTabSelected: (DashboardTab) -> Unit,
    search: String,
    onSearchChanged: (String) -> Unit,
    pending: List<CachedOccurrence>,
    completionQueue: List<PendingCompletion>,
    syncError: String?,
    syncing: Boolean,
    lastSyncedAt: String?,
    goals: List<GoalProgress>,
    goalsLoading: Boolean,
    goalsError: String?,
    onRetrySync: () -> Unit,
    onRetryGoals: () -> Unit,
    onComplete: (CachedOccurrence) -> Unit,
) {
    val today = LocalDate.now(SystemZone)
    val normalizedQuery = search.trim()
    val filteredTasks = pending.filter { row ->
        normalizedQuery.isBlank() ||
            row.title.contains(normalizedQuery, ignoreCase = true) ||
            row.notes.orEmpty().contains(normalizedQuery, ignoreCase = true)
    }
    val filteredGoals = goals.filter {
        normalizedQuery.isBlank() ||
            it.title.contains(normalizedQuery, ignoreCase = true) ||
            it.description.orEmpty().contains(normalizedQuery, ignoreCase = true)
    }

    Column(modifier = modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            DashboardTab.values().forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    text = { Text(tab.label) },
                )
            }
        }

        OutlinedTextField(
            value = search,
            onValueChange = onSearchChanged,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            label = { Text(if (selectedTab == DashboardTab.Goals) "Search goals" else "Search tasks") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            trailingIcon = {
                if (search.isNotEmpty()) TextButton(onClick = { onSearchChanged("") }) { Text("Clear") }
            },
        )

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            SyncSummary(syncing, syncError, lastSyncedAt, onRetrySync)
            if (completionQueue.isNotEmpty()) CompletionQueueCard(completionQueue, onRetrySync)
        }

        when (selectedTab) {
            DashboardTab.Today -> {
                val todayRows = filteredTasks.filter { row ->
                    val date = row.taskDate()
                    date != null && date <= today
                }
                TaskList(
                    modifier = Modifier.weight(1f),
                    rows = todayRows,
                    today = today,
                    emptyTitle = if (search.isBlank()) "Nothing due today" else "No matching tasks",
                    emptyBody = if (search.isBlank()) "Add a task in ChatGPT and it will show up here." else "Try a different word.",
                    onComplete = onComplete,
                )
            }
            DashboardTab.Upcoming -> {
                val upcomingRows = filteredTasks.filter { row -> row.taskDate()?.let { it > today } == true }
                val unscheduledRows = filteredTasks.filter { it.taskDate() == null }
                UpcomingList(
                    modifier = Modifier.weight(1f),
                    dated = upcomingRows,
                    unscheduled = unscheduledRows,
                    today = today,
                    searchActive = search.isNotBlank(),
                    onComplete = onComplete,
                )
            }
            DashboardTab.Goals -> {
                GoalsList(
                    modifier = Modifier.weight(1f),
                    goals = filteredGoals,
                    loading = goalsLoading,
                    error = goalsError,
                    searchActive = search.isNotBlank(),
                    onRetry = onRetryGoals,
                )
            }
        }
    }
}

@Composable
private fun SyncSummary(syncing: Boolean, error: String?, lastSyncedAt: String?, onRetry: () -> Unit) {
    when {
        error != null -> Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Couldn’t sync", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("Your saved tasks are still available on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
        syncing -> Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("  Syncing your tasks…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        lastSyncedAt != null -> Text(
            "Updated " + formatClock(lastSyncedAt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        else -> Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun CompletionQueueCard(items: List<PendingCompletion>, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    items.size.toString() + " completion" + (if (items.size == 1) "" else "s") + " waiting to sync",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    if (items.any { it.lastError != null }) "A saved change needs another try."
                    else "Saved on this phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                )
            }
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun TaskList(
    modifier: Modifier,
    rows: List<CachedOccurrence>,
    today: LocalDate,
    emptyTitle: String,
    emptyBody: String,
    onComplete: (CachedOccurrence) -> Unit,
) {
    if (rows.isEmpty()) {
        EmptyState(modifier = modifier, title = emptyTitle, body = emptyBody)
        return
    }
    val overdue = rows.filter { it.taskDate()?.let { date -> date < today } == true }
    val dueToday = rows.filter { it.taskDate() == today }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (overdue.isNotEmpty()) {
            item { SectionHeading("OVERDUE", overdue.size.toString() + " waiting") }
            items(overdue, key = { it.id }) { TaskCard(it, onComplete) }
        }
        if (dueToday.isNotEmpty()) {
            item { SectionHeading(if (overdue.isEmpty()) "TODAY" else "DUE TODAY", dueToday.size.toString() + " waiting") }
            items(dueToday, key = { it.id }) { TaskCard(it, onComplete) }
        }
    }
}

@Composable
private fun UpcomingList(
    modifier: Modifier,
    dated: List<CachedOccurrence>,
    unscheduled: List<CachedOccurrence>,
    today: LocalDate,
    searchActive: Boolean,
    onComplete: (CachedOccurrence) -> Unit,
) {
    if (dated.isEmpty() && unscheduled.isEmpty()) {
        EmptyState(
            modifier = modifier,
            title = if (searchActive) "No matching tasks" else "Nothing coming up",
            body = if (searchActive) "Try a different word." else "Tasks and habits you add in ChatGPT will appear here.",
        )
        return
    }
    val grouped = dated.groupBy { it.taskDate() }.toSortedMap(compareBy<LocalDate?> { it })
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grouped.forEach { (date, tasks) ->
            if (date != null) {
                item(key = "heading-" + date.toString()) {
                    val label = if (date == today.plusDays(1)) "TOMORROW"
                    else date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")).uppercase()
                    SectionHeading(label, tasks.size.toString() + " task" + if (tasks.size == 1) "" else "s")
                }
                items(tasks, key = { it.id }) { TaskCard(it, onComplete) }
            }
        }
        if (unscheduled.isNotEmpty()) {
            item(key = "no-date-heading") { SectionHeading("NO DATE", unscheduled.size.toString() + " task" + if (unscheduled.size == 1) "" else "s") }
            items(unscheduled, key = { it.id }) { TaskCard(it, onComplete) }
        }
    }
}

@Composable
private fun GoalsList(
    modifier: Modifier,
    goals: List<GoalProgress>,
    loading: Boolean,
    error: String?,
    searchActive: Boolean,
    onRetry: () -> Unit,
) {
    if (loading && goals.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (error != null && goals.isEmpty()) {
        EmptyState(modifier, "Goals didn’t load", error, actionLabel = "Retry", onAction = onRetry)
        return
    }
    if (goals.isEmpty()) {
        EmptyState(
            modifier,
            if (searchActive) "No matching goals" else "Give a goal some shape",
            if (searchActive) "Try a different word." else "Ask ChatGPT to create a goal and link tasks or habits to it.",
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Small steps add up.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        }
        items(goals, key = { it.goalId }) { goal ->
            val progress = if (goal.targetCount <= 0) 0f else
                (goal.completedCount.toFloat() / goal.targetCount.toFloat()).coerceIn(0f, 1f)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(goal.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (!goal.description.isNullOrBlank()) {
                                Text(goal.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                        if (goal.completed) Text("Done", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                    Text(
                        goal.completedCount.toString() + " of " + goal.targetCount + " steps" + if (goal.completed) " · complete" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCard(row: CachedOccurrence, onComplete: (CachedOccurrence) -> Unit) {
    val scope = rememberCoroutineScope()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                if (!row.notes.isNullOrBlank()) {
                    Text(row.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 2)
                }
                val localDate = row.taskDate()
                Text(
                    localDate?.let {
                        if (row.scheduledFor != null && !row.allDay) {
                            val time = runCatching { Instant.parse(row.scheduledFor).atZone(SystemZone).format(DateTimeFormatter.ofPattern("h:mm a")) }.getOrNull()
                            it.format(DateTimeFormatter.ofPattern("EEE, d MMM")) + (time?.let { value -> " · " + value } ?: "")
                        } else it.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
                    } ?: "No date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
                if (row.kind == "habit") {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("HABIT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
            androidx.compose.material3.Checkbox(
                checked = false,
                onCheckedChange = { checked -> if (checked) scope.launch { onComplete(row) } },
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Mark " + row.title + " complete" },
            )
        }
    }
}

@Composable
private fun SectionHeading(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(actionLabel) }
                }
            }
        }
    }
}

private fun CachedOccurrence.taskDate(): LocalDate? =
    scheduledFor?.let { runCatching { Instant.parse(it).atZone(SystemZone).toLocalDate() }.getOrNull() }

private fun formatClock(instant: String): String =
    runCatching { Instant.parse(instant).atZone(SystemZone).format(DateTimeFormatter.ofPattern("h:mm a")) }
        .getOrDefault("recently")
