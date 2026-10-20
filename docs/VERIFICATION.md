# Verification checklist

## Source audit changes — 21 October 2026

The four repository audit migrations dated `20261021090000` through `20261021090200` are not deployed to the hosted Supabase project. The separate `20261021100000_system_device_token_lifecycle.sql` migration was applied and verified afterward. Apply the four audit migrations in order before treating their backend checks as passed.

- Verify authenticated clients cannot insert task occurrences directly, while `complete_task_occurrence` still succeeds with an owner token.
- Verify sign-out disables the registered token, another account cannot claim an active token, and a disabled token can be reused after an account switch.
- Verify the `system-consent` bucket is private and the anonymous `probe.html` object is absent.
- Verify task and occurrence mutations still commit when the Vault refresh secret is absent, and a multi-row insert queues at most one refresh per owner for the statement.
- Verify Android cache queries return only the active user, paginate beyond 1,000 pending rows, preserve the queued optimistic row during an atomic snapshot, and replay only that user's queue.
- Verify `list_tasks` pages with `offset`, and `include_completed=true` omits skipped habit occurrences.
- Verify the Vercel production workflow skips dispatches from branches other than `main`.

## Audit verification — 21 October 2026

| Check | Result | Evidence / next step |
|---|---|---|
| Android build and instrumentation | Passed | Initial audit run: `connectedDebugAndroidTest --rerun-tasks` passed 7 tests on `SYSTEM_CI_QA_FINAL` API 36, covering sign-in validation/deep links, Room user isolation and snapshot replacement, v1→v3 migration, task projection, and widget feed grouping. The v1→v4 migration and duplicate-queue test passed in the follow-up run below. |
| Consent app lint/build | Passed | `npm run lint` and `npm run build` passed after the source audit changes. |
| Edge Function tests/type checks | Passed | Initial audit run: five recurrence tests and `deno check` for MCP, schedule expansion, push refresh, and reminders passed. The later date-validation test and full type-check rerun are recorded below. |
| SQL migration execution | Not run during initial audit | Docker Desktop's Linux engine was unavailable. The later remote device-token migration and rollback acceptance results are recorded below; the four `20261021090000`–`20261021090200` audit migrations remain pending. |
| Hosted migration and core-flow acceptance | Pending during initial audit | ChatGPT OAuth, owner Android sign-in, FCM refresh, and closed-app widget convergence still require the owner account and physical phone. |

## Follow-up verification — 21 October 2026

| Check | Result | Evidence |
|---|---|---|
| Android build, lint, and instrumentation | Passed | `cd android; .\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest` passed on `SYSTEM_CI_QA_FINAL` API 36. Eight instrumentation tests passed, including the v1→v4 Room migration, duplicate queue prevention, user isolation, task projection, sign-in, and widget feed. |
| Consent site | Passed | `npm run lint` and `npm run build` completed successfully in `web/consent`. |
| Edge Function recurrence tests | Passed | Six tests passed, including invalid date rejection and ISO reminder timestamp validation. |
| Edge Function type checks | Passed | `deno check --config supabase/deno.json` passed for MCP, schedule expansion, push refresh, and reminders. |
| Hosted migration history | Matched for applied migrations | `npx --yes supabase migration list --linked --project-ref zpxcilhmbbhowqceyafj` shows the local and remote `20261017045929` and `20261021100000` versions match. The four `20261021090000`–`20261021090200` repository audit migrations remain unapplied. |
| Rollback acceptance | Passed | `npx --yes supabase db query --linked --project-ref zpxcilhmbbhowqceyafj --file supabase/acceptance_rollback.sql` passed idempotent MCP create/complete, changed-payload rejection, owner isolation, device-token registration, active-token takeover rejection, and anonymous-grant checks; the transaction rolled back. |
| Live MCP authentication | Passed | The deployed MCP function is version 5; an unauthenticated request returned HTTP 401. |
| Primary closed-app phone flow | Pending | Physical Android sign-in, ChatGPT owner OAuth, FCM delivery while Vanta is closed, widget completion, and server replay still need owner account and device interaction. |

The Android build emitted existing deprecation warnings for system bar properties, Firebase token access, and the messaging callback. They did not fail compilation.

## Current results — 26 September 2026

| Check | Result | Evidence / next step |
|---|---|---|
| Consent-site lint/build | Passed locally and in CI | `npm run lint` and `npm run build` pass with code/email-link handling. Windows used Next's WASM compiler fallback. CI run `36255318789` passed. Supabase rejects hosted template edits for this free project with the default email provider; email-link fallback works while OTP templates await custom SMTP or an eligible plan. |
| Supabase recurrence tests/type checks | Passed locally and in CI | CI run `36255318789`; five recurrence tests and all four Edge Function checks passed. |
| Firebase-configured CI APK | Passed | CI run `36255318789` decoded `SYSTEM_GOOGLE_SERVICES_JSON_B64` and assembled the debug APK. |
| Firebase-configured Android build | Passed in CI and installed on emulator | `artifacts/SYSTEM-debug.apk` (19,504,524 bytes), SHA-256 `9DB9195BA8230BCE451512308AC8FF52BB7C61FE8CE1FBEF25F642F5734647A5`. It cold-launched to the updated sign-in screen. |
| Android lint | Passed | `:app:lintDebug` completed with no lint errors. |
| Android instrumentation | Passed | `SYSTEM_API_36_PLAY`: 4 tests, 0 failures; sign-in validation, native deep-link registration, offline queue persistence, and widget feed date grouping. |
| Android cold launch | Passed | Latest CI APK launched to the email sign-in screen; fresh logcat had no ANR or fatal exception. Screenshot: `artifacts/system-signin-final.png`. |
| Supabase Android redirect | Passed | Hosted redirect allowlist now contains `dev.system.app://auth`; CLI config diff confirms the URI is live. |
| Android email sign-in | Code and link handling implemented; code-template configuration pending | The app verifies the live project's 8-digit email code and retains the native link callback. Supabase rejected template changes on this free project with its default email provider. Configure custom SMTP or upgrade, update both templates to use `{{ .Token }}`, then verify from the owner inbox. |
| Anonymous database access | Passed | Anonymous task insert returned 401; SQL privilege checks show `anon` cannot insert tasks or execute `mcp_create_task`. |
| Live MCP-to-widget flow | Not verified | ChatGPT owner OAuth and owner email-code sign-in are not complete. There are 0 persisted tasks and 0 registered devices. |
| Consent production deploy | Workflow ready; Vercel token required | The linked `system-consent` project/team IDs are stored as GitHub Actions variables and `.github/workflows/deploy-consent.yml` is available for a manual production deploy. Add `VERCEL_TOKEN` as a repository Actions secret, then run the workflow. The Vercel connector cannot read the project (403/schema mismatch), and the local CLI cannot reach Vercel. |
| FCM server credentials | Passed | The dedicated service account has only `cloudmessaging.messages.create`; its credentials are Supabase Edge Function secrets, and a short-lived Firebase Messaging OAuth token was minted successfully. The local private-key file was removed. |
| FCM delivery to Android | Pending | Sign in on the Android phone to register a device token, then verify a closed-app refresh and periodic fallback. |
| ChatGPT owner OAuth | Not verified | Developer mode account linking is still pending. |

## Automated checks

- Consent app: `cd web/consent; npm run lint; npm run build`.
- Edge Function rule tests: `deno test --config supabase/deno.json supabase/functions/_shared/recurrence_test.ts`.
- Edge Function type checks: `deno check --config supabase/deno.json supabase/functions/mcp/index.ts supabase/functions/expand-schedules/index.ts supabase/functions/sync-push/index.ts supabase/functions/send-reminders/index.ts`.
- Android: `cd android; .\gradlew.bat assembleDebug` on Windows, or `./gradlew assembleDebug` on macOS/Linux with JDK 17 and Android SDK 37.0 installed.

## Backend and device acceptance

1. **Not run:** Sign in as the account owner through ChatGPT OAuth, call `get_context`, then create “50 push-ups” for its returned `local_date + 1`; verify exactly one task and occurrence in Asia/Kolkata.
2. **Partially verified:** On 26 September, unauthenticated MCP, `sync-push`, and `send-reminders` requests each returned 401. An anonymous task insert returned 401; SQL ACL checks show `anon` cannot insert tasks or execute `mcp_create_task`. Rolled-back SQL tests confirmed an alternate JWT subject cannot read the owner's task and cannot update or complete the owner's tasks, goals, habit schedule, or goal links. The new occurrence-insert denial and device-token transfer migration still need hosted verification.
3. **Partially verified:** A rolled-back transaction called `mcp_create_task` twice with one operation ID and identical arguments, confirmed the same result and one occurrence, then confirmed the same ID with changed arguments is rejected. The full ChatGPT transport path remains untested.
4. **Partially verified:** Room persistence of the offline completion/idempotency key passes instrumentation. Server replay, deduplication, and queue removal after a real successful response remain unverified.
5. **Partially verified:** Deno tests cover recurrence exceptions and Asia/Kolkata wall-time expansion. A rolled-back live DB test confirmed future schedule edits retire pending future occurrences and preserve completed history. End-to-end schedule materialization remains unverified.
6. **Partially verified:** Deno tests cover previous-day 9 AM defaults and late catch-up. A rolled-back live DB test confirmed reminder claims deduplicate before and after marking a send complete. Actual FCM delivery remains unverified.
7. **Partially verified:** FCM credentials can mint an OAuth token. Closed-app refresh and periodic fallback remain untested because no Android device has registered an FCM token.
8. **Not run:** Widget completion while offline, reconnect replay, and retry UI for a rejected server response.

The repository excludes Supabase and Firebase credentials. Use the hosted Supabase project for backend acceptance; Docker is only required if you choose to run a local Supabase stack. The Firebase app and ignored `google-services.json` are configured, with the config available to CI through the GitHub secret `SYSTEM_GOOGLE_SERVICES_JSON_B64`. The FCM service-account key is held only in Supabase secrets. Closed-app push acceptance still needs a signed-in device with a registered token.
