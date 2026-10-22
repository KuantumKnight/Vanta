# Vanta 2 Batch 5.1 — Authenticated Offline Update E2E

Date: 2026-10-22 · Branch: `vanta2-batch3-batch4-integration`
Acceptance source commit: `8d2aaa09c629aeb29b4ebdd3a17a4c0b711ae864` · Hosted source gate: [run 39747927644](https://github.com/KuantumKnight/Vanta/actions/runs/39747927644)

## Verdict

**Offline authenticated task-update E2E: NOT RUN TO COMPLETION.** The disposable backend and configured APKs were prepared, but the fixture instrumentation did not finish its initial sign-in/setup phase. It created neither an E2E state file nor a local or remote task, so no offline edit, process-death replay, authenticated RPC, or convergence scenario was executed.

This is an incomplete run, not a passing result and not a failing product assertion. The instrumentation runner emitted its test-start status and then remained active without returning a result. Two attempts were stopped after the second bounded retry also produced no completion. No later phase was run.

## Isolated environment

- The user approved the disposable Supabase project `vanta2-batch51-offline-e2e-disposable` in KuantumKnight’s Org, ref `zmtouvlokhtbsmmcuxhy`. It is isolated from production.
- All 23 repository migrations were applied. The expected public schema and RLS state were checked. Two disposable auth identities exist.
- The configured debug and instrumentation APKs came from APK-only workflow run [39750915501](https://github.com/KuantumKnight/Vanta/actions/runs/39750915501), artifact `12412778424`, built from the isolated E2E harness revision `5524678abd687e7b1e7aa0ecdeb017ae165d7943`.
- The workflow receives only `VANTA_E2E_SUPABASE_URL` and `VANTA_E2E_SUPABASE_PUBLISHABLE_KEY`. No service-role credential was supplied to Android. The two test-account records were staged in the app-private files directory with mode `600`; credentials are omitted from this report and were not sent to another agent.
- The AVD was Android 16/API 36, Pixel 2 profile, `sdk_gphone64_x86_64`, 1080 × 1920 at 420 dpi. It is an emulator, not a physical device.
- Before and after the attempts, read-only database counts showed `tasks=0` and `task_occurrences=0`. `offline-e2e-state.json` was absent. The test app was left online and open to `MainActivity` for the accessibility handoff.

The evidence was collected, then `supabase projects delete --yes zmtouvlokhtbsmmcuxhy` confirmed deletion of the disposable project. A Supabase project-list check no longer returns its name or ref. Repository Actions secrets `VANTA_E2E_SUPABASE_URL` and `VANTA_E2E_SUPABASE_PUBLISHABLE_KEY` were removed; the unrelated existing `SYSTEM_*` secrets were left intact. After B's keyboard-inset retest, the old fixture APK was uninstalled and `pm clear` removed app data. `run-as` confirmed that `files/offline-e2e-secrets.json` and all app-private files were absent; font scale and density were restored and the AVD was returned on the launcher.

## Attempt record

The phase-1 command selected only `OfflineUpdateFixtureSetupTest#phase1CreateAndSyncTaskAsAuthenticatedUserA`. AndroidJUnitRunner reported that the test started, and the app initialized the Supabase client with no prior stored session. It did not return a final instrumentation result. The first adb client remained active for several minutes; the retry was allowed 60 seconds and was then cancelled. The application was force-stopped and relaunched, restoring the emulator to an online, signed-out state.

The test harness source is on the isolated E2E branch. It includes phases for authenticated fixture creation, offline UI edit, force-stop/restart persistence, replay and delta handling, cross-user/allowlist checks, and post-reconnect UI. Those methods are **not execution evidence** because phase 1 never completed.

## Required scenario results

| Required scenario | Result | Evidence / limit |
|---|---|---|
| Basic offline edit | **NOT RUN** | Fixture creation/sign-in did not complete; no task was available to edit. |
| Process-death persistence | **NOT RUN** | No pending update existed and no app-process death was exercised. |
| Reconnect replay | **NOT RUN** | No offline-to-online RPC was observed. |
| Server convergence | **NOT RUN** | No app update reached the server; final counts remained zero. |
| Delta-race protection | **NOT RUN — LIVE E2E** | Hosted Room instrumentation covers a local stale-delta/queued-row case, but no authenticated server delta race ran in this attempt. |
| Ambiguous-response replay | **NOT RUN — LIVE E2E** | The isolated harness has a test-level same-operation acknowledgement-discard equivalent, but it was never executed and does not drop a real HTTP response. |
| Cross-user authorization | **NOT RUN — LIVE E2E** | The harness includes an authenticated user-B check; it was never executed. Existing RPC/pgTAP source was reviewed separately. |
| Patch allowlist | **NOT RUN — LIVE E2E** | The harness includes unsupported-field rejection; it was never executed. Existing RPC validation was source-reviewed. |
| Retry/backoff | **NOT RUN — LIVE E2E** | No transport failure or replay cycle ran. Persisted retry metadata coverage in Room is lower-layer evidence only. |

## Lower-layer coverage that remains valid

Hosted Android release gate [39747927644](https://github.com/KuantumKnight/Vanta/actions/runs/39747927644) passed all 57 managed-device tests with 0 failures, 0 errors, and 0 skips. Existing Room tests exercise durable mutation state, delta shielding, canonical reconciliation, and retry metadata. The repository's existing RPC source and SQL tests cover owner scoping, idempotency, and the scheduling-field allowlist; pgTAP was not run for this closure attempt. These results do not prove the authenticated Android transport lifecycle.

## Remaining blockers

1. Diagnose why the configured app's phase-1 authentication/setup instrumentation does not complete, then rerun it with a bounded test timeout and capture a final test result.
2. Only after a fixture is created, run offline edit, actual force-stop/relaunch, normal reconnect replay, delta race, authorized user-B denial, unsupported-field rejection, retry/backoff, and server/Room/UI convergence.
3. The harness's acknowledgement-discard case is not a wire-level lost-response simulation. A true response-loss boundary remains necessary for a complete A7 result.
Do not mark this gate PASS until the app authenticates through the disposable backend and the required persistence, replay, security, and convergence assertions complete.
