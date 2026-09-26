# Verification checklist

## Current results — 26 September 2026

| Check | Result | Evidence / next step |
|---|---|---|
| Consent-site lint/build | Passed | GitHub Actions run `36234395884`, commit `3473244`. |
| Supabase recurrence tests/type checks | Passed | Same run; five recurrence tests and all four Edge Function checks. |
| Firebase-configured CI APK | Passed | Same run decoded `SYSTEM_GOOGLE_SERVICES_JSON_B64` and assembled the debug APK. |
| Firebase-configured Android build | Passed locally | `assembleDebug` ran `processDebugGoogleServices`; APK SHA-256 `F9E70B8091A5A965A887C1A4FD75586A7EE1D75477B4DF1D6E43AD0FA5D04FF7`. |
| Android instrumentation | Passed | `SYSTEM_API_36_PLAY`: 2 tests, 0 failures; sign-in input validation and offline queue persistence. |
| Android cold launch | Passed | After async startup fix, emulator reached sign-in; fresh logcat had no ANR/fatal exception. |
| Anonymous database access | Passed | Anonymous task insert returned 401; SQL privilege checks show `anon` cannot insert tasks or execute `mcp_create_task`. |
| Live MCP-to-widget flow | Not verified | Auth still needs the OTP template update and ChatGPT owner OAuth. There are 0 persisted tasks and 0 registered devices. |
| Consent production deploy | Not verified | Vercel connector cannot list the project (0 projects, deployment API 403); local CLI certificate chain is untrusted. |
| FCM server delivery | Sender role configured; credentials pending | FCM API is enabled and the dedicated service account has only `cloudmessaging.messages.create`; Supabase secrets and a registered device token are still missing. |
| ChatGPT owner OAuth | Not verified | Developer mode account linking is still pending. |

## Automated checks

- Consent app: `cd web/consent; npm run lint; npm run build`.
- Edge Function rule tests: `deno test --config supabase/deno.json supabase/functions/_shared/recurrence_test.ts`.
- Edge Function type checks: `deno check --config supabase/deno.json supabase/functions/mcp/index.ts supabase/functions/expand-schedules/index.ts supabase/functions/sync-push/index.ts supabase/functions/send-reminders/index.ts`.
- Android: `cd android; .\gradlew.bat assembleDebug` on Windows, or `./gradlew assembleDebug` on macOS/Linux with JDK 17 and Android SDK 37 installed.

## Backend and device acceptance

1. **Not run:** Sign in as the account owner through ChatGPT OAuth, call `get_context`, then create “50 push-ups” for its returned `local_date + 1`; verify exactly one task and occurrence in Asia/Kolkata.
2. **Partially verified:** On 26 September, unauthenticated MCP, `sync-push`, and `send-reminders` requests each returned 401. An anonymous task insert returned 401; SQL ACL checks show `anon` cannot insert tasks or execute `mcp_create_task`. A rolled-back SQL transaction confirmed an alternate JWT subject cannot read the owner’s task through RLS. Cross-owner write attempts using a second real Auth account remain untested.
3. **Partially verified:** A rolled-back transaction called `mcp_create_task` twice with one operation ID and identical arguments, confirmed the same result and one occurrence, then confirmed the same ID with changed arguments is rejected. The full ChatGPT transport path remains untested.
4. **Partially verified:** Room persistence of the offline completion/idempotency key passes instrumentation. Server replay, deduplication, and queue removal after a real successful response remain unverified.
5. **Partially verified:** Deno tests cover recurrence exceptions and Asia/Kolkata wall-time expansion. A rolled-back live DB test confirmed future schedule edits retire pending future occurrences and preserve completed history. End-to-end schedule materialization remains unverified.
6. **Partially verified:** Deno tests cover previous-day 9 AM defaults and late catch-up. A rolled-back live DB test confirmed reminder claims deduplicate before and after marking a send complete. Actual FCM delivery remains unverified.
7. **Not run:** Closed-app FCM refresh and periodic fallback. FCM credentials/device registration are still missing.
8. **Not run:** Widget completion while offline, reconnect replay, and retry UI for a rejected server response.

The repository excludes Supabase and Firebase credentials. Use the hosted Supabase project for backend acceptance; Docker is only required if you choose to run a local Supabase stack. The Firebase app and ignored `google-services.json` are configured, with the config available to CI through the GitHub secret `SYSTEM_GOOGLE_SERVICES_JSON_B64`. Closed-app push acceptance still needs FCM service-account credentials and a signed-in device with a registered token.
