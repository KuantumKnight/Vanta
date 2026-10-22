package dev.system.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.system.app.MainActivity
import dev.system.app.R
import dev.system.app.SystemApplication
import dev.system.app.settings.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

internal enum class DailyNotificationKind(
    val workName: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val body: String,
    val action: String,
) {
    MORNING(
        "vanta-morning-briefing",
        "vanta_morning_briefing",
        "Morning briefing",
        "Your day in Vanta",
        "Open your plan and see what matters next.",
        MainActivity.ACTION_OPEN_TODAY,
    ),
    EVENING(
        "vanta-evening-review",
        "vanta_evening_review",
        "Evening review",
        "Close out today",
        "Notice what moved forward and choose what to carry.",
        MainActivity.ACTION_OPEN_EVENING_REVIEW,
    ),
}

/** Uses a unique one-time WorkManager request so wall-clock settings and DST can be recalculated. */
internal object VantaDailyNotificationScheduler {
    fun isEnabled(kind: DailyNotificationKind, preferences: UserPreferences): Boolean = kind.enabled(preferences)

    fun schedule(context: Context, preferences: UserPreferences, zone: ZoneId = ZoneId.systemDefault()) {
        scheduleKind(
            context,
            DailyNotificationKind.MORNING,
            preferences.morningBriefingEnabled,
            preferences.morningBriefingMinute,
            zone,
            ExistingWorkPolicy.REPLACE,
        )
        scheduleKind(
            context,
            DailyNotificationKind.EVENING,
            preferences.eveningReviewEnabled,
            preferences.eveningReviewMinute,
            zone,
            ExistingWorkPolicy.REPLACE,
        )
    }

    internal fun scheduleNext(context: Context, kind: DailyNotificationKind, preferences: UserPreferences) {
        val enabled = kind.enabled(preferences)
        val minute = kind.minute(preferences)
        scheduleKind(
            context,
            kind,
            enabled,
            minute,
            ZoneId.systemDefault(),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            cancelExisting = false,
        )
    }

    private fun scheduleKind(
        context: Context,
        kind: DailyNotificationKind,
        enabled: Boolean,
        minuteOfDay: Int,
        zone: ZoneId,
        policy: ExistingWorkPolicy,
        cancelExisting: Boolean = true,
    ) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (cancelExisting) workManager.cancelUniqueWork(kind.workName)
        if (!enabled) return

        val now = ZonedDateTime.now(zone)
        val next = nextDailyNotificationTrigger(now, minuteOfDay)
        val delayMs = Duration.between(now.toInstant(), next.toInstant()).toMillis().coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<VantaDailyNotificationWorker>()
            .setInputData(
                Data.Builder()
                    .putString(VantaDailyNotificationWorker.KEY_KIND, kind.name)
                    .putString(VantaDailyNotificationWorker.KEY_EXPECTED_DATE, next.toLocalDate().toString())
                    .build(),
            )
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(kind.workName, policy, request)
    }

    internal fun DailyNotificationKind.enabled(preferences: UserPreferences): Boolean = when (this) {
        DailyNotificationKind.MORNING -> preferences.morningBriefingEnabled
        DailyNotificationKind.EVENING -> preferences.eveningReviewEnabled
    }

    internal fun DailyNotificationKind.minute(preferences: UserPreferences): Int = when (this) {
        DailyNotificationKind.MORNING -> preferences.morningBriefingMinute
        DailyNotificationKind.EVENING -> preferences.eveningReviewMinute
    }
}

/** Resolves a configured wall-clock time through the supplied zone's DST rules. */
internal fun nextDailyNotificationTrigger(now: ZonedDateTime, minuteOfDay: Int): ZonedDateTime {
    require(minuteOfDay in 0..1439) { "Notification time must be a minute of the day." }
    val desiredTime = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    var next = now.toLocalDate().atTime(desiredTime).atZone(now.zone)
    if (!next.isAfter(now)) next = now.toLocalDate().plusDays(1).atTime(desiredTime).atZone(now.zone)
    return next
}

internal class VantaDailyNotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val app = applicationContext as SystemApplication
        val kind = inputData.getString(KEY_KIND)?.let { runCatching { DailyNotificationKind.valueOf(it) }.getOrNull() }
            ?: return@withContext Result.failure()
        val expectedDate = inputData.getString(KEY_EXPECTED_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return@withContext Result.failure()
        val preferences = app.userPreferencesRepository.preferences.first()

        if (VantaDailyNotificationScheduler.isEnabled(kind, preferences) && expectedDate == LocalDate.now(ZoneId.systemDefault())) {
            deliverIfAllowed(kind, expectedDate)
        }
        VantaDailyNotificationScheduler.scheduleNext(applicationContext, kind, preferences)
        Result.success()
    }

    private fun deliverIfAllowed(kind: DailyNotificationKind, date: LocalDate) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            action = kind.action
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            kind.ordinal * 1_000 + date.dayOfYear,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val id = kind.ordinal * 100_000 + date.dayOfYear
        manager.notify(
            id,
            NotificationCompat.Builder(context, kind.channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(kind.title)
                .setContentText(kind.body)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
    }

    companion object {
        const val KEY_KIND = "notification_kind"
        const val KEY_EXPECTED_DATE = "expected_local_date"
    }
}

/** System time-zone/date changes invalidate the stored wall-clock delay. */
internal class VantaTimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED)) return
        val pendingResult = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as SystemApplication
                VantaDailyNotificationScheduler.schedule(context, app.userPreferencesRepository.preferences.first())
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal fun createVantaDailyNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    DailyNotificationKind.values().forEach { kind ->
        manager.createNotificationChannel(
            NotificationChannel(kind.channelId, kind.channelName, NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = kind.body },
        )
    }
}
