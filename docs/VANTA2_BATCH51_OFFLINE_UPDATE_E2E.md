# Vanta 2 Batch 5.1 — Offline Task Update E2E

Date: 2026-10-22
Branch: `vanta2-batch3-batch4-integration`
App source commit under test: `5c787482691029b639565e15b241529d2286de0b`
Hosted Android evidence: [run 39719944499](https://github.com/KuantumKnight/Vanta/actions/runs/39719944499)

## Verdict

**Offline task-update transport E2E: NOT RUN.** The hosted Android gate passed its 57 managed-device tests, but those tests exercise the Room mutation store and reconciliation methods with local test doubles. They do not run `SystemRepository` through an authenticated Supabase transport. No production endpoint, real account, or credential value was used.

## Test environment and safety

- This Windows host has Supabase CLI 2.117.0 at `node_modules/.bin/supabase.cmd` and Docker Desktop installed. The Docker Desktop service could not be opened from this session, and starting the Desktop app did not bring up the Linux engine.
- `supabase/config.toml` identifies project `system`, configures the auth site for `http://localhost:3000`, and has no seed SQL file configured. The existing SQL tests create synthetic `.invalid` accounts inside transactions and roll those transactions back.
- An internal search output included the URL/publishable-key line from `android/local.properties`; neither value was used in a test. The values are omitted here.
- There is no dedicated disposable Supabase project/account configured for this test. `SystemRepository` creates its Supabase client from `BuildConfig`, which Gradle derives directly from `android/local.properties`; it has no injected transport/client seam. `isConfigured` also requires an `https://` URL, while the local Supabase stack exposes HTTP by default.
- Local startup attempt: `Start-Service -Name com.docker.service` failed with “Cannot open 'com.docker.service' service on computer '.'.” Then `supabase start` exited 1 with `failed to inspect container health: failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine; check if the path is correct and if the daemon is running: open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified.` Starting Docker Desktop did not provide the Linux engine. Its processes were stopped when free RAM reached about 1.69 GB; service remained stopped, the engine unavailable, and the final memory recheck showed about 1.77 GB free. `db reset` and pgTAP were not run.

## Scenario results

| Scenario | Result | Evidence |
|---|---|---|
| C2 — offline edit updates Room and UI immediately | **NOT TESTED** | `SystemDaoOfflineQueueTest.taskUpdateMutationAndOptimisticImageSurviveReopenAndReconcileFromCanonicalRow` stores a typed mutation and optimistic Room row, but it does not exercise the screen or repository with the network unavailable. |
| C3 — pending edit survives Android process death | **NOT TESTED** | The same instrumentation test closes and reopens Room, proving database durability; it does not kill/relaunch the application process. |
| C4 — reconnect replays through authenticated RPC and converges | **NOT TESTED** | No authenticated app-to-Supabase transport run or server-state readback was performed. Repository code dispatches `mcp_update_task_with_scheduling` and reads the canonical occurrence, but this was source inspection only. |
| C5 — duplicate delivery has a safe server effect | **NOT TESTED** | `vanta2_scheduling_semantics.test.sql` contains an idempotent scheduling-update replay assertion, but pgTAP was not executed in this pass. No real transport retry was simulated. |
| C6 — server applies update, response is lost, then replay | **NOT TESTED** | No request proxy, response-loss test, or authenticated transport harness was available. |
| C7 — server delta arrives while a task update is pending | **PASS — Room layer only** | `taskUpdateMutationAndOptimisticImageSurviveReopenAndReconcileFromCanonicalRow` applies a stale delta through the DAO, confirms the optimistic row remains and the checkpoint advances, then reconciles a canonical row. A live server/delta race was not tested. |
| C8 — app restarts after server success but before local acknowledgement | **NOT TESTED** | The server-success/local-acknowledgement boundary was not simulated. |
| C9 — authenticated owner isolation denies a foreign task | **NOT TESTED** | `vanta2_scheduling_semantics.test.sql` includes an owner-scoped update assertion using synthetic users, but it was not executed in this pass. No account data was changed. |
| C10 — timeout/5xx retries back off and eventually succeed | **NOT TESTED** | Room instrumentation verifies persisted retry count/next-attempt scheduling for the queued update, but no transport failure, server response, or eventual network success was exercised. |
| C11 — server rejects unsupported patch fields | **NOT TESTED** | The RPC source rejects keys outside its scheduling-field allowlist. No runtime pgTAP/API rejection was exercised in this pass. |

## Existing coverage that did pass

In hosted run `39719944499`, the Android managed-device suite passed all 57 tests. The task-update Room test verifies optimistic row persistence over a database close/reopen, typed-field presence and explicit-null serialization, stable operation ID and retry timing, delta shielding/checkpoint advancement, and canonical-row reconciliation. Another test verifies rejection of a wrong owner ID, habit/local IDs, and conflicting queued work. These are valuable local persistence checks, not proof of network replay.

The source-reviewed RPC `mcp_update_task_with_scheduling` uses the existing authenticated function grant, scopes the occurrence by `auth.uid()`, applies a request idempotency key, and rejects unknown scheduling keys. Existing pgTAP coverage is authored for owner scoping and idempotent replay, but hosted CI does not run pgTAP.

## Safe backend-only reproduction

After the local Docker Linux engine is available, these commands target only the local project. Do not substitute `--linked` or pass a remote database URL:

```powershell
.\node_modules\.bin\supabase.cmd start
.\node_modules\.bin\supabase.cmd db reset --local --no-seed
.\node_modules\.bin\supabase.cmd test db --local supabase/tests/vanta2_scheduling_semantics.test.sql supabase/tests/system_hardening.test.sql
```

Those commands can validate the SQL owner/idempotency assertions; they do **not** complete C2–C10's Android transport lifecycle. They were not run because the local Docker engine could not start. The app-level test still needs an isolated integration build configuration that points only to local Supabase, a disposable local auth session, and a controllable response-loss/retry seam. The current local-properties file was not used for test setup.

## Remaining blockers

1. No end-to-end app-to-backend fixture currently supplies an isolated Supabase URL/key without using `android/local.properties`.
2. The repository-owned Supabase client is constructed directly and is not transport-injectable, so response-loss and transient HTTP failure cannot be controlled in existing tests.
3. The local Supabase HTTP endpoint does not satisfy the app's current `https://` configured check without an isolated test-only endpoint setup.
4. A physical device or dedicated AVD and disposable auth account are required to verify actual process termination, offline editing, reconnect, and authenticated server convergence.

Do not mark offline update transport E2E PASS until those scenarios run against a disposable authenticated backend and the server and Room states converge after retry.
