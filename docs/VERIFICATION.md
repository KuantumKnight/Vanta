# Verification

## Historical hardening follow-up status — 2026-10-21

This page retains earlier verification notes. Its first migration-reconciliation draft incorrectly said the deployed project had ten migrations and that the four `20261021090000`–`20261021090200` migrations were absent. The later read-only history check corrected that finding: 14 migrations are applied through `20261021100000_system_device_token_lifecycle`; the four 09:00–09:02 source files were restored from the recovered Git baseline, and the eight later Batch 3/4 migrations remain pending. The current, authoritative decision and evidence are in [VANTA2_PRE_BATCH5_GATE.md](VANTA2_PRE_BATCH5_GATE.md) and [VANTA2_MIGRATION_AUDIT.md](VANTA2_MIGRATION_AUDIT.md).

The original supplied workspace had no `.git` metadata. Read-only recovery found a clean sibling checkout at `b68662fcec8c6ccbb613415e46290ad083eb55b8`; its metadata was copied without replacing source files. The workspace now has branch `vanta2-batch3-batch4-integration` and a Batch 3/4 integration checkpoint. The release-gate audit has not deployed migrations or changed remote schema/data.

### Security review

The new completion RPC checks `auth.uid()`, verifies occurrence ownership, has a fixed `search_path`, and is executable only by `authenticated`. Direct occurrence writes are revoked. Materialization uses the Edge Function's server-only client after it reads the caller's canonical owned schedule; Android and web source scans found no service-role key or private-key material. The production Supabase Security Advisor run was read-only and preceded these unapplied migrations: it reported 11 `SECURITY DEFINER` findings on the existing owner-checked MCP/completion/token routines, plus disabled leaked-password protection. Those advisor results do not validate the two new local migrations.

### Offline replay and schedule edit semantics

Completion entries keep one idempotency key across retries. Success and `already_applied` remove the entry. Retryable failures keep it queued with bounded backoff and allow later independent entries to replay. Authentication failures keep the entry and stop the pass until auth recovers. Permanent failures and conflicts remain visible as `dead_letter` or `conflict` until the user explicitly dismisses them. A schedule edit keeps completed history and marks removed future pending occurrences `skipped`; a queued completion for one of those rows receives `NOT_FOUND` and becomes a visible dead letter instead of retrying forever.

The last authenticated user ID is stored separately from remote auth availability. A refresh failure keeps that account's Room cache visible. When the remote authenticated user changes, repository flows and widget reads hide the saved account until the matching local account context is active. Explicit sign-out checks unresolved queue entries and disables registered FCM tokens before clearing the local identity; it can be blocked until connectivity/authentication returns.

### Release-gate checks

- Deno shared regression suite: **34 passed, 0 failed**. The three changed Edge Function entry points type-check, and changed-file formatting passed.
- Android Gradle: **blocked before project configuration**. Both installed Temurin 17 JDKs pass TCP loopback but fail `Selector.open()` with `java.net.SocketException: Invalid argument: connect`; see [ANDROID_GRADLE_ENV_BLOCKER.md](ANDROID_GRADLE_ENV_BLOCKER.md).
- Supabase disposable migration/pgTAP suite: **blocked**. Docker's `desktop-linux` engine is unavailable, local Supabase at port 54322 refuses connections, and no dev branch exists. No test or migration was run against production.
- Read-only remote metadata confirms the 14/8 migration split. Data API exposure could not be established because `pgrst.db_schemas` returned null. See the release-gate report for exact status by gate.

The release decision is **NOT READY FOR BATCH 5**. No production migrations, functions, or project settings were deployed.

## Historical automated checks

Run from a clean checkout with JDK 17, Android SDK 37, Node 22+, Deno 2, and a booted Android emulator or connected device:

```powershell
cd web/consent
npm ci
npm run lint
npm run build

cd ..\..
deno test --config supabase/deno.json supabase/functions/_shared/recurrence_test.ts supabase/functions/_shared/materialize_test.ts supabase/functions/_shared/mcp_validation_test.ts supabase/functions/_shared/mcp_pagination_test.ts
deno check --config supabase/deno.json supabase/functions/mcp/index.ts supabase/functions/expand-schedules/index.ts supabase/functions/sync-push/index.ts supabase/functions/send-reminders/index.ts

npx supabase start -x studio,inbucket,imgproxy
npx supabase db reset --local
npx supabase test db

cd android
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest
```

CI runs the consent lint/build, Deno tests/type checks, Android debug build/lint/test compilation, and Android instrumentation tests without production secrets. CI has been authored but has not run on GitHub from this workspace.

## Earlier baseline results verified on 2026-10-21

The following build/device results predate the hardening follow-up above and do not verify the newly changed Android or SQL code.

- Consent site `npm run lint` and `npm run build` passed.
- Deno recurrence suite passed 6 tests; `deno check` passed for all four Edge Function entry points.
- Android `lintDebug --rerun-tasks` passed; `testDebugUnitTest assembleDebug assembleDebugAndroidTest` passed; `connectedDebugAndroidTest` passed all 7 instrumentation tests on the clean API 36 emulator (2 sign-in, 3 Room/offline-queue, 2 widget-feed tests). The widget-feed fixture was corrected after its first run exposed an off-by-one in the test data. The debug APK installed and `MainActivity` launched on the emulator.
- Android backup extraction rules now exclude app files, Room, and shared preferences from both cloud backup and device transfer; the final combined Gradle verification passed with those rules enabled.
- The follow-up adds JVM tests for replay error classification, offline account visibility, and a refresh arriving during an active sync; database persistence and migration tests run as Android instrumentation tests. The new Android tests remain unverified in this environment because Gradle failed before compilation.
- `npx supabase migration list --project-ref <project-ref>` showed every local migration through `20261021100000` matched remotely.
- `npx supabase db query --linked --project-ref <project-ref> --file supabase/acceptance_rollback.sql --output-format json` passed the rollback-only tests for create/replay, changed-payload rejection, MCP and Android completion replay, cross-user RLS isolation, idempotent token registration, and anonymous RPC denial. The SQL transaction ended with `ROLLBACK`.
- The new `20261021100000_system_device_token_lifecycle.sql` migration was applied remotely, and the updated `mcp` Edge Function is active at version 5. An unauthenticated request to the deployed MCP endpoint returned HTTP 401. To repeat the check:

  ```powershell
  $response = Invoke-WebRequest -Uri "https://<project-ref>.supabase.co/functions/v1/mcp" -Method Post -ContentType "application/json" -Body '{"jsonrpc":"2.0","method":"tools/list","id":1}' -SkipHttpErrorCheck
  $response.StatusCode
  ```

- Vercel showed the production consent deployment `https://system-consent.vercel.app` as Ready on 2026-10-21. The GitHub deploy workflow still needs the `VERCEL_TOKEN` repository secret.
- Follow-up count queries found zero rollback-test task and device-token rows; no production task, occurrence, or device-token rows were changed by acceptance SQL.

## End-to-end device acceptance

Do this after installing the debug build on the Android phone:

1. Sign in on Android with the Supabase account used in ChatGPT. Open the email link on that same phone. Enable reminders so Android can grant notification permission and the app can register its current FCM token.
2. Add the deployed MCP endpoint to ChatGPT Developer mode and complete OAuth as the same Supabase user.
3. Close Vanta. Ask ChatGPT to create `50 push-ups tomorrow`. Verify `list_tasks` returns one pending task and occurrence.
4. Without launching Vanta, wait for push refresh. Verify the home widget shows `UPCOMING` and `Tomorrow · 50 push-ups`. If FCM is delayed, confirm WorkManager repairs the cache within its platform interval.
5. Tap the widget's Done action. Verify it disappears immediately, then ask ChatGPT to list pending tasks and confirm it is completed/absent.
6. For offline recovery, disconnect the phone, complete another task from the widget, force-stop/reopen the app after reconnecting, and verify the queued mutation clears after Supabase confirms completion.
7. Resize the widget to compact and expanded sizes and confirm its sections remain readable and do not clip.

The hosted database acceptance check covers the authenticated RPC and RLS boundaries without leaving data behind. The phone, mailbox, notification delivery, ChatGPT consent, and visual widget sizing require user-controlled sessions and are not claimed verified until the end-to-end run is completed.
