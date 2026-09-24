# Verification checklist

## Automated checks

- Consent app: `cd web/consent; npm run lint; npm run build`.
- Edge Function rule tests: `deno test --config supabase/deno.json supabase/functions/_shared/recurrence_test.ts`.
- Edge Function type checks: `deno check --config supabase/deno.json supabase/functions/mcp/index.ts supabase/functions/expand-schedules/index.ts supabase/functions/sync-push/index.ts supabase/functions/send-reminders/index.ts`.
- Android: `cd android; .\gradlew.bat assembleDebug` on Windows, or `./gradlew assembleDebug` on macOS/Linux with JDK 17 and Android SDK 37 installed.

## Backend and device acceptance

1. Sign in as the account owner, then call `get_context` and create “50 push-ups” for its returned `local_date + 1`. Verify one task and one occurrence are created and the date is in Asia/Kolkata.
2. Connect with an unauthenticated MCP request and verify OAuth metadata is returned and tool calls are rejected. Sign in as another Supabase user and verify RLS exposes no rows belonging to the owner. Verify anonymous and authenticated API roles cannot call the internal reminder RPC or mutate task tables directly.
3. Replay each MCP write using the same operation ID and identical arguments; verify the stored response is returned without a second mutation. Reuse that ID with changed arguments or a different tool and verify the request is rejected. A deliberate repeated request must use a new operation ID.
4. Repeat `complete_task` for one occurrence and replay the same Android completion idempotency key. Verify one completed occurrence, no duplicate history, and no retry queue item after success.
5. Create a daily habit with an exception date, verify it is absent on that date, edit its schedule from a future date, and verify completed history stays unchanged.
6. Test a date-only reminder at the previous day’s 9 AM, a late catch-up send, and two consecutive scheduler runs. Verify one notification per device and occurrence.
7. With the app closed, create a task from ChatGPT and verify a delivered FCM refresh updates the Glance widget. Repeat with push disabled and verify the next WorkManager sync repairs the cache; allow at least the platform’s 15-minute periodic minimum.
8. Complete from the widget while offline, reconnect, and verify the dashboard shows a queued failure if replay is rejected, with a Retry action.

The repository excludes Supabase and Firebase credentials. Use the hosted Supabase project for backend acceptance; Docker is only required if you choose to run a local Supabase stack. The closed-app push acceptance test still needs a Firebase app, `google-services.json`, FCM service-account credentials, and a signed-in device with a registered token.
