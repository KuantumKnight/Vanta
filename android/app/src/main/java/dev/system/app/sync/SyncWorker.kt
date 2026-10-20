package dev.system.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import dev.system.app.SystemApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val app = applicationContext as SystemApplication
        try {
            app.awaitRepository().syncNow()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val PERIODIC_NAME = "system-periodic-sync"
        const val IMMEDIATE_NAME = "system-immediate-sync"
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // A refresh that arrives during a running sync must trigger another fetch.
            WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
