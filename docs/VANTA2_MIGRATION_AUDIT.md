# Vanta 2 Migration Audit

Audited 2026-10-21 from the supplied workspace. This is a source-level review plus read-only Supabase migration-history and database-configuration queries. No local or production schema/data was changed and no migration was applied.

## Status

| Area | Status | Assessment |
|---|---|---|
| Room | **SAFE WITH FIXES** | The source chain is continuous from version 1 to 8 and entity/table/column/index/default declarations align by inspection. Migration tests now cover the oldest version, a Batch 2 version-6 database, and intermediate paths. Runtime validation remains unverified because Android instrumentation is blocked before project configuration and no emulator/device is available. |
| Supabase | **NOT YET VERIFIED** | Static source checks pass for migration dependency order, owner checks, grants, RLS, and definer search paths. Read-only history confirms migrations through 20261021100000 are applied and later migrations are pending. SQL/pgTAP execution and target Data API exposure remain unverified. |

| Check | Result |
|---|---|
| Room migration registration and contiguous path | **PASS** |
| Room migration/entity columns, indexes, and defaults by source inspection | **PASS** |
| Room migration paths authored for v1, v3, v4, v5, and Batch 2 v6 through current v8 | **PASS — execution blocked** |
| Room instrumentation execution | **PARTIAL — blocked before project configuration; no emulator/device** |
| Supabase migration dependency order, with read-only applied-history confirmation | **PASS** |
| Supabase source-level owner/RLS/grant/definer-path review | **PASS** |
| Supabase SQL/pgTAP execution | **PARTIAL — not run against a database** |
| Target Supabase Data API `public` exposure | **PARTIAL — remote setting could not be read** |

## Ordered Supabase migration chain

The current lexicographic order is:

1. `20260924092922_system_core.sql`
2. `20260924092932_mcp_idempotency.sql`
3. `20260924092937_api_grants.sql`
4. `20260924100715_system_server_jobs.sql`
5. `20260924101414_system_consent_storage_probe.sql`
6. `20260924101708_system_consent_storage_probe_cleanup.sql`
7. `20260924102725_system_pgnet_schema_and_indexes.sql`
8. `20260926145917_secure_mcp_goal_link_ownership.sql`
9. `20261017045929_system_push_refresh_best_effort.sql`
10. `20261021090000_system_occurrence_write_grants.sql`
11. `20261021090030_system_device_token_ownership.sql`
12. `20261021090100_system_private_consent_probe_bucket.sql`
13. `20261021090200_system_statement_batched_refresh.sql`
14. `20261021100000_system_device_token_lifecycle.sql`
15. `20261021110000_system_occurrence_write_guard_and_completion_outcomes.sql`
16. `20261021120000_system_statement_batched_refresh.sql`
17. `20261021130000_vanta2_task_duration.sql`
18. `20261021170000_vanta2_daily_reviews.sql`
19. `20261021180000_vanta2_scheduling_semantics.sql`
20. `20261021185000_vanta2_delta_sync.sql`
21. `20261021185500_vanta2_reschedule_occurrence.sql`
22. `20261021190000_vanta2_task_reminder_preference.sql`

The target project's read-only migration history contains the first 14 migrations, through `20261021100000_system_device_token_lifecycle`; none of the eight later migrations in this workspace are recorded as applied. The four earlier migrations at 09:00–09:02 had been missing from the supplied tree; because their paths were absent, they were restored from the recovered baseline and their IDs/source match the remote history. Two other already-applied files have local edits in the worktree and are excluded from the integration checkpoint: `20260926145917_secure_mcp_goal_link_ownership.sql` has a comment-only change, and `20261021100000_system_device_token_lifecycle.sql` has added revoke/drop statements. The latter's owner-token revoke is already present in applied migration `20261021090000`; do not deploy or commit these local edits as changes to applied migration history without a separate reviewed forward migration. The delta-sync migration reads `task_occurrences.schedule_type`, `priority`, `deadline_at`, `earliest_start`, `latest_end`, and `preferred_start`, which are added by `20261021180000_vanta2_scheduling_semantics.sql`. The reschedule RPC reads `private.system_sync_state` and relies on the delta migration's trigger/helper. Their former names placed both migrations before those prerequisites. Because the read-only history confirms both are unapplied, their filenames were moved to 18:50 and 18:55 respectively. SHA-256 checks confirmed both SQL bodies were unchanged by the rename: delta sync `A8C89B7A7C4C6ACEBD351E51AD0D4A72FA165A5B1030C079EA9E5244786FB0D1`; reschedule `3A72911EBB6E5F2C0B0D813A35F7289E31F631B6BDD3CA631E0905026E2CC87A`. No other pending migration filename or SQL body was changed in this audit.

The daily-review migration precedes scheduling semantics but uses only core and duration fields that already exist. The task-reminder preference migration follows the earlier device-token lifecycle migration that creates its target table.

## Room schema and migrations

`SystemDatabase` is version 8. The registered path is contiguous: 1→2, 2→3, 3→4, 4→5, 5→6, 6→7, and 7→8. `SystemApplication` registers each migration in that order. No destructive fallback is configured.

The current Room declarations align by source inspection with the migrations:

- `MIGRATION_1_2` through `MIGRATION_5_6` preserve the existing cache, completion queue, and typed mutation/sync tables.
- `MIGRATION_6_7` creates `occurrence_history`, `focus_session_history`, `daily_reviews`, and `pending_habit_skips`, with the owner/range and queue indexes declared by their entities.
- `MIGRATION_7_8` adds scheduling fields to cached, staged, and queued rows. The `SOFT` and `NORMAL` non-null defaults match the entity `ColumnInfo` defaults. The scheduling and deadline indexes match the current cache indexes.
- Local review keys include `userId`; history and focus queries have owner/range indexes; habit skip uniqueness is scoped by owner and occurrence.

`SystemDatabaseMigrationTest` now provides migration paths from the oldest supported version 1, legacy versions 3 and 4, pre-Batch-2 version 5, and the Batch 2 version 6 through version 8:

- `oldestSupportedCacheMigratesThroughEveryVersionWithoutDroppingOfflineWork`: migrates version 1 through every registered migration to version 8, checks legacy kind/default behavior, duplicate completion cleanup, and Batch 4 tables.
- `versionThreeQueueAndCacheMigrateWithoutDroppingOfflineWork`: its builder includes migrations 1→2 through 7→8.
- `versionFourCacheMigratesWithNullDurationAndKeepsOfflineRows`: its builder includes migrations 4→5 through 7→8.
- `versionFiveCacheMigratesWithTypedMutationQueueAndCheckpointTables`: its builder includes migrations 5→6 through 7→8, and checks the `SOFT`/`NORMAL` scheduling defaults and the four new Batch 4 tables.
- `batchTwoVersionSixCacheMigratesToCurrentAndKeepsQueuedReschedule`: starts from the Batch 2 version-6 schema and traverses 6→7→8, preserving the typed offline reschedule queue and adding the Batch 4 history tables. The Batch 4 version-7 step is traversed in this path; there is no separate version-7-only fixture.

The Android instrumentation command `:app:connectedDebugAndroidTest` is blocked before project configuration with `java.io.IOException: Unable to establish loopback connection`. `adb devices -l` reports no attached devices and no emulator is available. The added migration tests have therefore not run. Room schema export is disabled (`exportSchema = false`), so no generated schema snapshots were available for comparison.

## Supabase ownership and security review

- Core owner tables use RLS policies keyed to `auth.uid()`. The API-grants migration removes broad client write grants and limits task/habit mutations to owner-checked RPCs.
- `daily_reviews` has owner-scoped RLS for reads. Direct writes are revoked; `system_save_daily_review` checks authentication, writes `auth.uid()` as owner, and has an empty `search_path` with schema-qualified references.
- `system_list_occurrence_history` is `SECURITY INVOKER`, filters by `auth.uid()`, uses a bounded page limit, and has an empty `search_path`.
- Private sync and idempotency tables revoke access from `public`, `anon`, and `authenticated`. Their trigger helpers are not executable by client roles. Authenticated delta RPCs derive the user from `auth.uid()` and return that owner’s rows only.
- The reschedule RPC checks the authenticated owner, hides foreign and unavailable rows behind the same not-found result, stores idempotency rows in a revoked private table, and uses an empty `search_path`.
- Task-reminder preference updates check the caller and the token’s owner. The due-reminder claim function is only executable by `service_role`.
- All reviewed `SECURITY DEFINER` functions in the migration chain declare a controlled search path. Functions that use an empty path qualify schema references; other paths place `pg_temp` last.
- Local `supabase/config.toml` exposes `public` and `graphql_public`. Deployment still needs a check that the target project exposes `public` through the Data API, because Android reads `daily_reviews` via PostgREST. This audit did not inspect or change remote API settings.

## Verification limits and follow-up

Supabase CLI is available from the repository as `npx --no-install supabase --version` (`2.117.0`). The Docker CLI is present, but `docker info --format '{{.OSType}} {{.ServerVersion}}'` fails because `npipe:////./pipe/dockerDesktopLinuxEngine` does not exist. The active Docker context is `desktop-linux`; no local Linux database engine is reachable, so local pgTAP and migration acceptance were not run. Read-only queries confirmed migration history through `20261021100000` and that `public.daily_reviews` does not yet exist. `current_setting('pgrst.db_schemas', true)` returned null, so the target Data API schema exposure could not be established from SQL. No SQL writes, migrations, deployments, or production data reads were performed.

Before deployment, apply the eight pending migrations in the listed order as one release step, run the SQL migration and pgTAP suites against a disposable local database, and verify `public` is exposed in the target project. Run the Room migration instrumentation suite on a working Android/Gradle host with an emulator or device. Git metadata has been restored from the clean sibling checkout at `b68662fcec8c6ccbb613415e46290ad083eb55b8`; the current workspace is on `vanta2-batch3-batch4-integration`. The remote migration history confirms the supplied pending-migration baseline; the project was not otherwise changed.
