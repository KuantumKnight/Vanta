package dev.system.app.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
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
import androidx.glance.Button
import dev.system.app.MainActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val zone = ZoneId.of("Asia/Kolkata")
private val occurrenceKey = androidx.glance.action.ActionParameters.Key<String>("occurrence_id")

class TodayCompactWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = LocalDate.now(zone)
        val persisted = WidgetDayStateStore.read(context)
        val state = persisted?.takeIf { it.localDate == today.toString() }
        if (state == null) VantaWidgetRefresh.requestRefresh(context)

        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val colors = WidgetColors(dark)
        val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
        val quickAdd = actionStartActivity(quickAddIntent(context, WidgetIntentContract.SOURCE_TODAY_COMPACT))

        provideContent {
            TodayCompactContent(today, state, WidgetSizeClass.from(LocalSize.current), colors, openApp, quickAdd)
        }
    }
}

@Composable
private fun TodayCompactContent(
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
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = GlanceModifier.defaultWeight().clickable(openApp)) {
                Text("TODAY  ·  ${today.format(DateTimeFormatter.ofPattern("EEE d"))}", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, color = colors.accent))
                if (state != null) {
                    Text("${state.totalCount} tasks · ${formatPlanned(state.plannedMinutes)} planned", style = TextStyle(fontSize = 10.sp, color = colors.muted))
                }
            }
            Text("＋", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = colors.accent), modifier = GlanceModifier.clickable(quickAdd))
        }

        Spacer(GlanceModifier.height(4.dp))
        when {
            state == null -> Text("Updating your plan…", style = TextStyle(fontSize = 12.sp, color = colors.muted))
            state.todayItems.isEmpty() -> Text("Nothing scheduled today", style = TextStyle(fontSize = 12.sp, color = colors.muted), modifier = GlanceModifier.clickable(openApp))
            else -> {
                val maxRows = when (sizeClass) {
                    WidgetSizeClass.COMPACT -> 2
                    WidgetSizeClass.MEDIUM -> 4
                    WidgetSizeClass.EXPANDED -> 7
                }
                val visible = state.todayItems.take(maxRows)
                visible.forEach { item -> TodayItemRow(item, colors, openApp) }
                val remaining = (state.totalCount - visible.size).coerceAtLeast(0)
                if (remaining > 0) {
                    Text("+$remaining more", style = TextStyle(fontSize = 10.sp, color = colors.muted), modifier = GlanceModifier.padding(top = 2.dp).clickable(openApp))
                }
            }
        }

        if (state?.syncWarning == true) {
            Text("Saved offline · sync will retry", style = TextStyle(fontSize = 10.sp, color = colors.muted), modifier = GlanceModifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun TodayItemRow(item: WidgetDayItem, colors: WidgetColors, openApp: Action) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val startLabel = if (item.allDay) "ALL DAY" else item.start?.let(::clockLabel) ?: "ANYTIME"
        Text(startLabel, style = TextStyle(fontSize = 10.sp, color = colors.muted), modifier = GlanceModifier.padding(end = 7.dp))
        Text(item.title, style = TextStyle(fontSize = 12.sp, color = if (item.completed) colors.muted else colors.foreground), maxLines = 1, modifier = GlanceModifier.defaultWeight().clickable(openApp))
        if (item.completed) {
            Text("✓", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, color = colors.accent))
        } else {
            Button(
                text = "Done",
                onClick = actionRunCallback<CompleteOccurrenceAction>(actionParametersOf(occurrenceKey to item.id)),
            )
        }
    }
}

class TodayCompactWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayCompactWidget()
}

private fun formatPlanned(minutes: Int): String {
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours == 0 -> "${remaining}m"
        remaining == 0 -> "${hours}h"
        else -> "${hours}h ${remaining}m"
    }
}

private fun clockLabel(instant: Instant): String = DateTimeFormatter.ofPattern("HH:mm").format(instant.atZone(zone))
