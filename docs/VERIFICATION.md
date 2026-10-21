# Verification checklist

Updated: 21 October 2026

## Current status

The source fixes for the remaining architecture blockers are in the local `main` checkout. No production migrations or Edge Functions were deployed during this work. Hosted database acceptance is a controlled release gate after source checks, GitHub Actions, and the read-only migration precheck.

Do not claim production verification until the hosted rollback-safe database suite passes and the owner phone + ChatGPT closed-app acceptance flow has run.

## Migration history

The hosted project's read-only migration list was checked on 21 October 2026. Applied versions:

```text
20260924092922 system_core
20260924092932 mcp_idempotency
20260924092937 api_grants
20260924100715 system_server_jobs
20260924101414 system_consent_storage_probe
20260924101708 system_consent_storage_probe_cleanup
20260924102725 system_pgnet_schema_and_indexes
20260926145917 secure_mcp_goal_link_ownership
20261017045929 system_push_refresh_best_effort
20261021100000 system_device_token_lifecycle
```

Pending versions:

| Version | Status before rollout |
|---|---|
| `20261021090000` | Modified before deployment. Keeps direct occurrence writes revoked, adds the owner-checked materialization RPC, and makes already-completed Android replay idempotent. |
| `20261021090030` | Modified before deployment. Returns the UUID and applies the same active-owner token rule as the already-applied `20261021100000`; an active foreign token cannot be claimed. |
| `20261021090100` | Pending. The live `system-consent` bucket is still public, while the `probe.html` object is already absent. The migration's object delete is currently a no-op; it sets the bucket private. |
| `20261021090200` | Pending. Replaces per-row refresh triggers with best-effort statement triggers. |

The hosted `system_register_device_token(text)` definition was read and matches the safe semantics in the applied `20261021100000` migration. The hosted refresh trigger matches the applied `20261017045929` migration. The hosted privilege check still reports authenticated `INSERT` on `task_occurrences` as allowed; that is the pending `20261021090000` change. No pending migration has been applied remotely.

The local migration order places the four pending versions before the already-applied `20261021100000`. `20261021090030` was changed so applying that older pending version cannot restore the former token-takeover behavior.

## Checks actually run

All checks below ran on 21 October 2026 in the Vanta checkout.

| Check | Result |
|---|---|
| Consent `npm run lint` from `web/consent` | Passed. |
| Consent `npm run build` from `web/consent` | Passed with CI placeholder values for the public Supabase build settings. |
| Consent `npm audit` after `npm ci` | Passed: 0 vulnerabilities. |
| `deno test --config supabase/deno.json supabase/functions/_shared/recurrence_test.ts` | Passed: 6 tests, 0 failures. |
| `deno check --config supabase/deno.json supabase/functions/mcp/index.ts supabase/functions/expand-schedules/index.ts supabase/functions/sync-push/index.ts supabase/functions/send-reminders/index.ts` | Passed. |
| `.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` from `android` | Passed. `testDebugUnitTest` had no unit-test sources; lint, APK assembly, and Android test assembly passed. The documented Windows loopback-temp workaround was needed. |
| `.\gradlew.bat --no-daemon connectedDebugAndroidTest` from `android` | Passed on `SYSTEM_CI_QA_FINAL` API 36: 19 tests, 0 failures, 0 errors. Includes queue persistence/migration and retry-state checks, cache identity, replay classification, sync follow-up gating, sign-in, response projection, and widget-feed tests. |

GitHub Actions [run 45](https://github.com/KuantumKnight/Vanta/actions/runs/39536205576) passed on commit `5958a8a33bc8dc1c25e107fc6cdbd934371fefd3`: Consent site, Supabase Edge Functions, and Android debug APK including emulator instrumentation.

## Database integration acceptance

GitHub Actions covers consent, Edge Functions, and Android; it does not connect to any database. Both acceptance scripts use unique synthetic identities and transaction-local fixtures, and both must end with `ROLLBACK`.

The full acceptance SQL covers:

- `anon` task mutation denial and authenticated direct occurrence `INSERT`, `UPDATE`, and `DELETE` denial.
- Cross-user task reads and updates, goal and goal-link reads and updates, and habit schedule reads, updates, and materialization denial.
- Habit creation, occurrence materialization, uniqueness, schedule update, and preservation of completed history.
- MCP create/complete idempotency and rejection of a reused operation ID with changed input.
- Android completion replay after a server commit, already-completed success, and deleted-occurrence behavior.
- Same-owner token retries, multiple devices, active foreign-owner rejection, disabled-token reassignment, and disabling one phone without affecting another.
- A task mutation completing when Vault refresh credentials are absent, with the Vault and test rows restored by rollback.

**Pre-migration baseline: PASS.** After CI run 45 passed and the read-only precheck matched the expected state, `supabase/acceptance_pre_migration.sql` ran against the hosted project on 21 October 2026. It passed current-schema checks for MCP create idempotency and changed-payload rejection, completion retry, habit creation, cross-user RLS and RPC update isolation, current token ownership semantics, refresh failure isolation, and anonymous task-write denial. It confirmed authenticated direct occurrence `INSERT` is still granted while `20261021090000` remains pending. A separate read-only postcheck found zero acceptance users, tasks, goals, occurrences, schedules, or tokens; both Vault credential rows were present again after rollback.

**Full hosted suite: not run.** The CLI needed to apply the four repository versions is not installed or linked in this checkout, and no CLI access token or database password is configured. The available MCP `apply_migration` operation creates its own migration version, so it cannot faithfully apply these existing repository versions. Do not substitute it or repair migration history manually. Continue with the linked Supabase CLI migration workflow once CLI authentication is available; then run `supabase/acceptance_database.sql`. Afterward, use a separate read-only query to confirm zero `vanta-acceptance-%@example.invalid` auth users, `VANTA_ACCEPTANCE_` task/goal titles, related occurrences/schedules/goal links, completion requests for those users, and token strings with that prefix. The full suite has not been called PASS.

The pre-rollout security advisor reported 11 intentionally authenticated `SECURITY DEFINER` RPCs and one Auth warning for disabled leaked-password protection. The post-migration advisor review has not run because the migrations have not been applied.

## Real V1 acceptance status

| Handoff | Status | Evidence / remaining work |
|---|---|---|
| ChatGPT → MCP | BLOCKED | Owner OAuth linking is not complete. |
| MCP → Supabase | PARTIAL | Authenticated function deployment exists, but the changed MCP habit flow has not been exercised against the new migration. |
| Supabase → FCM | PARTIAL | FCM credentials/token exchange were previously checked; no owner phone token is registered. |
| FCM → Android | BLOCKED | Closed-app delivery has not been tested on the owner phone. |
| Android → Room | PARTIAL | Room instrumentation tests pass; no owner account sync has run. |
| Room → Widget | PARTIAL | Widget feed tests pass; no live task has appeared on the owner widget. |
| Widget → Offline queue | PARTIAL | Durable queue and local optimistic state are instrumented; offline physical tap remains untested. |
| Offline queue → Supabase | PARTIAL | Replay policies and SQL acceptance are authored; real database acceptance and owner replay remain pending. |
| Supabase → ChatGPT | BLOCKED | Owner OAuth and post-completion ChatGPT query remain pending. |

The newest available `system-consent` production deployment is READY but was created on 17 October 2026 from the Vercel CLI and carries no Git commit metadata for the 21 October source. The manual deployment workflow requires the `VERCEL_TOKEN` repository secret; the current consent source has not been verified as deployed.

## Owner acceptance after database rollout

1. Deploy the consent site if its Vercel deployment is still pending.
2. Install the latest Firebase-configured APK from `android/app/build/outputs/apk/debug/app-debug.apk`.
3. Sign into Vanta and confirm the device token is enabled under the owner in `device_tokens`.
4. Place the Vanta widget on the phone home screen.
5. Connect ChatGPT Developer mode to `https://<project-ref>.supabase.co/functions/v1/mcp` and complete OAuth as the same Supabase user.
6. With Vanta closed, ask ChatGPT: “Add 50 push-ups tomorrow”. Verify one task and one occurrence in Supabase and then verify the item appears in the widget.
7. Turn off the phone's network, tap **Done** in the widget, and verify the task disappears locally and its stable completion key remains queued after killing and reopening Vanta.
8. Reconnect, wait for replay, and verify Supabase marks the occurrence complete and Room clears the queue item.
9. Ask ChatGPT: “What do I have tomorrow?” and verify the completed item is no longer pending.
