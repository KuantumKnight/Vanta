package dev.system.app.calendar

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/** Result returned to UI: it either has an in-memory token or a user-launched consent flow. */
sealed interface CalendarAuthorizationResult {
    data class Authorized(val accessToken: String) : CalendarAuthorizationResult
    data class NeedsUserInteraction(val pendingIntent: PendingIntent) : CalendarAuthorizationResult
}

/**
 * Requests only the scopes needed to list calendars and read their event instances.
 * Vanta never persists this bearer token; Google Play services owns token caching.
 */
class GoogleCalendarAuthorization(context: Context) {
    private val client: AuthorizationClient = Identity.getAuthorizationClient(context.applicationContext)

    suspend fun requestAccessToken(chooseGoogleAccount: Boolean = false): CalendarAuthorizationResult {
        val request = AuthorizationRequest.Builder()
            .setRequestedScopes(READ_ONLY_CALENDAR_SCOPES)
            .apply {
                if (chooseGoogleAccount) setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
            }
            .build()
        return result(client.authorize(request).await())
    }

    fun completeUserInteraction(data: Intent): CalendarAuthorizationResult =
        result(client.getAuthorizationResultFromIntent(data))

    suspend fun clearCachedToken(accessToken: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(accessToken).build()).await()
    }

    suspend fun revokeAccess() {
        client.revokeAccess(RevokeAccessRequest.builder().setScopes(READ_ONLY_CALENDAR_SCOPES).build()).await()
    }

    private fun result(result: com.google.android.gms.auth.api.identity.AuthorizationResult): CalendarAuthorizationResult =
        if (result.hasResolution()) {
            CalendarAuthorizationResult.NeedsUserInteraction(
                requireNotNull(result.pendingIntent) { "Google authorization did not provide a consent flow." },
            )
        } else {
            CalendarAuthorizationResult.Authorized(
                requireNotNull(result.accessToken?.takeIf(String::isNotBlank)) {
                    "Google did not return Calendar read access."
                },
            )
        }

    companion object {
        val READ_ONLY_CALENDAR_SCOPES = listOf(
            Scope("https://www.googleapis.com/auth/calendar.events.readonly"),
            Scope("https://www.googleapis.com/auth/calendar.calendarlist.readonly"),
        )
    }
}
