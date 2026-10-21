package dev.system.app.ui.calendar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.system.app.calendar.CalendarAuthorizationResult
import dev.system.app.calendar.GoogleCalendarAuthorization
import kotlinx.coroutines.launch

/**
 * Returns an authorization action for an explicit Connect/Refresh button press. Do not invoke at
 * app startup or from a background refresh; those callers should request a token and leave a pending
 * interaction for the user instead of opening UI unexpectedly.
 */
@Composable
fun rememberGoogleCalendarUserAuthorization(
    onAccessToken: (String) -> Unit,
    onCancelled: () -> Unit,
    onError: (String) -> Unit,
): (chooseGoogleAccount: Boolean) -> Unit {
    val context = LocalContext.current
    val authorization = remember(context) {
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) { GoogleCalendarAuthorization(context.applicationContext) }
    }
    val coroutineScope = rememberCoroutineScope()
    val latestOnAccessToken = rememberUpdatedState(onAccessToken)
    val latestOnCancelled = rememberUpdatedState(onCancelled)
    val latestOnError = rememberUpdatedState(onError)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK || result.data == null) {
            latestOnCancelled.value()
        } else {
            runCatching { authorization.value.completeUserInteraction(result.data!!) }
                .onSuccess { outcome ->
                    when (outcome) {
                        is CalendarAuthorizationResult.Authorized -> latestOnAccessToken.value(outcome.accessToken)
                        is CalendarAuthorizationResult.NeedsUserInteraction ->
                            latestOnError.value("Reconnect Google Calendar to grant access.")
                    }
                }
                .onFailure { latestOnError.value("Google Calendar authorization is unavailable.") }
        }
    }

    return remember(authorization, coroutineScope, launcher) {
        { chooseGoogleAccount ->
            coroutineScope.launch {
                runCatching { authorization.value.requestAccessToken(chooseGoogleAccount) }
                    .onSuccess { outcome ->
                        when (outcome) {
                            is CalendarAuthorizationResult.Authorized -> latestOnAccessToken.value(outcome.accessToken)
                            is CalendarAuthorizationResult.NeedsUserInteraction -> {
                                launcher.launch(IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build())
                            }
                        }
                    }
                    .onFailure { latestOnError.value("Google Calendar authorization is unavailable.") }
            }
        }
    }
}
