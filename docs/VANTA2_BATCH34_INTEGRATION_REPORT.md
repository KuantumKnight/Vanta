# Vanta 2 Batch 3 + 4 Integration

Audited and stabilized the supplied Batch 3 + 4 workspace on 2026-10-21. This report describes the source tree and checks that actually ran. No new Supabase migration, Edge Function, or Google OAuth credential was deployed during this audit.

## Repository Recovery

- The `system` workspace and its E: ancestors had no Git metadata. The `.git` under the user profile belonged to an unrelated empty repository and was not used.
- Recovered metadata from the clean sibling checkout at `E:\Career_Development\Projects\KuantumKnight\Vanta`. Remote: `https://github.com/KuantumKnight/Vanta.git`. `origin/main` matched `b68662fcec8c6ccbb613415e46290ad083eb55b8` at verification. The copied repository is non-shallow and `git fsck --connectivity-only` completed successfully.
- Created branch `vanta2-batch3-batch4-integration` from that baseline. Copied only `.git`; the current source files were not replaced. No reset or clean command was used.
- Before the integration checkpoint was staged, the recovered tree showed 50 modified, 16 missing tracked, and 73 untracked paths. The initial source-only safety snapshot is at `E:\Career_Development\Projects\system-safety-snapshot-2026-10-21` (477 files, about 261 MB). It excludes generated build/cache directories and local secret/config files.
- Local `local.properties`, `android/app/google-services.json`, `supabase/.env`, and `web/consent/.env.local` were checked by filename only and are ignored. `.gitignore` now also covers root and nested developer `.env` files while leaving `.env.example` visible. The recovered baseline tracks `apk/vanta-debug.apk`; its absence in the supplied workspace remains a local deletion and was excluded from the checkpoint.

## Batch 3 Status

**Implemented by source inspection; Android execution remains unverified.** The current tree contains Calendar read-only sync/cache, HARD/SOFT/FLEXIBLE scheduling, server- and client-validated Plan My Day proposals, conflict-aware rescheduling, and a bounded weekly planner. Calendar remains a separate owner-scoped cache and its blocks are optional inputs to planner projection.

The app requests only Google Calendar event-read and calendar-list-read scopes. OAuth access tokens are held in memory, are not sent to MCP or the planner, and are not stored in Room. Calendar refresh and disconnect operate on the active Vanta owner.

## Batch 4 Status

**Implemented by source inspection; Android execution remains unverified.** The current tree contains habits and occurrence-derived streaks, morning briefing, daily review with owner-scoped notes, planned-versus-actual analytics, typed preferences, and reminder scheduling.

Habit and task history reuse occurrence records. Analytics reads the existing Focus Mode history rather than maintaining a second focus source. Habit and analytics reads are bounded. Morning priority counts use `CachedOccurrence.priority` when available and omit the count for legacy/null priority data.

## Shared Architecture

[`VANTA2_ARCHITECTURE_CONTRACT.md`](VANTA2_ARCHITECTURE_CONTRACT.md) records actual code interfaces, ownership, source of truth, offline behavior, sync rules, invariants, and mutation authority for tasks, occurrences, habits, FocusSession, PendingMutation, scheduling models, briefing, reviews, analytics, preferences, delta checkpoints, and widget state.

Integration checks found one shared typed preferences API feeding planning hours, week start, snap interval, and Quick Capture duration. Review rescheduling updates placement while retaining duration, priority, deadline, and placement windows; a moved FLEXIBLE item becomes SOFT, while a manually moved HARD item remains HARD. HARD blocks remain unavailable to drag and automatic proposals. Habits project through the same occurrence stream as planner views. Calendar is optional, and the morning briefing does not depend on it.

## Cross-Batch Fixes

- Deferred Calendar repository and Google authorization client construction until an authenticated owner/action is available. Signed-in Today and Week still load cached Calendar blocks; ordinary startup does not create the Google Identity client.
- Deferred AI `HttpClient` construction until the user requests Plan My Day, and close it on root disposal if initialized. Account-specific goals and Focus UI state now reset when the owner changes; Dashboard task/goal filtering is memoized by source lists and query.
- Repaired Room migration test builders to register both `MIGRATION_6_7` and `MIGRATION_7_8`; added coverage from the oldest version and Batch 2 version 6.
- Reordered pending Supabase migration filenames to satisfy SQL dependencies. Delta sync follows scheduling semantics; reschedule follows delta sync. The renamed SQL bodies are byte-for-byte unchanged.
- Formatted the 16 changed Batch 3/4 Deno source and documentation files checked below.
- Expanded `.gitignore` for developer secrets and the nested Supabase CLI temp directory. Local secret files were not read or staged.

## Room Migration Chain

Room is version 8 with a registered, contiguous path from 1→2 through 7→8. Source inspection found entity columns, defaults, tables, and indexes consistent with the migrations. Batch 4 remains at 6→7, and Batch 3 scheduling data is added at 7→8.

`SystemDatabaseMigrationTest` covers version 1→8, legacy version 3 and 4 paths, a version 5 path, and the Batch 2 version-6 path through 6→7→8. These tests include preservation checks for cached rows, completion/mutation queues, scheduling defaults, and Batch 4 tables. They were authored but could not run. The migration audit status is **SAFE WITH FIXES by source review; runtime not yet verified**.

## Supabase Migration Chain

All 22 migrations were enumerated in timestamp order in [`VANTA2_MIGRATION_AUDIT.md`](VANTA2_MIGRATION_AUDIT.md). Read-only Supabase history confirms the project's applied history contains 14 migrations through `20261021100000_system_device_token_lifecycle`; the eight later migrations in this workspace are pending. Four applied migration sources missing from the workspace were restored from the recovered Git baseline. The comment-only edit to already-applied `20260926145917_secure_mcp_goal_link_ownership.sql` was removed because it was absent from the recorded SQL. The local `20261021100000_system_device_token_lifecycle.sql` change matches the three statements recorded for that applied migration and is retained as a source reconciliation; it is not a new migration and will not be reapplied. Read-only catalog inspection confirms the deployed token-registration function has an empty `search_path` and no `PUBLIC`/`anon` execute grant. The pending daily-review migration now explicitly revokes `PUBLIC` access and the pgTAP file asserts its direct table privileges. Audit found that delta sync reads scheduling columns created later by the scheduling migration, and reschedule uses the sync-state table/helper created by delta sync. The pending filenames now order them as:

1. `20261021180000_vanta2_scheduling_semantics.sql`
2. `20261021185000_vanta2_delta_sync.sql`
3. `20261021185500_vanta2_reschedule_occurrence.sql`

Static inspection found owner-scoped RLS/RPC checks, minimal grants, and controlled `search_path` values for reviewed definer functions. The local Supabase CLI is available through the repository (`npx --no-install supabase --version` → `2.117.0`). The Docker CLI exists, but the active `desktop-linux` context cannot connect to `npipe:////./pipe/dockerDesktopLinuxEngine`; local SQL/pgTAP acceptance did not run. Remote checks were read-only and limited to migration history, stored migration statements, function definitions/ACLs, the `pgrst.db_schemas` setting, and `public.daily_reviews` catalog presence; no application data, migrations, or deployments were touched, so Supabase remains **NOT YET VERIFIED**.

Android reads `public.daily_reviews` through PostgREST. The read-only `pgrst.db_schemas` query returned null, so the target project's Data API exposure could not be established; confirm that `public` is exposed before deployment. Supabase announced a rollout beginning 2026-10-30 that changes the default exposure of new tables. [Supabase changelog](https://supabase.com/changelog/45329-breaking-change-tables-not-exposed-to-data-and-graphql-api-automatically)

## Android Validation

The Gradle wrapper starts and its daemon launches, but the Gradle process fails before project configuration or task discovery. `Selector.open()` fails independently in both installed Temurin JDK 17 runtimes with `java.io.IOException: Unable to establish loopback connection` caused by `java.net.SocketException: Invalid argument: connect`. A standalone Java probe reproduces it; a .NET TCP loopback probe succeeds. The app's Java 17 target is available. Proxy/Gradle options and `--no-daemon` did not change the failure. See [`ANDROID_GRADLE_ENV_BLOCKER.md`](ANDROID_GRADLE_ENV_BLOCKER.md) for the exact evidence and host-terminal command.

Therefore `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease`, `assembleDebugAndroidTest`, and `connectedDebugAndroidTest` remain **BLOCKED**, not passed. `adb devices -l` found no device or emulator. Benchmark/profile tasks also remain unverified; no performance benchmark ran.

## Deno Validation

From the `supabase` project directory:

- `deno test -A functions/_shared`: **34 passed, 0 failed**.
- `deno check functions/plan-day/index.ts functions/mcp/index.ts functions/expand-schedules/index.ts`: **passed**.
- `deno fmt --check` over 16 changed Batch 3/4 Deno source and documentation files: **passed**. A broader formatter scan still reports three unchanged legacy files: `functions/sync-push/index.ts`, `functions/_shared/admin.ts`, and `functions/_shared/fcm.ts`; these were not bulk-reformatted.

## Supabase Validation

No local database was available: Docker reports that the `desktop-linux` named pipe is absent. Supabase CLI tests, migration application, and pgTAP acceptance were not run. Remote access used read-only migration history, stored migration statements, function/catalog metadata, and checks of `pgrst.db_schemas` and whether `public.daily_reviews` exists; no application-table data was queried and no schema, data, or project settings were modified. The schema exposure value returned null and `daily_reviews` does not yet exist because its migration is pending. No development branch existed and none was created. The source-level migration/security review is documented separately and does not establish runtime SQL correctness.

## Security Review

- The authenticated `plan-day` function reloads every requested occurrence through the caller's Supabase client and checks owner, pending status, FLEXIBLE type, duration, priority, and scheduling bounds. It validates structured model output against that canonical task set and returns only a proposal; Android validates again before applying.
- Missing `OPENAI_API_KEY` or `OPENAI_MODEL` returns a generic 503 from Plan My Day. Ordinary Vanta scheduling does not depend on either value.
- The AI context includes compact busy intervals with their labels, plus canonical flexible-task fields. It omits OAuth tokens, raw event IDs, attendees, locations, task notes, and completed occurrence IDs. Treat Calendar titles sent as busy labels as external-service disclosure.
- Daily review writes derive the owner in the RPC; reads use RLS. Habit history and skip queues are owner-scoped. Typed app preferences do not store credentials.
- Root [`.env.example`](../.env.example) contains variable names only and classifies required, optional, and deployment-only configuration. Google Calendar setup, package name, signing fingerprints, read-only scopes, Play Services callback assumptions, and disconnect behavior are in [`GOOGLE_CALENDAR_SETUP.md`](GOOGLE_CALENDAR_SETUP.md).

## Performance Review

Source inspection found bounded planner reads (up to 700 scheduled and 100 flexible occurrences), a 365-day habit history window, 7/30-day analytics, a bounded Calendar cache (7 days past/60 days future), and freshness-gated Calendar refresh. Notifications use unique scheduled work and preferences only reschedule workers when relevant values change. AI and Calendar initialization are now deferred as described above. No Android benchmark or device scenario ran because Gradle and the emulator were unavailable.

## Google OAuth Deployment Requirements

Enable Calendar API, configure the consent screen and exact read scopes, create an Android OAuth client for package `dev.system.app`, and register SHA-1 fingerprints for debug, direct release, and Play App Signing certificates used in production. This authorization uses Google Play services `AuthorizationClient`; it does not require a Calendar redirect URI in the manifest. Complete Google's applicable production verification before public release. Keep Firebase `google-services.json` and signing credentials out of Git. See [`GOOGLE_CALENDAR_SETUP.md`](GOOGLE_CALENDAR_SETUP.md).

## AI Planner Deployment Requirements

Set `OPENAI_API_KEY` and `OPENAI_MODEL` as Supabase Edge Function secrets to enable Plan My Day. They are optional to the rest of Vanta and must not be placed in Android configuration. Confirm the target `public` Data API exposure for daily reviews before applying migrations. No credentials were deployed.

## Known Blockers

- Android compile, lint, unit tests, assemblies, instrumentation, and benchmarks are blocked by JDK selector loopback failure in the Codex-launched Windows environment.
- Room instrumentation tests and Supabase pgTAP tests were not executed.
- The target Supabase Data API exposure could not be established because the read-only `pgrst.db_schemas` query returned null. The read-only migration history confirms 14 applied migrations and eight Batch 3/4 migrations pending.
- Other user-worktree edits were classified in [`VANTA2_WORKSPACE_RECONCILIATION.md`](VANTA2_WORKSPACE_RECONCILIATION.md); separate consent-site and historical Batch 1/2 work is preserved outside this release scope.
- Production Google OAuth verification/client setup and OpenAI secrets remain operator tasks.
- Runtime flows for sign-in/account switch, Calendar connect/disconnect, offline replay, widgets, and review rescheduling were code-reviewed but not exercised on an Android device.

## Ready / Not Ready for Batch 5

**Not ready for Batch 5 yet.** The shared architecture contract and static integration audit are in place, and Deno verification is green. Before starting another feature batch, run the Android suite from a regular host terminal with an emulator/device, run Supabase SQL/pgTAP acceptance against a disposable local database, confirm the target Data API exposure, and exercise account-switch/offline/Calendar flows. No Batch 5 features were implemented.

## Status Matrix

| Area | Status | Evidence |
|---|---|---|
| Git recovered | PASS | Branch `vanta2-batch3-batch4-integration` at baseline `b68662f`; local checkpoint commit created after review; remote not modified. |
| Android compile | BLOCKED | Gradle fails in JDK `Selector.open()` before project configuration. |
| Unit tests | BLOCKED | `testDebugUnitTest` could not start; Android tests were not claimed as passed. |
| Lint | BLOCKED | `lintDebug` could not start because Gradle fails before task discovery. |
| Release build | BLOCKED | `assembleRelease` could not start for the same reason. |
| Deno | PASS | 34 shared tests pass; three Edge Function type checks pass; changed-file formatting passes. |
| Room migrations | PARTIAL | Version 1–8 chain and migration tests reviewed/added; instrumentation not run. |
| Supabase migrations | PARTIAL | Static order/security review passes after filename fixes; SQL/pgTAP and remote exposure unverified. |
| Calendar configuration | PARTIAL | Read-only scopes, memory-only tokens, owner-scoped cache, and disconnect cleanup are source-reviewed; OAuth Console settings, consent verification, signing fingerprints, and signed-device flows are not verified. |
| AI configuration | PARTIAL | Authenticated owner reload and server/client proposal validation are source-reviewed; missing-key behavior is graceful, but server secrets are not configured or verified. |
| Batch 3 ↔ 4 integration | PASS BY CODE REVIEW | Shared preferences, priority, Focus history, occurrence-based habits, review reschedule fields, and optional Calendar seams align in source. |
