package dev.system.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.mutableStateOf
import dev.system.app.ui.quickcapture.QuickCaptureLaunchRequest
import dev.system.app.ui.VantaRoot
import dev.system.app.ui.navigation.DashboardTab
import dev.system.app.notifications.VantaNotificationNavigation
import dev.system.app.widget.WidgetIntentContract
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val quickCaptureLaunch = mutableStateOf<QuickCaptureLaunchRequest?>(null)
    private val navigationLaunch = mutableStateOf<DashboardTab?>(null)
    private var nextQuickCaptureToken = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createReminderChannel()
        val app = application as SystemApplication
        app.repository.handleAuthDeepLink(intent)
        handleQuickCaptureIntent(intent)
        handleDailyNotificationIntent(intent)
        setContent {
            VantaRoot(
                repository = app.repository,
                userPreferencesRepository = app.userPreferencesRepository,
                quickCaptureRequest = quickCaptureLaunch.value,
                onQuickCaptureRequestConsumed = ::consumeQuickCaptureRequest,
                navigationRequest = navigationLaunch.value,
                onNavigationRequestConsumed = ::consumeNavigationRequest,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (application as SystemApplication).repository.handleAuthDeepLink(intent)
        handleQuickCaptureIntent(intent)
        handleDailyNotificationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val repo = (application as SystemApplication).repository
        if (repo.currentUser != null) lifecycleScope.launch { runCatching { repo.syncNow() } }
    }

    private fun createReminderChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    "system_reminders",
                    getString(R.string.reminder_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = getString(R.string.reminder_channel_description)
                },
            )
        }
    }

    private fun handleQuickCaptureIntent(intent: Intent?) {
        val launchIntent = intent?.takeIf { it.action == WidgetIntentContract.ACTION_QUICK_ADD } ?: return
        val date = launchIntent.getStringExtra(WidgetIntentContract.EXTRA_QUICK_ADD_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now(ZoneId.of("Asia/Kolkata"))
        val source = launchIntent.getStringExtra(WidgetIntentContract.EXTRA_QUICK_ADD_SOURCE)
        quickCaptureLaunch.value = QuickCaptureLaunchRequest(
            token = ++nextQuickCaptureToken,
            date = date,
            source = source,
        )
    }

    private fun handleDailyNotificationIntent(intent: Intent?) {
        navigationLaunch.value = VantaNotificationNavigation.destination(intent?.action)
    }

    private fun consumeNavigationRequest() {
        navigationLaunch.value = null
        intent?.takeIf { it.action == ACTION_OPEN_TODAY || it.action == ACTION_OPEN_EVENING_REVIEW }?.let { launchIntent ->
            launchIntent.action = Intent.ACTION_MAIN
            setIntent(launchIntent)
        }
    }

    private fun consumeQuickCaptureRequest(token: Long) {
        if (quickCaptureLaunch.value?.token != token) return
        quickCaptureLaunch.value = null
        intent?.takeIf { it.action == WidgetIntentContract.ACTION_QUICK_ADD }?.let { launchIntent ->
            launchIntent.action = Intent.ACTION_MAIN
            launchIntent.removeExtra(WidgetIntentContract.EXTRA_QUICK_ADD_DATE)
            launchIntent.removeExtra(WidgetIntentContract.EXTRA_QUICK_ADD_SOURCE)
            setIntent(launchIntent)
        }
    }

    companion object {
        const val ACTION_OPEN_TODAY = VantaNotificationNavigation.ACTION_OPEN_TODAY
        const val ACTION_OPEN_EVENING_REVIEW = VantaNotificationNavigation.ACTION_OPEN_EVENING_REVIEW
    }
}
