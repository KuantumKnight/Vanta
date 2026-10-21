# Vanta 2 workspace reconciliation

Audited the working tree on 2026-10-21 after checkpoint `63ca3ab4771069c20383e2484e8f97c6790c4057` on `vanta2-batch3-batch4-integration`. This review did not stage, restore, delete, or commit any path. The only file written by this review is this report.

`git status --short` reports 22 modified tracked paths, 12 deleted tracked paths, and 12 untracked status entries. Git collapses untracked directories: those 12 entries contain 21 individual untracked files (including nine screenshots). The findings below classify every reported path. Uncommitted work has no Git history; where intent is inferred from replacement code or accompanying QA documents, that evidence and uncertainty are stated.

## Modified tracked paths

| Path | Category | Evidence and disposition |
|---|---|---|
| `.github/workflows/ci.yml` | `INTENDED_TEST` | Adds a disposable Supabase stack/reset/pgTAP job, Android JVM/lint/build steps, and emulator instrumentation. It is relevant test automation, but it has not run from this workspace. Verify the credential-free Android build still configures before including it in a release checkpoint. |
| `.github/workflows/deploy-consent.yml` | `UNKNOWN` | The workflow remains manually triggered, but the diff removes the `refs/heads/main` restriction. No worktree history explains this change. It expands which branch can be selected for a Vercel deployment; keep it out of the Batch 3/4 checkpoint pending a separate deployment-policy review. |
| `README.md` | `INTENDED_DOC` | Rebrands public documentation as Vanta and points to setup/verification docs. |
| `deno.lock` | `GENERATED` | Adds `jsr:@std/assert@1.0.14`, the version imported by `supabase/functions/_shared/day_plan_test.ts`. The configured Supabase project has its own `supabase/deno.lock`; no root `deno.json` exists. This root lock change is consistent with a Deno invocation from the wrong working directory and is not the Supabase lockfile. The release lead verified this against the Supabase lock and restored the root lock to `HEAD`. |
| `docs/SETUP.md` | `INTENDED_DOC` | Rewrites clean-checkout setup for Vanta, including Supabase, Edge Function secrets, Vercel, and Android configuration. It is general setup guidance rather than evidence of a release deployment. |
| `docs/V1_COMPLETION_PLAN.md` | `INTENDED_DOC` | Records the V1 implementation/acceptance status and owner-dependent device steps. It is separate from the Batch 3/4 gate; claims about hosted completion should be reconciled with the newer gate report before treating it as current release evidence. |
| `docs/VERIFICATION.md` | `STALE_COPY` | Its top migration-reconciliation section says the deployed history ends at `20261021100000` with only ten applied migrations and says `20261021090000`–`20261021090200` are absent. Read-only history used by the integration audit instead confirms 14 applied migrations through `20261021100000`, including those four migrations. This page needs a current reconciliation before it is used as release evidence. |
| `supabase/acceptance_rollback.sql` | `UNKNOWN` | A local edit added an owner-selection comment and removed the cross-account active-token reassignment assertion. Dedicated pgTAP coverage exists, but no history explains the older edit. The release lead restored the tracked script to `HEAD`; do not run this historical script against production. |
| `supabase/migrations/20260926145917_secure_mcp_goal_link_ownership.sql` | `APPLIED_MIGRATION_EDIT` | The only diff was a three-line explanatory comment absent from the recorded applied statement. The release lead removed it; SQL/schema was unchanged and no corrective migration was warranted. |
| `supabase/migrations/20261021100000_system_device_token_lifecycle.sql` | `APPLIED_MIGRATION_EDIT` | The local diff removes the preamble `REVOKE INSERT` and `DROP FUNCTION`, and changes `CREATE FUNCTION` to `CREATE OR REPLACE FUNCTION`. Read-only query of `supabase_migrations.schema_migrations.statements` returned the applied version's exact three statements: this function definition, `REVOKE ALL` on the function from `PUBLIC, anon`, and authenticated `EXECUTE`. The local file now matches those applied statements. This is a source reconciliation only; no new migration or reapplication is needed. |
| `web/consent/app/api/oauth/decision/route.ts` | `UNKNOWN` | Part of a consent-site redesign that improves retry handling and callback safety. Supporting QA evidence exists, but this app work is outside the Batch 3/4 checkpoint and has no commit/history attribution. Preserve pending separate scope attribution. |
| `web/consent/app/auth/callback/route.ts` | `UNKNOWN` | Implements a magic-link callback and constrained return path in the same consent-site work. Preserve pending separate scope attribution. |
| `web/consent/app/globals.css` | `UNKNOWN` | Consent-site visual/branding changes accompany the new Connected apps and sign-in screens; outside the release checkpoint. |
| `web/consent/app/layout.tsx` | `UNKNOWN` | Consent-site metadata/branding change; outside the release checkpoint. |
| `web/consent/app/login/login-form.tsx` | `UNKNOWN` | Adds same-origin return-path storage and magic-link sign-in behavior. QA report describes the intended flow; it is not Batch 3/4 scope. |
| `web/consent/app/login/page.tsx` | `UNKNOWN` | Preserves validated local return paths for magic-link sign-in; outside the checkpoint. |
| `web/consent/app/oauth/consent/page.tsx` | `UNKNOWN` | Adds account/scope disclosure and retry/recovery UI; outside the checkpoint. |
| `web/consent/app/page.tsx` | `UNKNOWN` | Adds Connected apps navigation and Vanta account copy; outside the checkpoint. |
| `web/consent/package-lock.json` | `UNKNOWN` | Lockfile changes Next.js from 16.4.2 to 16.4.0 and removes some registry/integrity and platform metadata. It accompanies an uncommitted package downgrade; no history explains the reason. Verify the intended dependency version and regenerate with the repository's intended npm version before including it. |
| `web/consent/package.json` | `UNKNOWN` | Changes the Next.js dependency from 16.4.2 to 16.4.0. No worktree history explains the downgrade. |
| `web/consent/proxy.ts` | `UNKNOWN` | Adds `/` to the consent site's auth proxy matcher, consistent with redirecting signed-out visitors to login. Outside the checkpoint. |
| `web/consent/vercel.json` | `UNKNOWN` | Adds a schema URL and `outputDirectory: null`; no history explains whether this is needed or generated. Outside the checkpoint. |

## Deleted tracked paths

| Path | Category | References and disposition |
|---|---|---|
| `android/app/src/main/java/dev/system/app/data/LocalCacheIdentity.kt` | `INTENDED_BATCH34_SOURCE` | Superseded by `LocalAccountContext` and `localContextAfterRefreshFailure` in `LocalAccountContext.kt`; current `SystemRepository` uses the replacement. |
| `android/app/src/androidTest/java/dev/system/app/data/LocalCacheIdentityTest.kt` | `INTENDED_TEST` | Superseded by `OfflineStateTest`, which checks retained local owner visibility and cross-account isolation through the new context model. |
| `android/app/src/main/java/dev/system/app/sync/CompletionReplayPolicy.kt` | `INTENDED_BATCH34_SOURCE` | Superseded by `ReplayOutcome` and `classifyReplayError` in `ReplayOutcome.kt`, consumed by current repository replay code. The replacement has more specific SQL-state handling. |
| `android/app/src/androidTest/java/dev/system/app/sync/CompletionReplayPolicyTest.kt` | `INTENDED_TEST` | Replaced by `ReplayErrorClassifierTest` for auth, conflict, retryable, and permanent outcomes. The new test runs in the JVM source set; execution remains blocked by Gradle. |
| `android/app/src/main/java/dev/system/app/sync/SyncRequestGate.kt` | `INTENDED_BATCH34_SOURCE` | Superseded by the suspending `SyncPassCoordinator`, used by repository sync and sign-out. WorkManager unique names/policies handle scheduling. |
| `android/app/src/androidTest/java/dev/system/app/sync/SyncRequestGateTest.kt` | `INTENDED_TEST` | Superseded in part by `SyncPassCoordinatorTest`, which checks serialized follow-up and `REPLACE` policy. The old gate's explicit coalescing contract is not directly retained; confirm that WorkManager replacement semantics are the desired behavior. |
| `android/app/src/androidTest/java/dev/system/app/data/SystemRepositoryResponseTest.kt` | `UNKNOWN` | The deleted test asserted that the occurrence projection includes `title`, `notes`, `kind`, and `archived_at`. Current `SystemRepository.occurrenceColumns()` still selects all four, but the response class/helper are private and no direct replacement test was found. The release lead restored the test from `HEAD` to preserve this assertion. |
| `apk/vanta-debug.apk` | `BASELINE_RECOVERY_ARTIFACT` | The 19 MB tracked APK was already absent from the supplied workspace when Git metadata was recovered from the sibling checkout. It is a generated binary and was not staged. The release lead restored the baseline artifact from `HEAD` so its tracked deletion would not contaminate release status. |
| `supabase/acceptance_database.sql` | `STALE_COPY` | Hosted acceptance script is no longer referenced by current setup/verification docs. Its core hardening scenarios now have disposable-database pgTAP coverage under `supabase/tests/system_hardening.test.sql`. The release lead restored the historical file from `HEAD`; it must not be run against production. |
| `supabase/acceptance_pre_migration.sql` | `STALE_COPY` | Pre-migration hosted compatibility script has no current reference and applies to an older schema baseline. The release lead restored it from `HEAD`; do not run it against production. |
| `web/consent/app/auth/send-code/route.ts` | `UNKNOWN` | Replaced by Supabase magic-link sign-in through the current login form and `/auth/callback`. The walkthrough report documents this as intentional consent-site work, but it is outside Batch 3/4. |
| `web/consent/app/auth/verify-code/route.ts` | `UNKNOWN` | Same magic-link replacement as `send-code`; outside Batch 3/4 and pending scope attribution. |

## Untracked paths

| Path | Category | Evidence and disposition |
|---|---|---|
| `docs/PERFORMANCE.md` | `INTENDED_DOC` | Batch 1 performance targets and measurement guidance; explicitly says targets are not measured results. |
| `docs/VANTA2_BATCH1_BASELINE.md` | `INTENDED_DOC` | Historical Batch 1 baseline; its no-Git statement describes the original supplied workspace before metadata recovery. |
| `docs/VANTA2_BATCH1_REPORT.md` | `INTENDED_DOC` | Historical Batch 1 implementation/verification report. Keep separate from the Batch 3/4 release checkpoint. |
| `docs/VANTA2_BATCH2_BASELINE.md` | `INTENDED_DOC` | Historical Batch 2 baseline; its no-Git statement predates metadata recovery. |
| `docs/qa/brave-walkthrough/REPORT.md` | `INTENDED_DOC` | Consent-site walkthrough record dated 2026-10-09. It explicitly says no account email/OTP was used and live OAuth grant/revocation was not tested. It is evidence for the separate consent-site changes, not Batch 3/4. |
| `docs/qa/brave-walkthrough/01-login.png` | `GENERATED` | Browser walkthrough screenshot; not source. |
| `docs/qa/brave-walkthrough/02-empty-validation.png` | `GENERATED` | Browser walkthrough screenshot; not source. |
| `docs/qa/brave-walkthrough/03-invalid-email-validation.png` | `GENERATED` | Browser walkthrough screenshot; not source. |
| `docs/qa/brave-walkthrough/04-invalid-authorization.png` | `GENERATED` | Browser walkthrough screenshot; not source. |
| `docs/qa/brave-walkthrough/05-consent-signin-return-path.png` | `GENERATED` | Browser walkthrough screenshot; not source. |
| `docs/qa/brave-walkthrough/06-external-return-url.png` | `GENERATED` | Browser walkthrough screenshot; not source. |
| `docs/qa/brave-walkthrough/07-keyboard-focus.png` | `GENERATED` | Browser walkthrough screenshot; not source. |
| `docs/qa/brave-walkthrough/08-connected-apps-signin-guard.png` | `GENERATED` | Browser walkthrough screenshot; not source. |
| `docs/qa/brave-walkthrough/09-decision-retry-return-path.png` | `GENERATED` | Browser walkthrough screenshot; not source. |
| `web/consent/.vercelignore` | `UNKNOWN` | Deployment ignore config accompanying the separate consent-site changes; review with that site's deployment owner. |
| `web/consent/AGENTS.md` | `GENERATED` | Next.js-generated agent rules file; the file itself says `next dev` writes/re-adds the block. Do not treat it as Batch 3/4 product source. |
| `web/consent/app/account/connections/page.tsx` | `UNKNOWN` | New authenticated Connected apps page that lists and revokes OAuth grants. The QA report describes it, but it is outside Batch 3/4 and no commit/history attributes it. |
| `web/consent/app/api/auth/return-path/route.ts` | `UNKNOWN` | Same-origin return-path cookie API for the consent-site magic-link flow; outside the checkpoint. |
| `web/consent/app/api/oauth/revoke/route.ts` | `UNKNOWN` | Authenticated OAuth grant revocation endpoint; outside the checkpoint. |
| `web/consent/lib/auth-return-path.ts` | `UNKNOWN` | Cookie constants shared by consent-site routes; outside the checkpoint. |
| `web/consent/lib/safe-redirect.ts` | `UNKNOWN` | Local-path validation helper for the consent-site flow; outside the checkpoint. |

## Applied migrations

Read-only inspection of the applied statement arrays showed the comment in `20260926145917_secure_mcp_goal_link_ownership.sql` is absent remotely; it was removed, leaving that file at the committed source. The `20261021100000_system_device_token_lifecycle.sql` working diff matches the statements stored for the applied version, so it is retained as an explicit source reconciliation, not a forward migration. A separate read-only catalog query confirmed the deployed `system_register_device_token(text)` function uses an empty `search_path` and grants execution to `authenticated` and `service_role`; `system_register_device_token` ownership/revoke behavior is recorded in the already-applied `20261021090030` migration. No corrective migration was created and no deployed history was changed.

## Recommended reconciliation

1. Keep the 165-file Batch 3/4 checkpoint unchanged; no remaining path was staged by this review.
2. The release lead removed the comment-only applied-file edit from `20260926145917...`, retained the exact `20261021100000` applied SQL source, and restored the generated root `deno.lock` to baseline.
3. The release lead restored the tracked APK from `HEAD` without staging it, restored `SystemRepositoryResponseTest` to preserve its occurrence-projection assertion, and restored the old acceptance scripts/rollback file from `HEAD`. Those scripts remain historical and must not be used against production.
4. The six superseded Android source/test paths remain deletions because their replacement classes and tests are present; the old `SyncRequestGate` coalescing test contract is replaced by `SyncPassCoordinatorTest` serialization and WorkManager `REPLACE` semantics, with Android execution still blocked.
5. Consent-site source, its package downgrade, and the Vercel workflow branch restriction removal are outside this release scope and remain preserved for separate review. The CI workflow, README/setup/V1 docs, Batch 1/2 reports, and browser QA artifacts are also not part of the release checkpoint.
6. `docs/VERIFICATION.md` was updated to identify its earlier migration note as superseded and point to the current read-only 14/8 history.

No unknown source path was silently discarded. The user-worktree changes outside release scope are preserved in a named Git stash; its full object ID and recovery command are recorded below. The consent-site files have been inspected and have a QA walkthrough as evidence, but their authorization and relationship to the Batch 3/4 release remain outside this worktree's Git history.

## Release lead follow-up changes

These paths were changed or created after the initial path-by-path snapshot above and are part of the release-gate review:

| Path | Category | Evidence and disposition |
|---|---|---|
| `.env.example` | `INTENDED_DOC` | Names-only server/client environment template; contains no credential values. |
| `android/app/src/main/java/dev/system/app/ui/VantaRoot.kt` | `INTENDED_BATCH34_SOURCE` | Lazily constructs the Google authorization client and keys Goals/Focus UI state to the active owner. |
| `android/app/src/main/java/dev/system/app/ui/calendar/GoogleCalendarAuthorizationLauncher.kt` | `INTENDED_BATCH34_SOURCE` | Defers Google authorization-client construction until a user authorization action/result path. |
| `android/app/src/main/java/dev/system/app/ui/navigation/DashboardScreen.kt` | `INTENDED_BATCH34_SOURCE` | Memoizes task/goal filtering by its input lists and normalized search text. |
| `docs/ANDROID_GRADLE_ENV_BLOCKER.md` | `INTENDED_DOC` | Records the Java selector/loopback exception, isolation results, and host-terminal comparison. |
| `docs/DEPLOYMENT_V2.md` | `INTENDED_DOC` | Records the applied/pending migration sequence, functions, server-only secrets, and deployment order. |
| `docs/GOOGLE_CALENDAR_SETUP.md` | `INTENDED_DOC` | Documents Android OAuth client identity/scopes, token handling, disconnect, and unverified Console/device steps. |
| `docs/VANTA2_BATCH34_INTEGRATION_REPORT.md` | `INTENDED_DOC` | Updates release evidence and corrects Calendar/AI and Gradle blocker status. |
| `docs/VANTA2_MIGRATION_AUDIT.md` | `INTENDED_DOC` | Adds remote migration-history evidence, privilege inventory, and runtime-verification limits. |
| `docs/VANTA2_REGRESSION_PERFORMANCE_AUDIT.md` | `INTENDED_DOC` | Records the cross-feature static review, low-risk fixes, query/index notes, and runtime limits. |
| `docs/VANTA2_PRE_BATCH5_GATE.md` | `INTENDED_DOC` | Final gate report with exact status values and the release decision. |
| `docs/VERIFICATION.md` | `INTENDED_DOC` | Marks the earlier migration reconciliation as superseded and points to current evidence. |
| `supabase/migrations/20261021100000_system_device_token_lifecycle.sql` | `APPLIED_MIGRATION_EDIT` | The recovered baseline file starts with `REVOKE INSERT`, `DROP FUNCTION`, and `CREATE FUNCTION`; local source removes those lines and uses `CREATE OR REPLACE FUNCTION`. The remote applied statement array contains the local function definition followed by function `REVOKE ALL` and authenticated `GRANT EXECUTE`. This is an explicit source reconciliation only; no migration was created or reapplied. |
| `supabase/migrations/20261021170000_vanta2_daily_reviews.sql` | `INTENDED_BATCH34_SOURCE` | Pending, unapplied migration now explicitly revokes table privileges from PostgreSQL `PUBLIC`, `anon`, and `authenticated` before granting authenticated `SELECT`. |
| `supabase/tests/vanta2_daily_reviews.test.sql` | `INTENDED_TEST` | Adds four direct table-privilege assertions to the existing owner/RPC tests; pgTAP remains unexecuted because no disposable database is available. |

### Applied migration source evidence

For `20260926145917_secure_mcp_goal_link_ownership.sql`, the recovered working copy initially added this three-line comment above the function:

```sql
-- Keep goal/task linking scoped to the signed-in owner, including the
-- ON CONFLICT update path. This migration was restored from the live
-- production function definition so fresh installs reproduce deployed state.
```

The comment is absent from the applied statement and made no SQL/schema change. It was removed, so the current file is byte-for-byte equal to the committed baseline; there is no staged diff and no corrective migration.

For `20261021100000_system_device_token_lifecycle.sql`, the committed baseline began with:

```sql
revoke insert on public.device_tokens from public, anon, authenticated;
drop function if exists public.system_register_device_token(text);
create function public.system_register_device_token(p_token text)
```

The retained local source begins with:

```sql
create or replace function public.system_register_device_token(p_token text)
```

The remainder of the function definition is the same. Read-only `supabase_migrations.schema_migrations.statements` inspection confirms that the applied migration's statements are this `CREATE OR REPLACE FUNCTION` definition, `REVOKE ALL ON FUNCTION ... FROM PUBLIC, anon`, and `GRANT EXECUTE ... TO authenticated`; the direct table insert revoke and `DROP FUNCTION` are not part of the applied record. This local source now matches the recorded applied statements. It will be committed only as an explicitly documented source reconciliation, never as a new or reapplied migration.

### Out-of-scope preservation

Full stash object: `be6d70c967be99b65ff787c25476f7cdd2d03adb`

Message: `Preserve out-of-scope Vanta workspace work after pre-Batch-5 gate`

Recovery: `git stash apply be6d70c967be99b65ff787c25476f7cdd2d03adb`
