package dev.system.app.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.system.app.calendar.CalendarCatalogEntity
import dev.system.app.calendar.CalendarSyncStatus
import dev.system.app.ui.theme.VantaSpacing
import java.time.Instant
import java.time.ZoneId

/** Settings surface to embed in the existing account/preferences destination. */
@Composable
fun GoogleCalendarSettingsPanel(
    status: CalendarSyncStatus,
    calendars: List<CalendarCatalogEntity>,
    isRefreshing: Boolean,
    now: Instant = Instant.now(),
    @Suppress("UNUSED_PARAMETER") zoneId: ZoneId = ZoneId.systemDefault(),
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onCalendarSelection: (calendarId: String, selected: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stale = status.isStale(now.toEpochMilli())
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = VantaSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(VantaSpacing.xs),
    ) {
        Text("Google Calendar", style = MaterialTheme.typography.titleMedium)
        when {
            !status.connected && status.lastSuccessAt != null ->
                Text("Disconnected. Cached events are stale until you reconnect.", color = MaterialTheme.colorScheme.error)
            !status.connected && status.lastErrorCode != null ->
                Text("Google Calendar needs authorization. Reconnect to continue.", color = MaterialTheme.colorScheme.error)
            !status.connected ->
                Text("Connect a Google account to show its commitments in Vanta.")
            status.lastErrorCode != null ->
                Text("Calendar refresh failed. Showing cached events.", color = MaterialTheme.colorScheme.error)
            stale ->
                Text("Calendar is connected. Its event cache needs a refresh.")
            else ->
                Text("Calendar is connected.")
        }

        if (status.connected) {
            if (calendars.isEmpty()) {
                Text("No calendars are available for this Google account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Show these calendars", style = MaterialTheme.typography.labelLarge)
                calendars.forEach { calendar ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = calendar.selected,
                            onCheckedChange = { onCalendarSelection(calendar.calendarId, it) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(calendar.name)
                            if (calendar.isPrimary) {
                                Text("Primary calendar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(VantaSpacing.xs)) {
                OutlinedButton(onClick = onRefresh, enabled = !isRefreshing) {
                    Text(if (isRefreshing) "Refreshing…" else "Refresh")
                }
                OutlinedButton(onClick = onDisconnect, enabled = !isRefreshing) { Text("Disconnect") }
            }
        } else {
            Button(onClick = onConnect, enabled = !isRefreshing) {
                Text(if (status.lastSuccessAt == null) "Connect Google Calendar" else "Reconnect Google Calendar")
            }
        }

        Text(
            "Calendar events are read-only in Vanta. Disconnecting removes cached Google events.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
