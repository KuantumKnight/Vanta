# SYSTEM V1 completion plan

Updated: 26 September 2026

This plan tracks the shortest dependable path from the current repository to a polished, working SYSTEM V1. Supabase remains the authority for task data. Room is a cache; ChatGPT is the task command center; Android is the review and completion surface.

## Current state

| Area | Status | Evidence / remaining condition |
|---|---|---|
| Supabase schema and server functions | Deployed; live acceptance still needed | Eight migrations and four Edge Functions are active on `zpxcilhmbbhowqceyafj`. Rolled-back authenticated SQL tests verified create-task idempotency, changed-payload rejection, one occurrence, RLS read isolation, goal progress, future schedule edits with completed-history preservation, reminder claim deduplication, and cross-owner write denial. Unauthenticated MCP/internal probes returned 401; anonymous task insert and RPC access are denied. The live database still has no persisted tasks, occurrences, enabled device tokens, or sent reminders. Security Advisor reports ten authenticated `SECURITY DEFINER` RPC warnings and leaked-password protection disabled; password sign-in is not used by SYSTEM. |
| MCP tools and OAuth server | Deployed; owner link pending | The live `mcp` function is version 4 and uses Supabase's authenticated-user OAuth integration. ChatGPT Developer mode still needs to connect and finish the owner OAuth flow. |
| Consent and sign-in | Code-entry flow implemented; hosted template update and site deployment pending | Android and the consent site now verify the project's 8-digit email OTP directly, avoiding the one-use link loop. Android still accepts older native links. The live Supabase Auth email templates must be changed to include `{{ .Token }}` before the new code flow can work; the current browser control bridge cannot reach the dashboard and the CLI does not manage hosted template bodies. The new consent routes pass local lint/build; Vercel access lists no projects for the connected team, so the updated site has not been deployed. |
| Android dashboard and widget | Implemented; rebuilt and emulator-tested | Current APK builds and lint passes. Four instrumentation tests pass, including email validation, Android deep-link fallback, Room offline-queue persistence, and timezone-aware widget grouping. The app cold-launches to the email sign-in screen. Authenticated dashboard rendering, an actual task row in the widget, and server-backed offline replay still need end-to-end verification. |
| Firebase push refresh | Sender credentials configured; device test pending | Firebase project `gdg-web-dev` has app `dev.system.app`; `google-services.json` is ignored locally and its encoded config is in GitHub secret `SYSTEM_GOOGLE_SERVICES_JSON_B64`. FCM API is enabled, and dedicated `system-fcm-sender` has a custom role limited to `cloudmessaging.messages.create`. FCM credentials are now Supabase Edge Function secrets and successfully minted a short-lived FCM OAuth token. A signed-in device token and delivery test are still missing. |
| Production milestone | Not yet proven end to end | One Supabase Auth user exists, but there are no tasks or registered devices. Verify ChatGPT create → Supabase row → FCM refresh → closed-app widget after owner OAuth linking and a phone sign-in. |

## Build sequence

### 1. Lock the Android build and polish the dashboard

- Build with JDK 17 and the installed Android SDK; fix compiler, lint, and manifest issues found by the build.
- Refine the signed-in dashboard into a strong daily overview: clear due-today/overdue counts, distinct Today / Upcoming / Goals navigation, concise task cards, and a visible sync state.
- Keep the teal and neutral palette consistent in light and dark themes. Check large font scaling, compact widths, safe areas, contrast, keyboard behavior, and at least 48 dp controls.
- Polish widget sizing at compact and expanded sizes. Keep overdue and today first, dates on upcoming tasks, a useful empty state, and reachable completion/open-app actions.
- Preserve task creation and editing in ChatGPT; the Android app should remain quick to scan and complete.

**Accept when:** `assembleDebug` and lint pass; the dashboard and widget fit a small screen and large text without clipping; a completed item disappears from both views after sync. The sign-in and offline queue persistence instrumentation tests pass on the QA emulator.

### 2. Verify local-first completion and sync recovery

- Confirm each offline tap creates exactly one pending completion with a persisted idempotency key.
- Replay queued completions on reconnect; remove successful items and leave failures visible with a retry action.
- Confirm server state wins after refresh, including when a queued completion races with a ChatGPT update.
- Check foreground sync, FCM-triggered one-time work, and periodic WorkManager recovery. Treat periodic work as delayed recovery, not a one-minute guarantee.

**Accept when:** the optimistic completion and its stable idempotency key survive database reopen, reconnect replays once, a rejected replay remains retryable, and a later successful fetch refreshes Room and Glance. The persistence part is verified; network replay and server conflict behavior still need an authenticated end-to-end run.

### 3. Finish hosted Auth and ChatGPT linking

- In Supabase Auth settings, set the SYSTEM consent origin and authorization path `/oauth/consent`; preserve existing redirect URLs and review the global Site URL effect on any other apps sharing this project.
- Verify the consent flow can identify the requesting OAuth client, show requested scopes, approve and deny, and return to the MCP client.
- Add `https://<project-ref>.supabase.co/functions/v1/mcp` in ChatGPT Developer mode and finish OAuth as the owner account.
- Test `get_context`, date-only and relative-date task creation, search/list, update, complete, and write retries with the same operation ID.

**Accept when:** OAuth-linked MCP calls operate as the owner under RLS; unauthenticated calls fail; a repeated operation ID produces one mutation; a new operation ID intentionally creates a second request.

### 4. Configure Firebase push refresh

- Firebase CLI sign-in and project access to `gdg-web-dev` are verified; its Android app is registered. Local `google-services.json` is ignored, and CI retrieves it from `SYSTEM_GOOGLE_SERVICES_JSON_B64`.
- Cloud Messaging is enabled; the `system-fcm-sender` service account has the custom `systemFcmSender` role, limited to `cloudmessaging.messages.create`.
- `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, and `FCM_PRIVATE_KEY` are set as Supabase Edge Function secrets. The private key was used only from an ignored temporary file, then removed from this machine; never put it in Android, Vercel, Git, or chat.
- After the secrets are set, sign in on Android and verify one enabled token is stored in `device_tokens`.
- Rebuild and install the Firebase-configured APK; sign in, grant notifications, and verify the app registers an enabled device token.

**Accept when:** a server-side data-only refresh reaches a closed app, the app fetches current tasks, and Glance updates without launching the activity. Also verify token refresh and sign-out disable the old token.

### 5. Prove the core milestone and edge cases

- From ChatGPT, create “50 push-ups tomorrow” using the date returned by `get_context` and the fixed `Asia/Kolkata` zone.
- Confirm one task and one occurrence exist, then wait for push delivery with the app closed and verify the item appears under Upcoming in the widget.
- Complete it from the widget and verify server status, local cache, and duplicate-delivery behavior.
- Repeat with push disabled; wait for periodic WorkManager recovery and confirm the cache converges.
- Run the remaining security and product checks: ambiguous-match clarification, recurring schedule edits and exclusions, reminder catch-up/deduplication, goal progress, offline replay, and sign-out.

**Accept when:** the closed-app milestone passes on a signed-in Google Play services device and every test in `docs/VERIFICATION.md` has a recorded result.

### 6. Package the handoff

- Produce the installable debug APK and record its path, build variant, and build date.
- Update setup instructions with the selected Firebase project’s non-secret identifier and the exact hosted URLs; keep all credentials local or in provider secret stores.
- Re-run web lint/build and backend recurrence/type checks, then check repository status for accidentally staged secrets or generated files.
- Push the reviewed code and documentation to `main` only after the checks pass.

**Accept when:** a clean checkout can reproduce the APK build using the documented JDK/SDK versions, the repository contains no credentials, and the milestone evidence is recorded.

## Critical path and external account steps

The hosted milestone is gated by these remaining steps: deploy the consent UI update, sign in on the Android device, and complete ChatGPT Developer mode account linking. Supabase Auth now allows both the Vercel callback and Android deep link, and the FCM service credentials are configured. Vercel access currently returns no project listings, so the consent deployment depends on the existing GitHub integration or restored Vercel access.

## Definition of done

- A polished Android app builds and installs, supports email-code sign-in with older native-link fallback, shows Today / Upcoming / Goals, searches tasks, and handles loading, empty, offline, and retry states.
- ChatGPT reads and changes the same Supabase records using authenticated MCP tools and idempotent writes.
- The widget shows overdue/today tasks before upcoming tasks and can complete an occurrence offline or online.
- One-time tasks, recurring occurrences, reminders, and goal progress obey the deployed ownership policies and documented date/time-zone rules.
- The primary milestone succeeds: “add 50 push-ups tomorrow” creates one dated occurrence and updates the widget while the app is closed.
- Automated checks pass, the APK is installable, and no private credentials are committed.

## Verification snapshot

Checked on 26 September 2026:

- Android `assembleDebug`, `assembleDebugAndroidTest`, `connectedDebugAndroidTest`, and `lintDebug` passed with Firebase config present. `connectedDebugAndroidTest` ran on `SYSTEM_API_36_PLAY`: 4 tests, 0 failures, 0 errors. It verifies sign-in address validation, that `dev.system.app://auth` resolves to the app, persistence of the optimistic completion/idempotency key across a Room reopen, and timezone-aware widget grouping.
- The startup trace showed Supabase client construction blocking `Application.onCreate`. After moving initialization to `Dispatchers.IO`, the installed app cold-launched to sign-in; fresh logcat had no ANR or fatal exception. Screenshot: `artifacts/system-debug-apk-36231694111/system-startup.png`.
- Current Firebase-configured installable debug APK: `artifacts/SYSTEM-debug.apk` (20,442,275 bytes; SHA-256 `353363A3C47B0191B19B723F3B29CA2A5D9E8CF8C0BFF29D7D500581CBB8137C`). It was installed on the QA emulator; cold launch reached the email-code sign-in screen without a fatal exception or ANR. Screenshot: `artifacts/system-signin-otp.png`.
- Android lint still reports 10 non-blocking warnings, mainly newer suggested dependency versions, target SDK 36, deprecated Android/Firebase calls, backup rules, and the launcher icon's missing monochrome layer.
- Deno recurrence tests passed 5/5, and the documented `deno check --config supabase/deno.json` command passed for all four Edge Functions.
- The clean QA emulator displayed sign-in in light and dark mode, including 360 dp width with 1.3 font scaling. Signed-in screens and the widget data flow remain unverified on a device.
- GitHub workflow `36246772258` passed all jobs for commit `c174b37ce6364ec6396ec830516e2b98f6a52209`, including Firebase config injection and Android debug APK assembly.
- Live hosted checks: Supabase is `ACTIVE_HEALTHY`; all four Edge Functions are active; recurrence/reminder jobs were previously verified enabled. Unauthenticated MCP, `sync-push`, and `send-reminders` requests each returned 401. Anonymous task insertion returned 401, and SQL ACL checks confirm `anon` cannot insert tasks or call `mcp_create_task`. Rolled-back SQL tests verified MCP create idempotency, owner RLS reads, goal progress, future habit schedule editing/history preservation, and reminder claim deduplication. The database has 1 Auth user, 0 persisted tasks, 0 occurrences, 0 enabled devices, and 0 sent reminders.
- The Supabase URL and publishable key in both ignored local configuration files (`android/local.properties` and `web/consent/.env.local`) match the selected hosted project. Values were compared without printing the key.
- The Supabase Auth redirect update is deployed and `config diff` confirms the production Vercel callback plus Android callback are allowlisted. The hosted project uses 8-digit email tokens. Android and the consent site now support code entry, but the hosted Magic Link and Confirm signup email templates still need to replace `{{ .ConfirmationURL }}` with `{{ .Token }}`; Supabase CLI config push does not manage these hosted templates.
- Vercel live verification/deployment remains blocked: the connector lists no projects for its team, deployment fetch says the deployment is not accessible, and project lookup has an argument-schema mismatch. The local Vercel CLI sees an untrusted TLS chain. TLS validation was not bypassed.
- Firebase CLI 15.31.0 is authenticated and exposes `gdg-web-dev`; Android package `dev.system.app` is registered and the local `google-services.json` validates against its project/app IDs. The file is git-ignored and CI retrieves it from `SYSTEM_GOOGLE_SERVICES_JSON_B64`. FCM API is enabled and the dedicated sender has a custom role with only `cloudmessaging.messages.create`.
- The FCM service-account credentials are configured in Supabase and their OAuth token exchange was verified without sending a push. No Android user/device has signed in to register an FCM token. The ChatGPT OAuth account link is also not yet complete.
- Vercel currently lists no projects for the connected team; deployment listing returns 403, and `get_project` fails because the plugin's `projectId` argument does not match the backend's `idOrName` requirement. Local `vercel whoami` still fails its TLS validation, and the Brave browser connector cannot load its request-header policy.
- The consent site requests an 8-digit code and verifies it server-side while setting the authenticated cookie. The `/auth/callback` route remains as a fallback for older links and exchanges PKCE/email confirmation codes while rejecting external `next` redirects. `npm run lint` and `npm run build` pass. The Supabase redirect allowlist is verified; deploying the consent UI and completing live email-code verification remain open.
- Android requests an 8-digit email code and verifies it with Supabase; the native `dev.system.app://auth` PKCE callback remains available for older link emails. `assembleDebug`, lint, and 4 emulator instrumentation tests pass. A real owner-inbox sign-in remains pending the hosted template change.
