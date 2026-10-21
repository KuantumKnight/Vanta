package dev.system.app.sync

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.system.app.MainActivity
import dev.system.app.SystemApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SystemMessagingService : FirebaseMessagingService() {
    private val reminderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"]) {
            "refresh" -> SyncWorker.enqueue(applicationContext)
            "reminder" -> showReminder(message)
        }
    }

    override fun onNewToken(token: String) {
        SyncWorker.enqueue(applicationContext)
    }

    private fun showReminder(message: RemoteMessage) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val preferencesRepository = (application as? SystemApplication)?.userPreferencesRepository ?: return
        reminderScope.launch {
            val enabled = runCatching { preferencesRepository.preferences.first().taskRemindersEnabled }.getOrDefault(true)
            if (!enabled) return@launch
            displayReminder(message)
        }
    }

    private fun displayReminder(message: RemoteMessage) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val occurrenceId = message.data["occurrence_id"].orEmpty()
        val launch = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            occurrenceId.hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, "system_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(message.notification?.title ?: context.getString(dev.system.app.R.string.reminder_notification_title))
            .setContentText(message.notification?.body ?: "You have a task reminder.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(occurrenceId.hashCode(), notification)
    }
}
