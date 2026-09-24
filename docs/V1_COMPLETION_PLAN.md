# SYSTEM V1 completion plan

Updated: 24 September 2026

This plan tracks the shortest dependable path from the current repository to a polished, working SYSTEM V1. Supabase remains the authority for task data. Room is a cache; ChatGPT is the task command center; Android is the review and completion surface.

## Current state

| Area | Status | Evidence / remaining condition |
|---|---|---|
| Supabase schema and server functions | Deployed | Seven migrations and four Edge Functions are live on the selected project. RLS and anonymous RPC rejection were checked. Re-run the security review after any schema change. |
| MCP tools and OAuth server | Deployed; public discovery verified; owner link pending | An unauthenticated MCP initialize returns 401 with OAuth metadata, and the Supabase authorization-server metadata endpoint returns 200. ChatGPT Developer mode still needs to connect and finish the owner OAuth flow. |
| Consent and email-code site | Previously deployed; current deployment access unverified | Production URL: [system-consent.vercel.app](https://system-consent.vercel.app). The saved Vercel project link is `system-consent`, but the connected Vercel account gets 403 when listing its deployments and the local CLI cannot reach its API. Auth Site URL and `/oauth/consent` also need final confirmation in Supabase Auth settings. |
| Android dashboard and widget | Implemented; build, lint, sign-in smoke test, and offline-queue test pass | Compose views, Room cache, Glance widget, completion queue, and WorkManager sync are present. QA instrumentation verifies the email sign-in screen and address validation, plus persistence of optimistic completion and its idempotency key across database reopen. Signed-in/device sync remains unverified. |
| Firebase push refresh | Android project configured; server credential pending | Firebase project `gdg-web-dev` now has package `dev.system.app` registered. Its `google-services.json` is valid, ignored by Git, and the Firebase-configured Android build passes. Supabase still has no FCM service-account secrets or registered device tokens. |
| Production milestone | Not yet proven end to end | Live checks show no Supabase Auth users and no registered device tokens. Verify ChatGPT create → Supabase row → push → closed-app widget after owner OAuth linking and server-side FCM configuration. |

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

- Firebase CLI sign-in, project selection (`gdg-web-dev`), Android app registration, and local `google-services.json` setup are complete.
- Enable Cloud Messaging; create or select a service account with the required messaging permission.
- Set `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, and `FCM_PRIVATE_KEY` as Supabase Edge Function secrets. Never put the private key in Android, Vercel, Git, or chat.
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

The hosted milestone is gated by these remaining owner actions: configure an FCM service account in Supabase, approve the Supabase Auth configuration for the consent origin, and complete ChatGPT Developer mode account linking. Current Vercel project access also needs to be restored if the consent site needs a redeploy.

## Definition of done

- A polished Android app builds and installs, supports email-code sign-in, shows Today / Upcoming / Goals, searches tasks, and handles loading, empty, offline, and retry states.
- ChatGPT reads and changes the same Supabase records using authenticated MCP tools and idempotent writes.
- The widget shows overdue/today tasks before upcoming tasks and can complete an occurrence offline or online.
- One-time tasks, recurring occurrences, reminders, and goal progress obey the deployed ownership policies and documented date/time-zone rules.
- The primary milestone succeeds: “add 50 push-ups tomorrow” creates one dated occurrence and updates the widget while the app is closed.
- Automated checks pass, the APK is installable, and no private credentials are committed.

## Verification snapshot

Checked on 24 September 2026:

- Android `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, and `lintDebug` succeeded both before and after Firebase config was added. The Firebase-configured build ran `processDebugGoogleServices` successfully. `testDebugUnitTest` still reports `NO-SOURCE`.
- `connectedDebugAndroidTest` passed on the isolated `SYSTEM_CI_QA_20260924` emulator after Firebase configuration: 2 tests, 0 failures, 0 errors. It verifies email sign-in screen/address validation, and that the cached optimistic completion and stable idempotency key survive a Room database close/reopen before successful queue removal.
- Current Firebase-configured debug APK: `android/app/build/outputs/apk/debug/app-debug.apk` (20,442,275 bytes; SHA-256 `DD79488966C65E0D31CFCF5B5CF9110860C763E4C7FDC37564F389E8B60B3849`).
- Android lint still reports 10 non-blocking warnings, mainly newer suggested dependency versions, target SDK 36, deprecated Android/Firebase calls, backup rules, and the launcher icon's missing monochrome layer.
- Deno recurrence tests passed 5/5, and the documented `deno check --config supabase/deno.json` command passed for all four Edge Functions.
- The clean QA emulator displayed sign-in in light and dark mode, including 360 dp width with 1.3 font scaling. Signed-in screens and the widget data flow remain unverified on a device.
- Consent-site lint/build and the Vercel production deployment passed earlier in this work; the consent site has not changed since.
- Live hosted checks: Supabase project is `ACTIVE_HEALTHY`; all four Edge Functions are active; the recurrence and reminder cron jobs are enabled. Unauthenticated MCP `initialize` returns 401, the protected-resource metadata returns 200, and the Auth OAuth-server metadata returns 200. The database currently has 0 auth users and 0 registered push devices.
- The Supabase URL and publishable key in both ignored local configuration files (`android/local.properties` and `web/consent/.env.local`) match the selected hosted project. Values were compared without printing the key.
- The Vercel deployment URL is documented from an earlier successful deployment, but current live verification is blocked: the Vercel connector shows no project for its linked team, and the local HTTPS client reports `UntrustedRoot`. Certificate validation was not bypassed.
- Firebase CLI is authenticated and exposes one project, `gdg-web-dev`; Android package `dev.system.app` is registered and the 1,032-byte `google-services.json` validates against its project/app IDs. It is git-ignored. The local Firebase CLI sometimes exits with a Windows Node assertion after completing a request; configuration output was validated independently.
- The Firebase service-account email/private key are not configured in Supabase, and no Android user/device has signed in to register an FCM token. The ChatGPT OAuth account link is also not yet complete.
- Vercel’s connected project lookup returned 403 for `system-consent`; local `vercel whoami` still fails to fetch its API. The Brave browser connector reported it could not load browser request-header policy, so no browser session could be inspected.
