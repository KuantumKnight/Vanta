# Vanta 2 deployment runbook

Release-gate audit: 2026-10-21. This is an operator checklist; no migrations, functions, OAuth clients, or secrets were deployed during this audit.

## Current release state

- The connected Supabase project's read-only migration history contains 14 applied migrations through `20261021100000_system_device_token_lifecycle`.
- Eight later migrations are pending in this workspace. They have not passed disposable-database application or pgTAP checks yet. Do not use the production project for that validation.
- The remote read-only query found no `public.daily_reviews` table because its pending migration has not been applied. `pgrst.db_schemas` returned null, so the target project's Data API exposure could not be established. Confirm `public` explicitly in the Supabase API settings before exposing the table.
- The repository has no Android build/test verification yet. Resolve the host Java selector/loopback issue and test on an emulator or device before calling this a release candidate.

## Release sequence

1. Run all repository SQL/pgTAP tests and apply the pending chain to a disposable PostgreSQL/Supabase database starting at the 14-migration baseline. Verify grants, RLS, owner isolation, RPC execution, indexes, and that reapplication behaves as expected. This gate is currently blocked because Docker/PostgreSQL is unavailable.
2. Review the exact migration diff. The two source changes in the 17:00 daily-review migration (explicit `PUBLIC` revoke and pgTAP privilege assertions) are in a migration confirmed pending; already-applied migrations must remain unchanged.
3. In the target project's API settings, explicitly expose the intended `public` schema. Verify the resulting PostgREST behavior for `daily_reviews` after migration: authenticated owner reads allowed under RLS, direct writes denied, anonymous reads denied, and the owner-derived save RPC available only to authenticated users.
4. Apply the eight pending migrations in timestamp order:

   1. `20261021110000_system_occurrence_write_guard_and_completion_outcomes.sql`
   2. `20261021120000_system_statement_batched_refresh.sql`
   3. `20261021130000_vanta2_task_duration.sql`
   4. `20261021170000_vanta2_daily_reviews.sql`
   5. `20261021180000_vanta2_scheduling_semantics.sql`
   6. `20261021185000_vanta2_delta_sync.sql`
   7. `20261021185500_vanta2_reschedule_occurrence.sql`
   8. `20261021190000_vanta2_task_reminder_preference.sql`

5. Confirm migration history reaches `20261021190000`, review PostgREST grants/RLS against the release tests, then deploy the Edge Functions listed below. The local database migration must precede functions that reference the added columns and RPCs.
6. Provision only the secrets for enabled server features, then perform a signed Android build and device/emulator smoke tests. Google Calendar and the AI planner are optional integrations; core schedule use must work without them.

The dated Supabase changelog describes the upcoming default-exposure change for new tables beginning 2026-10-30. Keep the API schema exposure deliberate and verify it in the target project rather than relying on a default: [Supabase changelog](https://supabase.com/changelog/45329-breaking-change-tables-not-exposed-to-data-and-graphql-api-automatically).

## Edge Functions and secrets

The Batch 3/4 workspace changed or added these function sources; deploy only after the migration and disposable validation gates pass:

| Function | Release action | Configuration |
|---|---|---|
| `mcp` | Deploy updated source | Supabase server URL and server key are required by the admin client. Preserve its internal authorization checks and verify the configured gateway/JWT mode. |
| `expand-schedules` | Deploy updated source after scheduling migrations | Requires the internal-call secret and server credentials. Trigger it only through the authorized internal path. |
| `send-reminders` | Deploy updated source after reminder migrations | Requires internal-call secret, server credentials, and FCM credentials if push delivery is enabled. |
| `plan-day` | New optional authenticated endpoint; deploy only when AI planning is enabled | Requires `OPENAI_API_KEY` and `OPENAI_MODEL`; the user-scoped Supabase session is used to reload canonical occurrences. Missing either AI setting returns a generic 503 for this feature only. |
| `sync-push` | No source change in this checkpoint; keep the deployed version compatible with the shared internal secret | Requires the internal-call secret and server credentials. No new deployment is indicated by this source diff alone. |

Supabase supplies platform-managed runtime values such as `SUPABASE_URL` and supports server-side keys. The repository's shared admin helper reads `SUPABASE_SECRET_KEYS` (a JSON object whose `default` key contains the server key) or the legacy `SUPABASE_SERVICE_ROLE_KEY` fallback. Prefer the supported server-side secret mechanism configured for the project; never send a server key to Android.

- `INTERNAL_FUNCTION_SECRET`: server-only shared value for authenticated internal function calls (`sync-push`, `expand-schedules`, and `send-reminders`).
- `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FCM_PRIVATE_KEY`: server-only; needed only for Firebase Cloud Messaging delivery.
- `OPENAI_API_KEY`, `OPENAI_MODEL`: server-only; both are needed only for Plan My Day. They are not Android build variables.
- `SUPABASE_PUBLISHABLE_KEY`: client publishable key used by Android with `SUPABASE_URL`; it is not a service-role credential.

Set secrets through the Supabase project/function secret manager or CLI in the deployment environment. Do not put values in this repository, Android resources, BuildConfig, APKs, or `.env.example`. The root `.env.example` documents names only.

## Android build configuration

The Android Gradle build reads `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` from ignored `android/local.properties` to generate public client configuration. The root example file is documentation only. OpenAI and FCM service credentials do not belong in `local.properties` or the APK.

Google Calendar is configured outside environment files: create an Android OAuth client for application ID `dev.system.app`, register the signing certificate SHA-1 for each distribution channel (including the Play app-signing certificate), configure the consent screen and exact read scopes, and complete applicable verification. This implementation uses Google Play services `AuthorizationClient`; it does not use a Calendar redirect URI. See [GOOGLE_CALENDAR_SETUP.md](GOOGLE_CALENDAR_SETUP.md).

## Gate before production rollout

- Disposable migration + pgTAP acceptance passes from the deployed baseline.
- All public Data API objects have explicit grants, RLS policies, and ownership tests; private/internal tables and functions are not exposed to client roles.
- The target `public` API exposure is confirmed in project settings and verified with an authenticated client after migrations.
- Android compile, unit tests, lint, debug/release builds, and device/emulator scenarios pass.
- Google OAuth client, consent/verification, signing fingerprints, connect/disconnect, and offline fallback are verified on signed builds.
- If AI is enabled, `OPENAI_API_KEY` and `OPENAI_MODEL` are present only as server-side function secrets; missing-secret behavior is graceful.

Until those checks pass, keep this release **NOT READY** and do not begin Batch 5.
