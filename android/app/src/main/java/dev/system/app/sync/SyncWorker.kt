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
        syncRequestGate.workerStarted()
        try {
            val result = app.awaitRepository().syncNow()
            if (result.retryablePending) Result.retry() else Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Result.retry()
        } finally {
            syncRequestGate.workerFinished { enqueueWork(applicationContext) }
        }
    }

    companion object {
        const val PERIODIC_NAME = "system-periodic-sync"
        const val IMMEDIATE_NAME = "system-immediate-sync"
        private val syncRequestGate = SyncRequestGate()

        fun enqueue(context: Context) {
            syncRequestGate.request { enqueueWork(context) }
        }

        private fun enqueueWork(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
