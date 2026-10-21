package dev.system.app.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.DpSize
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
import androidx.glance.appwidget.provideContent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.system.app.MainActivity
import dev.system.app.SystemApplication
import dev.system.app.sync.SyncWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val zone = ZoneId.of("Asia/Kolkata")
private val occurrenceKey = ActionParameters.Key<String>("occurrence_id")

class SystemWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    /** Compatibility integration for existing repository call sites. */
    suspend fun updateAll(context: Context) {
        VantaWidgetRefresh.refresh(context)
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = LocalDate.now(zone)
        val persistedState = WidgetDayStateStore.read(context)
        val state = persistedState?.takeIf { it.localDate == today.toString() }
        if (state == null) VantaWidgetRefresh.requestRefresh(context)

        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val colors = WidgetColors(dark)
        val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
        val quickAdd = actionStartActivity(quickAddIntent(context, WidgetIntentContract.SOURCE_NOW_NEXT))

        provideContent {
            val sizeClass = WidgetSizeClass.from(LocalSize.current)
            NowNextContent(today, state, sizeClass, colors, openApp, quickAdd)
        }
    }
}

@Composable
private fun NowNextContent(
    today: LocalDate,
    state: WidgetDayState?,
    sizeClass: WidgetSizeClass,
    colors: WidgetColors,
    openApp: Action,
    quickAdd: Action,
) {
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = GlanceModifier.defaultWeight().clickable(openApp)) {
                Text("VANTA", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = colors.accent))
                Text(today.format(DateTimeFormatter.ofPattern("EEE · d MMM")), style = TextStyle(fontSize = 11.sp, color = colors.muted))
            }
            Text("＋", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = colors.accent), modifier = GlanceModifier.clickable(quickAdd))
        }

        Spacer(GlanceModifier.height(8.dp))
        when {
            state == null -> EmptyWidgetMessage("Updating your plan…", colors.muted, openApp)
            state.isEmpty -> EmptyWidgetMessage("Your day is open.", colors.muted, openApp)
            state.currentOccurrence == null && state.nextOccurrence == null -> EmptyWidgetMessage("No more scheduled tasks today", colors.muted, openApp)
            else -> {
                state.currentOccurrence?.let { current ->
                    WidgetLabel("NOW", colors.accent)
                    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = GlanceModifier.defaultWeight().clickable(openApp)) {
                            Text(current.title, style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, color = colors.foreground), maxLines = 1)
                            current.end?.let { end ->
                                Text("Ends ${clockLabel(end)}", style = TextStyle(fontSize = 12.sp, color = colors.muted))
                            }
                        }
                        CompleteButton(current.id)
                    }
                }

                state.nextOccurrence?.let { next ->
                    WidgetLabel(if (state.currentOccurrence == null) nextLabel(next) else "NEXT · ${clockLabel(checkNotNull(next.start))}", colors.accent)
                    Text(next.title, style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, color = colors.foreground), maxLines = 1, modifier = GlanceModifier.fillMaxWidth().clickable(openApp))
                    val nextStart = next.start
                    if (state.currentOccurrence == null && nextStart != null) {
                        val label = if (nextStart.atZone(zone).toLocalDate() == today) "Free until ${clockLabel(nextStart)}" else "Next plan ${dateTimeLabel(nextStart)}"
                        Text(label, style = TextStyle(fontSize = 11.sp, color = colors.muted))
                    } else if (sizeClass == WidgetSizeClass.EXPANDED) {
                        Text("Up next", style = TextStyle(fontSize = 11.sp, color = colors.muted))
                    }
                }

                if (state.nextOccurrence == null) {
                    Text("No more scheduled tasks today", style = TextStyle(fontSize = 12.sp, color = colors.muted), modifier = GlanceModifier.padding(top = 5.dp))
                }

                if (sizeClass == WidgetSizeClass.EXPANDED && state.todayItems.size > 1) {
                    Spacer(GlanceModifier.height(4.dp))
                    state.todayItems.asSequence()
                        .filterNot { it.id == state.currentOccurrence?.id || it.id == state.nextOccurrence?.id }
                        .take(2)
                        .forEach { item ->
                            val time = if (item.allDay) "ALL DAY" else item.scheduledFor
                                ?.let { runCatching { clockLabel(Instant.parse(it)) }.getOrNull() }
                                ?: "Anytime"
                            Text(
                                "$time  ${item.title}",
                                style = TextStyle(fontSize = 11.sp, color = colors.muted),
                                maxLines = 1,
                            )
                        }
                }
            }
        }

        if (state?.syncWarning == true) {
            Text("Saved offline · sync will retry", style = TextStyle(fontSize = 10.sp, color = colors.muted), modifier = GlanceModifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun EmptyWidgetMessage(message: String, color: ColorProvider, openApp: Action) {
    Column(modifier = GlanceModifier.fillMaxWidth().clickable(openApp).padding(top = 4.dp)) {
        Text(message, style = TextStyle(fontSize = 14.sp, color = color), maxLines = 2)
        Text("Open Vanta or add a task", style = TextStyle(fontSize = 11.sp, color = color), modifier = GlanceModifier.padding(top = 3.dp))
    }
}

@Composable
private fun WidgetLabel(label: String, color: ColorProvider) {
    Text(label, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 10.sp, color = color), modifier = GlanceModifier.fillMaxWidth())
}

@Composable
private fun CompleteButton(id: String) {
    Button(
        text = "Done",
        onClick = actionRunCallback<CompleteOccurrenceAction>(actionParametersOf(occurrenceKey to id)),
    )
}

class CompleteOccurrenceAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val occurrenceId = parameters[occurrenceKey] ?: return
        val app = context.applicationContext as SystemApplication
        // Mark before the repository refresh so the completed row survives the pending-only snapshot.
        withContext(Dispatchers.IO) { WidgetDayStateStore.markCompleted(context, occurrenceId) }
        app.repository.queueCompletion(occurrenceId)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("system-complete-$occurrenceId", ExistingWorkPolicy.REPLACE, request)
    }
}

class SystemWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SystemWidget()
}

internal enum class WidgetSizeClass {
    COMPACT,
    MEDIUM,
    EXPANDED;

    companion object {
        fun from(size: DpSize): WidgetSizeClass = when {
            size.width < 180.dp || size.height < 120.dp -> COMPACT
            size.width < 300.dp || size.height < 220.dp -> MEDIUM
            else -> EXPANDED
        }
    }
}

internal class WidgetColors(dark: Boolean) {
    val background = ColorProvider(ComposeColor(if (dark) 0xFF151C19 else 0xFFF9FBF9))
    val foreground = ColorProvider(ComposeColor(if (dark) 0xFFE0E5E2 else 0xFF171D1B))
    val muted = ColorProvider(ComposeColor(if (dark) 0xFFC0C9C4 else 0xFF52605A))
    val accent = ColorProvider(ComposeColor(if (dark) 0xFF80D5C9 else 0xFF006B61))
}

internal fun quickAddIntent(context: Context, source: String): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(WidgetIntentContract.ACTION_QUICK_ADD)
        .putExtra(WidgetIntentContract.EXTRA_QUICK_ADD_SOURCE, source)
        .putExtra(WidgetIntentContract.EXTRA_QUICK_ADD_DATE, LocalDate.now(zone).toString())
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

private fun nextLabel(item: WidgetDayItem): String {
    val start = item.start ?: return "NEXT"
    return if (start.atZone(zone).toLocalDate() == LocalDate.now(zone)) "NEXT · ${clockLabel(start)}" else "NEXT · ${dateTimeLabel(start)}"
}

private fun clockLabel(instant: Instant): String = DateTimeFormatter.ofPattern("h:mm a").format(instant.atZone(zone))

private fun dateTimeLabel(instant: Instant): String = DateTimeFormatter.ofPattern("EEE · h:mm a").format(instant.atZone(zone))
