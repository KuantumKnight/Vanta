package dev.system.app

import android.app.Application
import androidx.room.Room
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.system.app.data.SystemDatabase
import dev.system.app.data.SystemRepository
import dev.system.app.calendar.GoogleCalendarRepository
import dev.system.app.notifications.VantaDailyNotificationScheduler
import dev.system.app.notifications.createVantaDailyNotificationChannels
import dev.system.app.settings.DataStoreUserPreferencesRepository
import dev.system.app.settings.UserPreferencesRepository
import dev.system.app.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SystemApplication : Application() {
    lateinit var database: SystemDatabase
        private set
    lateinit var repository: SystemRepository
        private set
    lateinit var userPreferencesRepository: UserPreferencesRepository
        private set
    /** Calendar cache is initialized only when a signed-in planner/settings surface needs it. */
    val googleCalendarRepository: GoogleCalendarRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GoogleCalendarRepository(this)
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, SystemDatabase::class.java, "system-cache.db")
            .addMigrations(
                SystemDatabase.MIGRATION_1_2,
                SystemDatabase.MIGRATION_2_3,
                SystemDatabase.MIGRATION_3_4,
                SystemDatabase.MIGRATION_4_5,
                SystemDatabase.MIGRATION_5_6,
                SystemDatabase.MIGRATION_6_7,
                SystemDatabase.MIGRATION_7_8,
                SystemDatabase.MIGRATION_8_9,
            )
            .build()
        userPreferencesRepository = DataStoreUserPreferencesRepository(this)
        repository = SystemRepository(this, database, userPreferencesRepository)
        createVantaDailyNotificationChannels(this)
        applicationScope.launch {
            userPreferencesRepository.preferences
                .distinctUntilChanged { old, new ->
                    old.morningBriefingEnabled == new.morningBriefingEnabled &&
                        old.morningBriefingMinute == new.morningBriefingMinute &&
                        old.eveningReviewEnabled == new.eveningReviewEnabled &&
                        old.eveningReviewMinute == new.eveningReviewMinute
                }
                .collect { preferences -> VantaDailyNotificationScheduler.schedule(this@SystemApplication, preferences) }
        }
        applicationScope.launch {
            userPreferencesRepository.preferences
                .map { it.taskRemindersEnabled }
                .distinctUntilChanged()
                .collect { repository.syncTaskReminderPreference() }
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
