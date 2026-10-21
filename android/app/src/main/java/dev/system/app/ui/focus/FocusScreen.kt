package dev.system.app.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.system.app.data.CachedOccurrence
import dev.system.app.focus.FocusSession
import kotlinx.coroutines.delay

/**
 * Focus view for an already-started session. The host owns persistence and navigation: finish
 * [activeSession] in its callbacks, and route task completion through the existing repository
 * completion callback. Exiting without ending leaves the session active for later recovery.
 */
@Composable
internal fun FocusScreen(
    activeSession: FocusSession,
    occurrence: CachedOccurrence?,
    onCompleteExistingOccurrence: (CachedOccurrence) -> Unit,
    onEndEarly: () -> Unit,
    onExit: () -> Unit,
) {
    val matchingOccurrence = occurrence?.takeIf { it.id == activeSession.occurrenceId }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onExit) {
                Text("Close focus")
            }
        }

        Spacer(Modifier.weight(1f))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "FOCUS SESSION",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    activeSession.occurrenceTitle,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                FocusCountdown(session = activeSession)
            }
        }

        if (matchingOccurrence == null) {
            Text(
                "Task details are unavailable. You can leave focus and return to your timeline.",
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = {
                matchingOccurrence?.let { row ->
                    onCompleteExistingOccurrence(row)
                    onExit()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = matchingOccurrence != null,
        ) {
            Text("Complete task")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                onEndEarly()
                onExit()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("End session early")
        }
    }
}

/** Only this small leaf recomposes each second; elapsed/remaining values always use timestamps. */
@Composable
private fun FocusCountdown(session: FocusSession) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var nowEpochMillis by remember(session.startedAtEpochMillis) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(session.startedAtEpochMillis, session.plannedDurationSeconds, isResumed) {
        if (isResumed) {
            while (true) {
                nowEpochMillis = System.currentTimeMillis()
                delay(TIMER_REFRESH_MILLIS)
            }
        }
    }

    val remainingMillis = session.remainingMillis(nowEpochMillis)
    val actualElapsedMillis = session.actualElapsedMillis(nowEpochMillis)
    val progress = (actualElapsedMillis.toDouble() / session.plannedDurationMillis)
        .toFloat()
        .coerceIn(0f, 1f)
    val countdownText = formatCountdown(remainingMillis)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            countdownText,
            modifier = Modifier.semantics { contentDescription = "$countdownText remaining" },
            style = MaterialTheme.typography.displayLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 60.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            textAlign = TextAlign.Center,
        )
        Text(
            "remaining",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(22.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Elapsed ${formatElapsed(actualElapsedMillis)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Planned ${formatElapsed(session.plannedDurationMillis)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatCountdown(durationMillis: Long): String {
    val totalSeconds = (durationMillis + 999L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun formatElapsed(durationMillis: Long): String {
    val totalMinutes = durationMillis.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h ${minutes}m" else "${minutes}m"
}

private const val TIMER_REFRESH_MILLIS = 1_000L
