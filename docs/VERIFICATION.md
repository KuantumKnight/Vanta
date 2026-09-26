# Verification checklist

## Current results — 26 September 2026

| Check | Result | Evidence / next step |
|---|---|---|
| Consent-site lint/build | Passed locally and in CI | `npm run lint` and `npm run build` pass with the code request and verification routes. Windows used Next's WASM compiler fallback. CI run `36252566677` passed. The Supabase Management API rejects hosted template edits for this free project with the default email provider; code entry requires custom SMTP or an eligible plan, and link fallback remains available meanwhile. |
| Supabase recurrence tests/type checks | Passed locally and in CI | CI run `36252566677`; five recurrence tests and all four Edge Function checks passed. |
| Firebase-configured CI APK | Passed | CI run `36252566677` decoded `SYSTEM_GOOGLE_SERVICES_JSON_B64` and assembled the debug APK. |
| Firebase-configured Android build | Passed locally | `assembleDebug` ran `processDebugGoogleServices`; installable APK at `artifacts/SYSTEM-debug.apk` (20,442,275 bytes), SHA-256 `AB8DE57232ADD51F37AF6893B6781CB12B9122D3AA46E9C598CFA9ADC322B7FB`. |
| Android lint | Passed | `:app:lintDebug` completed with no lint errors. |
| Android instrumentation | Passed | `SYSTEM_API_36_PLAY`: 4 tests, 0 failures; sign-in validation, native deep-link registration, offline queue persistence, and widget feed date grouping. |
| Android cold launch | Passed | Final APK launched to the email sign-in screen; fresh logcat had no ANR or fatal exception. |
| Supabase Android redirect | Passed | Hosted redirect allowlist now contains `dev.system.app://auth`; CLI config diff confirms the URI is live. |
| Android email sign-in | Code and link handling implemented; code-template configuration pending | The app verifies the live project's 8-digit email code and retains the native link callback. Supabase rejected template changes on this free project with its default email provider. Configure custom SMTP or upgrade, update both templates to use `{{ .Token }}`, then verify from the owner inbox. |
| Anonymous database access | Passed | Anonymous task insert returned 401; SQL privilege checks show `anon` cannot insert tasks or execute `mcp_create_task`. |
| Live MCP-to-widget flow | Not verified | ChatGPT owner OAuth and owner email-code sign-in are not complete. There are 0 persisted tasks and 0 registered devices. |
| Consent production deploy | Not verified | Vercel connector lists 0 projects and cannot access the documented deployment; the Brave control bridge is unavailable and the local CLI's TLS chain is untrusted. |
| FCM server credentials | Passed | The dedicated service account has only `cloudmessaging.messages.create`; its credentials are Supabase Edge Function secrets, and a short-lived Firebase Messaging OAuth token was minted successfully. The local private-key file was removed. |
| FCM delivery to Android | Pending | Sign in on the Android phone to register a device token, then verify a closed-app refresh and periodic fallback. |
| ChatGPT owner OAuth | Not verified | Developer mode account linking is still pending. |

## Automated checks

- Consent app: `cd web/consent; npm run lint; npm run build`.
- Edge Function rule tests: `deno test --config supabase/deno.json supabase/functions/_shared/recurrence_test.ts`.
- Edge Function type checks: `deno check --config supabase/deno.json supabase/functions/mcp/index.ts supabase/functions/expand-schedules/index.ts supabase/functions/sync-push/index.ts supabase/functions/send-reminders/index.ts`.
- Android: `cd android; .\gradlew.bat assembleDebug` on Windows, or `./gradlew assembleDebug` on macOS/Linux with JDK 17 and Android SDK 37 installed.

## Backend and device acceptance

1. **Not run:** Sign in as the account owner through ChatGPT OAuth, call `get_context`, then create “50 push-ups” for its returned `local_date + 1`; verify exactly one task and occurrence in Asia/Kolkata.
2. **Partially verified:** On 26 September, unauthenticated MCP, `sync-push`, and `send-reminders` requests each returned 401. An anonymous task insert returned 401; SQL ACL checks show `anon` cannot insert tasks or execute `mcp_create_task`. Rolled-back SQL tests confirmed an alternate JWT subject cannot read the owner's task and cannot update or complete the owner's tasks, goals, habit schedule, or goal links. The probes used a synthetic second Auth identity and left no rows behind.
3. **Partially verified:** A rolled-back transaction called `mcp_create_task` twice with one operation ID and identical arguments, confirmed the same result and one occurrence, then confirmed the same ID with changed arguments is rejected. The full ChatGPT transport path remains untested.
4. **Partially verified:** Room persistence of the offline completion/idempotency key passes instrumentation. Server replay, deduplication, and queue removal after a real successful response remain unverified.
5. **Partially verified:** Deno tests cover recurrence exceptions and Asia/Kolkata wall-time expansion. A rolled-back live DB test confirmed future schedule edits retire pending future occurrences and preserve completed history. End-to-end schedule materialization remains unverified.
6. **Partially verified:** Deno tests cover previous-day 9 AM defaults and late catch-up. A rolled-back live DB test confirmed reminder claims deduplicate before and after marking a send complete. Actual FCM delivery remains unverified.
7. **Partially verified:** FCM credentials can mint an OAuth token. Closed-app refresh and periodic fallback remain untested because no Android device has registered an FCM token.
8. **Not run:** Widget completion while offline, reconnect replay, and retry UI for a rejected server response.

The repository excludes Supabase and Firebase credentials. Use the hosted Supabase project for backend acceptance; Docker is only required if you choose to run a local Supabase stack. The Firebase app and ignored `google-services.json` are configured, with the config available to CI through the GitHub secret `SYSTEM_GOOGLE_SERVICES_JSON_B64`. The FCM service-account key is held only in Supabase secrets. Closed-app push acceptance still needs a signed-in device with a registered token.
