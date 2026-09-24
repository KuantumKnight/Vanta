package dev.system.app

import android.app.Application
import androidx.room.Room
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.system.app.data.SystemDatabase
import dev.system.app.data.SystemRepository
import dev.system.app.sync.SyncWorker
import java.util.concurrent.TimeUnit

class SystemApplication : Application() {
    lateinit var database: SystemDatabase
        private set
    lateinit var repository: SystemRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, SystemDatabase::class.java, "system-cache.db")
            .addMigrations(SystemDatabase.MIGRATION_1_2)
            .build()
        repository = SystemRepository(this, database)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
