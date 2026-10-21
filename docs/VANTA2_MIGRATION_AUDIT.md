# Vanta 2 Migration Audit

Audited 2026-10-21 from the supplied workspace, the existing target project, and a free disposable Supabase project. No production schema or data was changed. The disposable project was built from the 14-migration applied baseline, then used to apply the nine pending source migrations and execute the database acceptance suite.

## Status

| Area | Status | Assessment |
|---|---|---|
| Room | **SAFE WITH FIXES** | The source chain is continuous from version 1 to 8 and entity/table/column/index/default declarations align by inspection. Migration tests now cover the oldest version, a Batch 2 version-6 database, and intermediate paths. Runtime validation remains unverified because Android instrumentation is blocked before project configuration and no emulator/device is available. |
| Supabase | **PASS — DISPOSABLE VALIDATION** | The 14-migration baseline and all nine pending source migrations applied to a free disposable project. All nine pgTAP files passed (153 assertions). Authenticated/anonymous Data API behavior was checked on the disposable project, and a zero-row anonymous request confirmed the existing target project's `public` API exposure. No production migration was applied. |

| Check | Result |
|---|---|
| Room migration registration and contiguous path | **PASS** |
| Room migration/entity columns, indexes, and defaults by source inspection | **PASS** |
| Room migration paths authored for v1, v3, v4, v5, and Batch 2 v6 through current v8 | **PASS — execution blocked** |
| Room instrumentation execution | **PARTIAL — blocked before project configuration; no emulator/device** |
| Supabase migration dependency order, with read-only applied-history confirmation | **PASS** |
| Supabase source-level owner/RLS/grant/definer-path review | **PASS** |
| Supabase pending migrations and function creation | **PASS — 9/9 applied to a disposable 14-migration baseline** |
| Supabase SQL/pgTAP execution | **PASS — 9 suites, 153 assertions** |
| Supabase owner isolation and table ACLs | **PASS — pgTAP and authenticated/anonymous Data API probes** |
| Target Supabase Data API `public` exposure | **PASS — public table endpoint returned the expected anon 42501 denial** |

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
23. `20261021191000_vanta2_service_role_api_grants.sql` (new; explicit service-role and anonymous table ACLs)

The target project's read-only migration history contains the first 14 migrations, through `20261021100000_system_device_token_lifecycle`; none of the nine later migrations in this workspace are recorded as applied there. The four earlier migrations at 09:00–09:02 had been missing from the supplied tree; they were restored from the recovered baseline and their IDs/source match the target history. For the two previously modified applied files, read-only inspection of `supabase_migrations.schema_migrations.statements` established the source of truth: `20260926145917_secure_mcp_goal_link_ownership.sql` had only an added explanatory comment, absent from its applied statement, so that comment was removed. The local `20261021100000_system_device_token_lifecycle.sql` diff (drop/revoke removal and `CREATE OR REPLACE`) matches the three SQL statements recorded for that applied version; it is retained as a source reconciliation, not as a new migration or a request to reapply deployed history. The applied `20261021090030_system_device_token_ownership` record contains the direct-insert revoke and owner-checked function with an empty search path. A read-only catalog query confirms the live `system_register_device_token(text)` definition uses `search_path = ''` and execute ACLs for `authenticated`/`service_role`; `mcp_link_goal_task` has no `PUBLIC`/`anon` execute grants. A newly identified missing explicit service-role grant on `public.goal_progress` is addressed by the pending migration `20261021191000_vanta2_service_role_api_grants.sql`; that migration passed on the disposable project and remains unapplied to the target. The delta-sync migration reads `task_occurrences.schedule_type`, `priority`, `deadline_at`, `earliest_start`, `latest_end`, and `preferred_start`, which are added by `20261021180000_vanta2_scheduling_semantics.sql`. The reschedule RPC reads `private.system_sync_state` and relies on the delta migration's trigger/helper. Their former names placed both migrations before those prerequisites. Because the target history confirms both are unapplied, their filenames were moved to 18:50 and 18:55 respectively. SHA-256 checks confirmed both SQL bodies were unchanged by the rename: delta sync `A8C89B7A7C4C6ACEBD351E51AD0D4A72FA165A5B1030C079EA9E5244786FB0D1`; reschedule `3A72911EBB6E5F2C0B0D813A35F7289E31F631B6BDD3CA631E0905026E2CC87A`.

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
- Local `supabase/config.toml` exposes `public` and `graphql_public`. A zero-row anonymous request to the existing target project's `/rest/v1/tasks` returned PostgREST `42501 permission denied for table tasks`, confirming that `public` is exposed and its anonymous ACL is enforced. The disposable project also returned one owner row and zero rows for another owner under an authenticated JWT.

## Batch 3/4 Data API and function privilege inventory

This review covers the eight feature migrations after the deployed `20261021100000` baseline, plus the grant-hardening migration `20261021191000_vanta2_service_role_api_grants.sql`. The source matrix and ACL assertions were runtime-verified on the disposable project; the existing target remains at its original 14-migration baseline.

### Public table privilege matrix

The matrix is the intended final ACL after all pending migrations, including `20261021191000`. `service_role` is the trusted backend role; the Android app uses authenticated credentials and does not receive a service-role key. “None” means no table-level `SELECT`, `INSERT`, `UPDATE`, or `DELETE` privilege. Owner RLS and RPC checks still apply to authenticated operations.

| Public object | `anon` | `authenticated` | `service_role` | Source / purpose |
|---|---|---|---|---|
| `public.tasks` | None | `SELECT` | `SELECT`, `INSERT`, `UPDATE`, `DELETE` | Authenticated reads are owner-filtered; mutations go through owner-checked RPCs. |
| `public.habit_schedules` | None | `SELECT` | `SELECT`, `INSERT`, `UPDATE`, `DELETE` | Authenticated reads are owner-filtered; schedule writes go through owner-checked RPCs. |
| `public.task_occurrences` | None | `SELECT` | `SELECT`, `INSERT`, `UPDATE`, `DELETE` | Direct authenticated writes are revoked; mutations use owner-checked RPCs. |
| `public.completion_requests` | None | None | `SELECT`, `INSERT`, `UPDATE`, `DELETE` | Internal idempotency state; no direct authenticated access. |
| `public.goals` | None | `SELECT` | `SELECT`, `INSERT`, `UPDATE`, `DELETE` | Owner-filtered reads; mutations use owner-checked RPCs. |
| `public.goal_links` | None | `SELECT` | `SELECT`, `INSERT`, `UPDATE`, `DELETE` | Owner-filtered reads; mutations use owner-checked RPCs. |
| `public.device_tokens` | None | `SELECT`, `UPDATE` | `SELECT`, `INSERT`, `UPDATE`, `DELETE` | Registration uses an owner-checked RPC; Android directly disables its own token. |
| `public.reminder_deliveries` | None | None | `SELECT`, `INSERT`, `UPDATE`, `DELETE` | Internal server delivery state. |
| `public.goal_progress` (view) | None | `SELECT` | `SELECT` | Android and the MCP Edge Function read the aggregate view. New migration explicitly grants service-role `SELECT`; the deployed baseline had not. |
| `public.daily_reviews` | None | `SELECT` | `SELECT`, `INSERT`, `UPDATE`, `DELETE` | Pending daily-review migration grants authenticated read; writes use the owner-derived RPC. New grant migration establishes service-role ACL explicitly. |

The deployed API-grants migration explicitly revokes access from PostgreSQL `PUBLIC`, `anon`, and `authenticated` before granting the narrow authenticated table operations. The deployed core migration explicitly grants service-role full access to the base tables, but omitted the `goal_progress` view. The new pending migration repeats the `PUBLIC`/`anon` revokes across the Vanta public objects, grants service-role full access to the public tables, and grants service-role `SELECT` on `goal_progress`. This fixes the source-level service-role grant gap without editing an applied migration. The accompanying `supabase/tests/vanta2_data_api_grants.test.sql` asserts the complete `anon`, `authenticated`, and `service_role` matrix for `SELECT`, `INSERT`, `UPDATE`, and `DELETE`; all three assertions passed against the disposable project.

### Additional RPC, private, and local object inventory

| Object | Intended Data API / role decision | Source controls reviewed |
|---|---|---|
| `public.system_save_daily_review(date,text)` | **AUTHENTICATED EXECUTE** | `PUBLIC` and `anon` execute revoked; authenticated execute granted; definer writes caller-derived `auth.uid()` only. |
| `public.system_list_occurrence_history(...)` | **AUTHENTICATED EXECUTE** | `PUBLIC` and `anon` execute revoked; authenticated execute granted; `SECURITY INVOKER`, owner filter and bounded page size. |
| `public.system_complete_task_occurrence`, `public.mcp_create_task`, `public.mcp_update_task`, `public.mcp_create_habit`, and their scheduling/reschedule RPCs | **AUTHENTICATED EXECUTE** | Reviewed definitions revoke `PUBLIC`/`anon` (and internal helpers also revoke `authenticated`); intended RPCs grant `authenticated`, derive/check caller ownership, and use controlled `search_path`. |
| `public.system_current_sync_revision()`, `public.system_get_occurrence_changes(...)` | **AUTHENTICATED EXECUTE** | `PUBLIC`/`anon` revoked; authenticated granted; results derive from `auth.uid()` and are owner-scoped. |
| `public.system_set_task_reminders_enabled(text,boolean)` | **AUTHENTICATED EXECUTE** | `PUBLIC`/`anon` revoked; authenticated granted; validates current caller and token owner. |
| `private.system_sync_state`, `private.system_occurrence_changes`, `private.system_reschedule_requests`, private idempotency state | **NOT EXPOSED** | Table privileges revoked from `PUBLIC`, `anon`, and `authenticated`; authenticated RPCs mediate access. |
| `private.claim_due_reminders()` | **SERVICE_ROLE ONLY** | Execute revoked from `PUBLIC`, `anon`, and `authenticated`; granted only to `service_role`. |
| Calendar cache, focus history, habit skip queue, daily-review UI cache, analytics and typed preferences | **LOCAL ROOM STATE** | These Batch 3/4 feature caches are Android Room/SharedPreferences objects, not new public Supabase tables. Habit/task server state reuses existing owner-scoped task/occurrence tables. |

The only new public table in these migrations is `daily_reviews`; the delta and reschedule tables are in `private`. No server-side calendar, planner, analytics, or focus tables were introduced. `current_setting('pgrst.db_schemas', true)` returned null on the existing project, but the live zero-row REST request verified that `public` is exposed. The disposable project applies the pending `daily_reviews` migration and verifies its intended role privileges through pgTAP.

## Disposable Supabase execution and Data API verification

The free project `vanta2-release-gate-20261021` (`keuzgtdimusacrjttacu`) was created in the user-selected Free organization. The test database began empty, then received the repository's 14 applied-baseline migrations in order, followed by all nine pending source migrations. Each pending migration applied successfully:

| Pending migration | Result |
|---|---|
| `20261021110000_system_occurrence_write_guard_and_completion_outcomes.sql` | PASS |
| `20261021120000_system_statement_batched_refresh.sql` | PASS |
| `20261021130000_vanta2_task_duration.sql` | PASS |
| `20261021170000_vanta2_daily_reviews.sql` | PASS |
| `20261021180000_vanta2_scheduling_semantics.sql` | PASS |
| `20261021185000_vanta2_delta_sync.sql` | PASS |
| `20261021185500_vanta2_reschedule_occurrence.sql` | PASS |
| `20261021190000_vanta2_task_reminder_preference.sql` | PASS |
| `20261021191000_vanta2_service_role_api_grants.sql` | PASS |

Three test-only migrations installed pgTAP and brought the test database in line with two source fixes discovered during the run: the final `goal_progress` service-role ACL and the scheduling RPC's JSON-null merge behavior. Those test-only entries are not repository migrations and were not applied to the existing target project.

All nine repository pgTAP files passed: `system_hardening` (32), `system_statement_refresh` (12), `vanta2_daily_reviews` (17), `vanta2_data_api_grants` (3), `vanta2_delta_sync` (18), `vanta2_reschedule_occurrence` (20), `vanta2_scheduling_semantics` (24), `vanta2_task_duration` (16), and `vanta2_task_reminders` (11), for 153 passing assertions. They cover owner A/B isolation, anonymous denial, RPC ownership, direct-write limits, daily reviews, habits/history-related state, delta sync, rescheduling, reminders, and the intended `anon`/`authenticated`/`service_role` table ACL matrix.

An authenticated Data API request against the disposable project returned one task for its owner and zero for another owner; the same task request with only the publishable key was denied with `42501`. Against the existing target project, `/rest/v1/tasks?select=id&limit=0` also returned `42501 permission denied for table tasks`, confirming that the `public` schema is exposed and anonymous table privileges are denied without reading any rows. The target project's `pgrst.db_schemas` session setting is null; the live PostgREST response is the exposure evidence.

Supabase security advisors reported four internal `private` tables with RLS disabled and 23 authenticated `SECURITY DEFINER` RPC findings. Catalog checks confirm `anon` and `authenticated` have no `USAGE` on `private` and no `SELECT` on its sync log, while the exposed owner-facing RPCs are granted intentionally and their ownership checks pass pgTAP. Performance advisors listed five currently unused indexes on the empty test project; those are not a useful signal before production data exists. No advisor-driven schema changes were made.

The existing target project still records only the original 14 applied migrations. No branch was created, no paid resource was used, and no production mutation or deployment occurred. The Supabase CLI deleted the disposable project after validation, and a follow-up project listing confirmed that its ref is gone. Android/Room validation remains blocked before Gradle project configuration by the Java selector/loopback failure; see [JAVA_LOOPBACK_ROOT_CAUSE.md](JAVA_LOOPBACK_ROOT_CAUSE.md). The release therefore remains **NOT READY FOR BATCH 5**.
