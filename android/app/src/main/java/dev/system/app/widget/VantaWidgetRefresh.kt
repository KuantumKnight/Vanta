package dev.system.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll as updateGlanceWidgets
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.system.app.SystemApplication
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rebuilds the local, bounded widget projection at repository and time boundaries. */
object VantaWidgetRefresh {
    private val zone = ZoneId.of("Asia/Kolkata")
    private const val BOUNDARY_WORK_NAME = "vanta-widget-day-boundary-refresh"

    /**
     * Call after task create/complete/reschedule, sync changes, or account changes.
     * This reads only the Room snapshot and never performs network work.
     */
    suspend fun refresh(context: Context) {
        val appContext = context.applicationContext
        if (!hasWidgetInstances(appContext)) {
            WorkManager.getInstance(appContext).cancelUniqueWork(BOUNDARY_WORK_NAME)
            return
        }

        val (oldState, newState) = withContext(Dispatchers.IO) {
            val application = appContext as SystemApplication
            val now = Instant.now()
            val today = now.atZone(zone).toLocalDate()
            val previous = WidgetDayStateStore.read(appContext)
            val snapshot = application.repository.widgetSnapshot(now)
            val state = buildWidgetDayState(
                rows = snapshot.occurrences,
                today = today,
                now = now,
                zone = zone,
                todayPendingCountOverride = snapshot.todayPendingCount,
                todayCompletedCountOverride = snapshot.todayCompletedCount,
                plannedMinutesOverride = snapshot.plannedMinutes,
                syncWarning = application.repository.syncState.value.error != null,
            )
            WidgetDayStateStore.write(appContext, state)
            scheduleBoundaryRefresh(appContext, state.nextBoundaryEpochMillis, now.toEpochMilli())
            previous to state
        }

        if (
            oldState?.nowNextProjection() != newState.nowNextProjection() &&
            hasWidgetInstance(appContext, SystemWidgetReceiver::class.java)
        ) {
            SystemWidget().updateGlanceWidgets(appContext)
        }
        if (
            oldState?.todayProjection() != newState.todayProjection() &&
            hasWidgetInstance(appContext, TodayCompactWidgetReceiver::class.java)
        ) {
            TodayCompactWidget().updateGlanceWidgets(appContext)
        }
    }

    /** Re-renders installed widgets after a visual preference changes, without rebuilding day state. */
    suspend fun refreshAppearance(context: Context) {
        val appContext = context.applicationContext
        if (hasWidgetInstance(appContext, SystemWidgetReceiver::class.java)) {
            SystemWidget().updateGlanceWidgets(appContext)
        }
        if (hasWidgetInstance(appContext, TodayCompactWidgetReceiver::class.java)) {
            TodayCompactWidget().updateGlanceWidgets(appContext)
        }
    }

    /** Used for first placement and malformed/missing persisted state. */
    fun requestRefresh(context: Context) {
        val appContext = context.applicationContext
        val request = OneTimeWorkRequestBuilder<WidgetBoundaryRefreshWorker>().build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            BOUNDARY_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun scheduleBoundaryRefresh(context: Context, boundaryEpochMillis: Long, nowEpochMillis: Long) {
        val delayMillis = (boundaryEpochMillis - nowEpochMillis).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<WidgetBoundaryRefreshWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            BOUNDARY_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun hasWidgetInstances(context: Context): Boolean {
        return listOf(
            SystemWidgetReceiver::class.java,
            TodayCompactWidgetReceiver::class.java,
        ).any { receiver -> hasWidgetInstance(context, receiver) }
    }

    private fun hasWidgetInstance(context: Context, receiver: Class<*>): Boolean =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, receiver))
            .isNotEmpty()

    private fun WidgetDayState.nowNextProjection() = listOf(
        localDate,
        currentOccurrence,
        nextOccurrence,
        syncWarning,
    )

    private fun WidgetDayState.todayProjection() = listOf(
        localDate,
        todayItems,
        plannedMinutes,
        completedCount,
        totalCount,
        syncWarning,
    )
}

class WidgetBoundaryRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        VantaWidgetRefresh.refresh(applicationContext)
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }
}
