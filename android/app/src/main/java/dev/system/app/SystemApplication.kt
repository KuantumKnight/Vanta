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
import dev.system.app.sync.SyncWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface StartupState {
    data object Initializing : StartupState
    data class Ready(val database: SystemDatabase, val repository: SystemRepository) : StartupState
    data class Failed(val cause: Throwable) : StartupState
}

class SystemApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupLock = Any()
    private var initializationJob: Job? = null
    private val mutableStartup = MutableStateFlow<StartupState>(StartupState.Initializing)
    val startup = mutableStartup.asStateFlow()

    val repositoryOrNull: SystemRepository?
        get() = (mutableStartup.value as? StartupState.Ready)?.repository

    override fun onCreate() {
        super.onCreate()
        initialize()
    }

    fun initialize() {
        synchronized(startupLock) {
            if (initializationJob?.isActive == true) return
            mutableStartup.value = StartupState.Initializing
            initializationJob = startupScope.launch {
                try {
                    val database = Room.databaseBuilder(
                        applicationContext,
                        SystemDatabase::class.java,
                        "system-cache.db",
                    ).addMigrations(
                        SystemDatabase.MIGRATION_1_2,
                        SystemDatabase.MIGRATION_2_3,
                        SystemDatabase.MIGRATION_3_4,
                        SystemDatabase.MIGRATION_4_5,
                    ).build()
                    val repository = SystemRepository(applicationContext, database)
                    mutableStartup.value = StartupState.Ready(database, repository)
                    schedulePeriodicSync()
                } catch (error: Throwable) {
                    mutableStartup.value = StartupState.Failed(error)
                }
            }
        }
    }

    suspend fun awaitRepository(): SystemRepository = when (
        val state = mutableStartup.first { it !is StartupState.Initializing }
    ) {
        is StartupState.Ready -> state.repository
        is StartupState.Failed -> throw IllegalStateException("SYSTEM could not initialize its local data store.", state.cause)
        StartupState.Initializing -> error("Unreachable startup state")
    }

    suspend fun awaitDatabase(): SystemDatabase = when (
        val state = mutableStartup.first { it !is StartupState.Initializing }
    ) {
        is StartupState.Ready -> state.database
        is StartupState.Failed -> throw IllegalStateException("SYSTEM could not initialize its local data store.", state.cause)
        StartupState.Initializing -> error("Unreachable startup state")
    }

    private fun schedulePeriodicSync() {
        runCatching {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                SyncWorker.PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
