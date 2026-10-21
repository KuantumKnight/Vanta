# Google Calendar setup

Release-gate audit: 2026-10-21. This confirms the Android source configuration; it does not confirm that an OAuth client or consent-screen verification is configured in Google Cloud Console.

Batch 3 uses Google Play services `AuthorizationClient` on Android. The app requests only these read scopes:

- `https://www.googleapis.com/auth/calendar.events.readonly`
- `https://www.googleapis.com/auth/calendar.calendarlist.readonly`

The first allows the app to read event details on calendars the user can access; the second lets the user list and choose subscribed calendars. The app does not create, edit, or delete Google events. These are read-only scopes, but event details are broader than free/busy availability. [Google's Calendar scope guide](https://developers.google.com/workspace/calendar/api/auth) recommends requesting only the scopes a feature needs and says public apps using user-data scopes must complete Google's verification process.

## Google Cloud Console

1. Create or select the production Google Cloud project and enable **Google Calendar API**.
2. Configure the OAuth consent screen/app branding, support contact, privacy-policy and homepage links, and the audience. Add the two exact read scopes above. Use test users while the app is in testing; complete the applicable Google OAuth verification before making a public production release.
3. Create an **Android OAuth client** (not a Web application OAuth client) for package/application ID `dev.system.app`. Register the SHA-1 signing certificate fingerprints for each build channel that will authorize Calendar: local debug, any directly distributed release, and the **Play app-signing certificate** for Play-installed builds. The upload certificate does not replace the Play app-signing certificate. Do not use a debug certificate for production distribution. [Google Play services client authentication](https://developers.google.com/android/guides/client-auth) documents the Android package/SHA-1 binding; Play App Signing fingerprints are available in Play Console's app integrity page.
4. If Firebase push notifications are enabled, download the matching Firebase Android configuration as `android/app/google-services.json`. This is separate from Calendar authorization and is already ignored by Git. Never commit it.
5. Install Google Play services on the test device and test connect, account selection, denied consent, expired/revoked access, refresh, account switch, and disconnect with both debug and release-signed builds.

There is no Google OAuth redirect URI to add to the Android manifest for this flow. `GoogleCalendarAuthorization` uses Google Play services `AuthorizationClient`, launches its returned `PendingIntent`, and consumes its result through the Android Activity Result API. The `dev.system.app://auth` deep link in `AndroidManifest.xml` is for Supabase email-link PKCE sign-in, not Google Calendar. `AuthorizationClient` can return a resolution/consent flow, and can fail when Google Play services is unavailable; the app reports a recoverable Calendar authorization error and keeps the rest of Vanta usable.

## Data and token handling in this implementation

- Access tokens come from Google Play services, are passed in memory to the Calendar REST reader, and are not written to Room, sent to Supabase, or included in logs. Vanta does not request offline access or a refresh token. Google Play services manages its authorization cache; Vanta's Calendar cache stores event/calendar data, not bearer tokens.
- Calendar catalog and event cache rows are keyed by the Vanta owner and a Google-account-derived key. The cache is local, limited to 7 days in the past and 60 days ahead, and refreshes after 15 minutes when the user resumes the app. Selecting another Google account clears the previous account's cached events/catalog for that Vanta owner.
- Only selected calendars' bounded event windows are cached. Disconnect attempts to revoke the requested Google scopes and removes Vanta's local Calendar cache even if revocation fails. If revocation fails, the UI reports that Google access could not be revoked; the user can also remove the grant in their Google Account settings. Account switching uses an owner- and Google-account-keyed cache.
- Calendar is optional. If it is disconnected or unavailable, Vanta still renders and edits its own schedule.

## Release validation status

The code uses the Android OAuth client shape, package `dev.system.app`, the two read scopes above, no Calendar redirect URI, in-memory access-token handling, local-cache deletion on disconnect, and a disconnected/offline fallback. No access to the Google Cloud or Play Console configuration was available during this audit, and Android device tests could not run. Before release, verify the Android OAuth client has the matching package and SHA-1 fingerprints, the consent screen is configured for the release audience, and any required Google verification is complete. Treat this configuration as **not yet release-verified** until those console checks and a signed-device connect/disconnect test pass.

## Environment configuration

No Google OAuth client ID or client secret is read from an environment variable in the current Android source. Supabase AI planner configuration is separate; see [`.env.example`](../.env.example) and [`plan-day` setup](../supabase/functions/plan-day/README.md). Keep signing keys, downloaded OAuth credentials, and local Firebase configuration out of Git.

## References

- [Configure the OAuth consent screen](https://developers.google.com/workspace/guides/configure-oauth-consent)
- [Google Calendar API scopes](https://developers.google.com/workspace/calendar/api/auth)
- [Google Play services AuthorizationClient](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient)
