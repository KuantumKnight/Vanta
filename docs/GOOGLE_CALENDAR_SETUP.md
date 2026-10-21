# Google Calendar setup

Batch 3 uses Google Play services `AuthorizationClient` on Android. The app requests only these read scopes:

- `https://www.googleapis.com/auth/calendar.events.readonly`
- `https://www.googleapis.com/auth/calendar.calendarlist.readonly`

The first allows the app to display event details; the second lets the user choose calendars. The app does not create, edit, or delete Google events. [Google's Calendar scope guide](https://developers.google.com/workspace/calendar/api/auth) recommends requesting only the scopes a feature needs and says public apps using user-data scopes must complete Google's verification process.

## Google Cloud Console

1. Create or select the production Google Cloud project and enable **Google Calendar API**.
2. Configure the OAuth consent screen/app branding, support contact, privacy-policy and homepage links, and the audience. Add the two exact read scopes above. Use test users while the app is in testing; complete the applicable Google OAuth verification before making a public production release.
3. Create an **Android OAuth client** for package name `dev.system.app`. Register the SHA-1 signing certificate fingerprints for each build channel that will authorize Calendar: local debug, any directly distributed release, and the Play App Signing certificate for Play-installed builds. Do not use a debug certificate for production distribution.
4. If Firebase push notifications are enabled, download the matching Firebase Android configuration as `android/app/google-services.json`. This is separate from Calendar authorization and is already ignored by Git. Never commit it.
5. Install Google Play services on the test device and test connect, account selection, denied consent, expired/revoked access, refresh, account switch, and disconnect with both debug and release-signed builds.

There is no Google OAuth redirect URI to add to the Android manifest for this flow. `GoogleCalendarAuthorization` launches the `PendingIntent` returned by Google Play services and consumes its result through the Android Activity Result API. The `dev.system.app://auth` deep link in `AndroidManifest.xml` is for Supabase email-link PKCE sign-in, not Google Calendar.

## Data and token handling in this implementation

- Access tokens come from Google Play services, are passed in memory to the Calendar REST reader, and are not written to Room, sent to Supabase, or included in logs. Vanta does not request offline access or a refresh token.
- Calendar catalog and event cache rows are keyed by the Vanta owner and a Google-account-derived key. The cache is local, limited to 7 days in the past and 60 days ahead, and refreshes after 15 minutes when the user resumes the app. Selecting another Google account clears the previous account's cached events/catalog for that Vanta owner.
- Only selected calendars' bounded event windows are cached. Disconnect removes the local cache and attempts to revoke the Google grant. If revocation fails, the UI reports that Google access could not be revoked even though local Calendar data was cleared; the user must also remove the grant in their Google Account settings.
- Calendar is optional. If it is disconnected or unavailable, Vanta still renders and edits its own schedule.

## Environment configuration

No Google OAuth client ID or client secret is read from an environment variable in the current Android source. Supabase AI planner configuration is separate; see [`.env.example`](../.env.example) and [`plan-day` setup](../supabase/functions/plan-day/README.md). Keep signing keys, downloaded OAuth credentials, and local Firebase configuration out of Git.

## References

- [Configure the OAuth consent screen](https://developers.google.com/workspace/guides/configure-oauth-consent)
- [Google Calendar API scopes](https://developers.google.com/workspace/calendar/api/auth)
- [Google Play services AuthorizationClient](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient)
