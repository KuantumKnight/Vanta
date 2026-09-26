# SYSTEM V1 completion plan

Updated: 26 September 2026

This plan tracks the shortest dependable path from the current repository to a polished, working SYSTEM V1. Supabase remains the authority for task data. Room is a cache; ChatGPT is the task command center; Android is the review and completion surface.

## Current state

| Area | Status | Evidence / remaining condition |
|---|---|---|
| Supabase schema and server functions | Deployed; live acceptance still needed | Seven migrations and four Edge Functions are active on `zpxcilhmbbhowqceyafj`. The live database has one Auth user and no tasks, occurrences, enabled device tokens, or sent reminders. Security Advisor reports ten authenticated `SECURITY DEFINER` RPC warnings and leaked-password protection disabled; verify user-bound RPC guards and the Auth setting before release. |
| MCP tools and OAuth server | Deployed; owner link pending | The live `mcp` function is version 3 and uses Supabase's authenticated-user OAuth integration. ChatGPT Developer mode still needs to connect and finish the owner OAuth flow. |
| Consent and email-code site | Callback and OTP UI implemented; hosted deployment/Auth settings pending | Commit `d7095b6` contains the same-host callback and code-first sign-in flow. CI lint/build passed. Vercel currently lists no accessible projects; deployment listing returns 403, the deploy connector reports its tool missing, and the CLI's TLS chain is untrusted. Supabase Auth email templates and redirect settings remain unverified. |
| Android dashboard and widget | Implemented; startup regression fixed and emulator checks pass | Commit `5fd32de` moves Room/Supabase construction off the main thread. A Firebase-configured debug build and both instrumentation tests passed. The app cold-launches to sign-in without an ANR. Authenticated dashboard, widget task rendering, and server-backed offline replay still need end-to-end verification. |
| Firebase push refresh | Android config wired; sender identity ready | Firebase project `gdg-web-dev` has app `dev.system.app`; `google-services.json` is ignored locally and its encoded config is in GitHub secret `SYSTEM_GOOGLE_SERVICES_JSON_B64`. FCM API is enabled, and dedicated `system-fcm-sender` has a custom role limited to `cloudmessaging.messages.create`. Supabase FCM secrets and a registered device token are still missing. |
| Production milestone | Not yet proven end to end | One Supabase Auth user exists, but there are no tasks or registered devices. Verify ChatGPT create → Supabase row → FCM refresh → closed-app widget after owner OAuth linking and server-side FCM configuration. |

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
- Set `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, and `FCM_PRIVATE_KEY` as Supabase Edge Function secrets. Never put the private key in Android, Vercel, Git, or chat.
- After the secrets are set, sign in on Android and verify one enabled token is stored in `device_tokens`.
- Rebuild and install the Firebase-configured APK; sign in, grant notifications, and verify the app registers an enabled device token.

**Accept when:** a server-side data-only refresh reaches a closed app, the app fetches current tasks, and Glance updates without launching the activity. Also verify token refresh and sign-out disable the old token.

### 5. Prove the core milestone and edge cases

- From ChatGPT, create “50 push-ups tomorrow” using the date returned by `get_context` and the fixed `Asia/Kolkata` zone.
- Confirm one task and one occurrence exist, then wait for push delivery with the app closed and verify the item appears under Upcoming in the widget.
- Complete it from the widget and verify server status, local cache, and duplicate-delivery behavior.
- Repeat with push disabled; wait for periodic WorkManager recovery and confirm the cache converges.
- Run the remaining security and product checks: cross-owner isolation, ambiguous-match clarification, recurring schedule edits and exclusions, reminder catch-up/deduplication, goal progress, offline replay, and sign-out.

**Accept when:** the closed-app milestone passes on a signed-in Google Play services device and every test in `docs/VERIFICATION.md` has a recorded result.

### 6. Package the handoff

- Produce the installable debug APK and record its path, build variant, and build date.
- Update setup instructions with the selected Firebase project’s non-secret identifier and the exact hosted URLs; keep all credentials local or in provider secret stores.
- Re-run web lint/build and backend recurrence/type checks, then check repository status for accidentally staged secrets or generated files.
- Push the reviewed code and documentation to `main` only after the checks pass.

**Accept when:** a clean checkout can reproduce the APK build using the documented JDK/SDK versions, the repository contains no credentials, and the milestone evidence is recorded.

## Critical path and external account steps

The hosted milestone is gated by these remaining steps: update hosted Supabase Auth URL settings, deploy the consent callback change, configure an FCM service account in Supabase, and complete ChatGPT Developer mode account linking. Current Vercel project access returns no project listings, so the callback deployment depends on the existing GitHub integration or restored Vercel access.

## Definition of done

- A polished Android app builds and installs, supports email-code sign-in, shows Today / Upcoming / Goals, searches tasks, and handles loading, empty, offline, and retry states.
- ChatGPT reads and changes the same Supabase records using authenticated MCP tools and idempotent writes.
- The widget shows overdue/today tasks before upcoming tasks and can complete an occurrence offline or online.
- One-time tasks, recurring occurrences, reminders, and goal progress obey the deployed ownership policies and documented date/time-zone rules.
- The primary milestone succeeds: “add 50 push-ups tomorrow” creates one dated occurrence and updates the widget while the app is closed.
- Automated checks pass, the APK is installable, and no private credentials are committed.

## Verification snapshot

Checked on 26 September 2026:

- Android `assembleDebug`, `assembleDebugAndroidTest`, and `connectedDebugAndroidTest` succeeded after the startup fix with Firebase config present. `connectedDebugAndroidTest` ran on `SYSTEM_API_36_PLAY`: 2 tests, 0 failures, 0 errors. It verifies sign-in address validation and persistence of the optimistic completion/idempotency key across a Room reopen. Run `lintDebug` again before release.
- The startup trace showed Supabase client construction blocking `Application.onCreate`. After moving initialization to `Dispatchers.IO`, the installed app cold-launched to sign-in; fresh logcat had no ANR or fatal exception. Screenshot: `artifacts/system-debug-apk-36231694111/system-startup.png`.
- Current Firebase-configured debug APK: `android/app/build/outputs/apk/debug/app-debug.apk` (20,442,275 bytes; SHA-256 `F9E70B8091A5A965A887C1A4FD75586A7EE1D75477B4DF1D6E43AD0FA5D04FF7`).
- Android lint still reports 10 non-blocking warnings, mainly newer suggested dependency versions, target SDK 36, deprecated Android/Firebase calls, backup rules, and the launcher icon's missing monochrome layer.
- Deno recurrence tests passed 5/5, and the documented `deno check --config supabase/deno.json` command passed for all four Edge Functions.
- The clean QA emulator displayed sign-in in light and dark mode, including 360 dp width with 1.3 font scaling. Signed-in screens and the widget data flow remain unverified on a device.
- GitHub workflow `36234395884` passed all jobs for commit `3473244c93cf5a7fdb7391dea50d7f8b46c936f5`, including Firebase config injection and Android debug APK assembly.
- Live hosted checks: Supabase is `ACTIVE_HEALTHY`; all four Edge Functions are active; recurrence/reminder jobs were previously verified enabled. Recheck unauthenticated MCP/internal endpoint 401s before release. The database has 1 Auth user, 0 tasks, 0 occurrences, 0 enabled devices, and 0 sent reminders.
- The Supabase URL and publishable key in both ignored local configuration files (`android/local.properties` and `web/consent/.env.local`) match the selected hosted project. Values were compared without printing the key.
- The Vercel deployment URL is documented from an earlier deployment, but current live verification is blocked: the connector lists no projects for its team and returns 403 for deployments; the deploy tool is unavailable. The local Vercel CLI sees an untrusted TLS chain. TLS validation was not bypassed.
- Firebase CLI 15.31.0 is authenticated and exposes `gdg-web-dev`; Android package `dev.system.app` is registered and the local `google-services.json` validates against its project/app IDs. The file is git-ignored and CI retrieves it from `SYSTEM_GOOGLE_SERVICES_JSON_B64`. FCM API is enabled and the dedicated sender has a custom role with only `cloudmessaging.messages.create`.
- The Firebase service-account email/private key are not configured in Supabase, and no Android user/device has signed in to register an FCM token. The ChatGPT OAuth account link is also not yet complete.
- Vercel’s connected project lookup returned 403 for `system-consent`; local `vercel whoami` still fails to fetch its API. The Brave browser connector reported it could not load browser request-header policy, so no browser session could be inspected.
- The consent site previously omitted `emailRedirectTo`, allowing Supabase Auth to use its localhost Site URL fallback. The web flow now sets `/auth/callback?next=...`; that route exchanges PKCE/email confirmation codes and rejects external `next` redirects. `npm run lint` and `npm run build` pass, and local callback smoke tests preserve valid consent paths while reducing `//evil.example` to `/`. Hosted Auth configuration and Vercel deployment still need verification.
