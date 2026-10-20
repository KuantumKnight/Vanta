package dev.system.app.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.Button
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.system.app.MainActivity
import dev.system.app.SystemApplication
import dev.system.app.data.CachedOccurrence
import dev.system.app.sync.SyncWorker
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val zone = ZoneId.of("Asia/Kolkata")
private val occurrenceKey = ActionParameters.Key<String>("occurrence_id")

class SystemWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val rows = try {
            (context.applicationContext as SystemApplication).awaitRepository().pendingSnapshotForCurrentUser()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("SystemWidget", "Could not read the local task cache", error)
            null
        }
        val today = LocalDate.now(zone)
        val feed = rows?.let { buildWidgetFeed(it, today) }
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val background = ColorProvider(ComposeColor(if (dark) 0xFF151C19 else 0xFFF9FBF9))
        val foreground = ColorProvider(ComposeColor(if (dark) 0xFFE0E5E2 else 0xFF171D1B))
        val muted = ColorProvider(ComposeColor(if (dark) 0xFFC0C9C4 else 0xFF404845))
        val accent = ColorProvider(ComposeColor(if (dark) 0xFF80D5C9 else 0xFF006B61))
        val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
        provideContent {
            val size = LocalSize.current
            val compact = size.height < 150.dp
            LazyColumn(
                modifier = GlanceModifier.fillMaxSize().background(background).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                item {
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = GlanceModifier.defaultWeight().clickable(openApp)) {
                            Text("SYSTEM", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp, color = accent))
                            Text(today.format(DateTimeFormatter.ofPattern("EEE, d MMM")), style = TextStyle(fontSize = 12.sp, color = muted))
                        }
                    }
                }

                if (feed == null) {
                    item {
                        Text(
                            "Couldn't load tasks. Open SYSTEM to retry.",
                            style = TextStyle(fontSize = 13.sp, color = muted),
                            modifier = GlanceModifier.padding(top = 8.dp).clickable(openApp),
                        )
                    }
                } else {
                    if (feed.todayAndOverdue.isNotEmpty()) {
                        if (!compact) item { WidgetSection("TODAY & OVERDUE", foreground) }
                        items(feed.todayAndOverdue, itemId = { it.occurrence.id.hashCode().toLong() }) { item ->
                            val label = when {
                                item.date.isBefore(today) -> "Overdue · ${item.date.format(DateTimeFormatter.ofPattern("d MMM"))} · ${item.occurrence.title}"
                                compact -> "Today · ${item.occurrence.title}"
                                else -> item.occurrence.title
                            }
                            OccurrenceRow(label, item.occurrence.id, foreground, openApp)
                        }
                    }

                    if (feed.anytime.isNotEmpty()) {
                        if (!compact) item { WidgetSection("ANYTIME", foreground) }
                        items(feed.anytime, itemId = { it.id.hashCode().toLong() }) { row ->
                            val label = if (compact) "Anytime · ${row.title}" else row.title
                            OccurrenceRow(label, row.id, foreground, openApp)
                        }
                    }

                    if (feed.upcoming.isNotEmpty()) {
                        if (!compact) item { WidgetSection("UPCOMING", foreground) }
                        items(feed.upcoming, itemId = { it.occurrence.id.hashCode().toLong() }) { item ->
                            val dateLabel = if (item.date == today.plusDays(1)) {
                                "Tomorrow"
                            } else {
                                item.date.format(DateTimeFormatter.ofPattern("d MMM"))
                            }
                            OccurrenceRow("$dateLabel · ${item.occurrence.title}", item.occurrence.id, foreground, openApp)
                        }
                    }

                    if (feed.todayAndOverdue.isEmpty() && feed.anytime.isEmpty() && feed.upcoming.isEmpty()) {
                        item {
                            Text(
                                "Add a task in ChatGPT to see it here.",
                                style = TextStyle(fontSize = 13.sp, color = muted),
                                modifier = GlanceModifier.padding(top = 8.dp).clickable(openApp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetSection(title: String, color: ColorProvider) {
    Text(
        title,
        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, color = color),
        modifier = GlanceModifier.fillMaxWidth().padding(top = 9.dp, bottom = 3.dp),
    )
}

@Composable
private fun OccurrenceRow(title: String, id: String, foreground: ColorProvider, openApp: Action) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = GlanceModifier.defaultWeight().clickable(openApp),
            style = TextStyle(color = foreground, fontSize = 13.sp),
            maxLines = 1,
        )
        Button(
            text = "Done",
            onClick = actionRunCallback<CompleteOccurrenceAction>(actionParametersOf(occurrenceKey to id)),
        )
    }
}

class CompleteOccurrenceAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val occurrenceId = parameters[occurrenceKey] ?: return
        val app = context.applicationContext as SystemApplication
        app.awaitRepository().queueCompletion(occurrenceId)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("system-complete-$occurrenceId", ExistingWorkPolicy.KEEP, request)
        SystemWidget().updateAll(context)
    }
}

class SystemWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SystemWidget()
}
