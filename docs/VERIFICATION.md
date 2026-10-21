# Verification checklist

Updated: 21 October 2026

## Current status

The four reviewed repository migrations are applied to hosted project `zpxcilhmbbhowqceyafj`, the rollback-safe hosted database acceptance passed, and the updated `mcp` Edge Function is deployed as v6. The owner phone and ChatGPT OAuth acceptance flow is still pending.

The backend rollout is verified through hosted SQL acceptance and an unauthenticated MCP smoke test. The authenticated MCP request through a real owner OAuth token and the owner phone flow remain owner acceptance steps.

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

The following versions were applied in this rollout on 21 October 2026:

| Version | Rollout result |
|---|---|
| `20261021090000` | APPLIED. Authenticated direct occurrence `INSERT`, `UPDATE`, and `DELETE` are revoked. The narrow materialization RPC derives the user and task from `auth.uid()` and an owned active schedule. Android completion replay remains idempotent. |
| `20261021090030` | APPLIED. Same-owner registration and disabled-token reassignment succeed; active foreign-owner takeover is rejected, matching `20261021100000`. |
| `20261021090100` | APPLIED. `system-consent` is private and `probe.html` remains absent. The pending migration retained its exact version and omits a blocked, unnecessary direct delete from `storage.objects`; the object was already absent. |
| `20261021090200` | APPLIED. Refresh triggers are statement-level/batched and signaling errors are caught so they cannot roll back authoritative writes. |

All 14 expected versions are now present in remote history; no repository migrations remain pending. Supabase CLI 2.121.0 was authenticated and linked to `zpxcilhmbbhowqceyafj`. The first dry run listed exactly the four requested versions. The first apply stopped on Supabase's `storage.objects` delete guard after applying `90000` and `90030`; after removing that unnecessary delete from the still-pending `90100` source, a second dry run listed only `90100` and `90200`, and both applied successfully. No migration IDs were generated or manually inserted into hosted history.

The local migration order places these versions before the already-applied `20261021100000`. `20261021090030` preserves the later token-lifecycle semantics when applied in that order.

Read-only post-migration checks confirmed authenticated/anon occurrence writes are denied; the materialization RPC is `SECURITY DEFINER`, has an empty `search_path`, is executable only by authenticated callers, and derives identity from the caller; token ownership protection remains active; the consent bucket is private with zero probe objects; and all 15 widget-refresh triggers are statement-level.

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

GitHub Actions [run 47](https://github.com/KuantumKnight/Vanta/actions/runs/39544908978) passed on commit `8bf3cb802f7ade6b432095e59ca3de7b206b6a40`: Consent site, Supabase Edge Functions, and Android debug APK including emulator instrumentation. All three jobs passed.

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

**Full hosted rollback acceptance: PASS.** `npx --yes supabase db query --linked --file supabase/acceptance_database.sql` exited successfully on 21 October 2026. Every assertion in the transaction ran before its final `ROLLBACK`:

| Assertion | Result |
|---|---|
| Anonymous task mutation denied | PASS |
| Cross-user task, goal, goal-link, and habit isolation | PASS |
| Direct authenticated occurrence `INSERT`, `UPDATE`, and `DELETE` denied | PASS |
| Habit creation and occurrence materialization | PASS |
| Duplicate materialization remains idempotent | PASS |
| Habit schedule update succeeds and completed history is preserved | PASS |
| Cross-user materialization and schedule update denied | PASS |
| MCP create replay succeeds; same operation ID with changed payload rejected | PASS |
| MCP completion replay succeeds | PASS |
| Lost-response Android completion replay succeeds | PASS |
| Missing occurrence is classified as permanent `P0002` | PASS |
| Same-owner token registration and duplicate registration are idempotent | PASS |
| Active foreign-owner token takeover rejected | PASS |
| Disabled token reassignment allowed | PASS |
| Task mutation survives missing refresh credentials | PASS |

The first acceptance attempt exposed an assertion that tried a direct `goals` update even though the deployed grants migration deliberately revokes goal-table writes. The test now accepts either that ACL denial or a zero-row RLS result and still fails if a cross-user row changes. The successful rerun left zero acceptance users, tasks, goals, occurrences, schedules, goal links, completion requests, MCP operation rows, or acceptance device tokens. Both expected Vault rows were present after rollback.

The deployed `mcp` function advanced from v5 to v6 with `verify_jwt=false`. An unauthenticated MCP initialize request returned HTTP 401 with `MISSING_CREDENTIALS`. A live authenticated MCP request requires the owner's OAuth bearer token and was not run in this checkout; the SQL acceptance did exercise the authenticated habit-create and materialization path.

The post-rollout security advisor reports 12 authenticated `SECURITY DEFINER` RPC warnings and one Auth warning for disabled leaked-password protection. All 12 RPCs are intentionally callable by authenticated users, check `auth.uid()` directly or through `private.begin_mcp_operation`, enforce owner identity, use controlled `search_path` values, deny `anon` and `PUBLIC` execution, and are covered by the reviewed SQL/source checks. These lints are accepted design warnings. Leaked-password protection remains disabled; this is accepted for the passwordless V1 sign-in flow and should be revisited if password authentication is enabled.

## Real V1 acceptance status

| Handoff | Status | Evidence / remaining work |
|---|---|---|
| ChatGPT → MCP | BLOCKED | Owner OAuth linking is not complete. |
| MCP → Supabase | PARTIAL | MCP v6 is deployed and database acceptance passed; a live authenticated MCP call awaits owner OAuth. |
| Supabase → FCM | PARTIAL | Refresh signaling is best effort; no owner phone token is registered. |
| FCM → Android | BLOCKED | Closed-app delivery has not been tested on the owner phone. |
| Android → Room | PARTIAL | Room instrumentation tests pass; no owner account sync has run. |
| Room → Widget | PARTIAL | Widget feed tests pass; no live task has appeared on the owner widget. |
| Widget → Offline queue | PARTIAL | Durable queue and local optimistic state are instrumented; offline physical tap remains untested. |
| Offline queue → Supabase | PARTIAL | Replay policies and SQL acceptance are authored; real database acceptance and owner replay remain pending. |
| Supabase → ChatGPT | BLOCKED | Owner OAuth and post-completion ChatGPT query remain pending. |

The newest `system-consent` production deployment is READY, was created on 17 October 2026, and has no Git commit metadata. It does not verify the current consent source. The manual deployment workflow requires the `VERCEL_TOKEN` repository secret. **USER ACTION REQUIRED — VERCEL_TOKEN:** add the secret, then run the existing production deployment workflow from `main` and verify the deployment is READY.

## Owner acceptance after database rollout

1. Install latest APK.
2. Open Vanta.
3. Sign in with owner account.
4. Confirm dashboard loads.
5. Verify owner FCM token appears in Supabase.
6. Add Vanta widget to home screen.
7. Close Vanta.
8. Connect the Vanta MCP in ChatGPT.
9. Complete OAuth using the SAME Supabase account.
10. Tell ChatGPT: “Add 50 push-ups tomorrow.”
11. Verify exactly one task and one occurrence exist.
12. Verify it appears on the widget while Vanta is closed.
13. Turn phone network off.
14. Tap Done in the widget.
15. Verify immediate local disappearance.
16. Force-stop Vanta.
17. Restore network.
18. Allow sync/reopen if necessary.
19. Verify queue clears.
20. Verify Supabase occurrence is completed.
21. Ask ChatGPT: “What do I have tomorrow?”
22. Confirm completed item is not returned as pending.
