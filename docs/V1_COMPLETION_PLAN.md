# SYSTEM V1 completion plan

Updated: 21 October 2026

Supabase remains the task authority. Android Room is an owner-scoped offline cache and completion queue. ChatGPT connects through authenticated MCP; the widget reads Room and can queue offline completion.

## Current state

| Area | Status | Evidence / remaining condition |
|---|---|---|
| Source fixes for deployment blockers | Implemented locally | The occurrence and token migrations, Android replay/auth/cache/sync changes, hosted acceptance scripts, and rollout docs are in the current checkout. They are not yet production-deployed. |
| Hosted migration state | Four pending | The hosted project has the original migrations plus `20261017045929` and `20261021100000` applied. `20261021090000`, `20261021090030`, `20261021090100`, and `20261021090200` are pending. See [VERIFICATION.md](VERIFICATION.md) for the full list and remote checks. |
| Database acceptance | Hosted release gate pending | Database acceptance is run against the existing hosted project inside a rollback transaction after source checks, CI, and migration prechecks pass. It is not part of GitHub Actions. |
| Android | Automated checks pass | Build, lint, APK/test APK assembly, and 19 API 36 instrumentation tests passed. No owner sign-in or live server replay has run. |
| Consent and owner linking | Pending | Consent lint/build pass. Vercel deployment and ChatGPT owner OAuth are still pending. |
| FCM and widget | Partially verified | Credentials and test code exist, but no owner device token or closed-app push-to-widget flow is verified. |
| V1 production milestone | Not verified | The complete ChatGPT → Supabase → FCM → Android → Room → widget → offline replay → ChatGPT flow has not run. |

## Source fixes completed

- `20261021090000_system_occurrence_write_grants.sql` keeps direct authenticated occurrence `INSERT`, `UPDATE`, and `DELETE` revoked. The MCP handler now uses an owner-checked `system_materialize_schedule_occurrences` RPC that derives task/user identity from the owned active schedule, bounds the expansion window, and relies on the schedule/time unique key. The migration also makes an already-completed occurrence an idempotent success for Android replay.
- `20261021090030_system_device_token_ownership.sql` now has the same active-owner and disabled-token reassignment rule as the already-applied `20261021100000` function. Same-owner registration is idempotent; an active token cannot be silently transferred.
- Android completion replay records retry state, attempt time, retry count, next-attempt time, error code, and user-visible error text. Success and already-applied results remove the queue entry; authentication failures retain it; transient failures back off; permanent failures and conflicts remain visible without wedging later independent queue items. The persisted idempotency key stays stable.
- Android now derives the Room cache owner from a stable local identity during refresh failure. Remote auth failure no longer makes the local queue and cached rows disappear. A successful account change switches the active cache owner.
- Sync refresh requests are coalesced. A refresh that arrives during a worker schedules one appended follow-up rather than cancelling the active sync. Room sync remains protected by the repository mutex.
- `.github/workflows/ci.yml` keeps CI to non-database checks. Hosted database acceptance remains a controlled release gate and is not run by CI.

## Verification snapshot

On 21 October 2026:

- Consent `npm run lint` and `npm run build`: passed.
- Deno recurrence tests: 6 passed, 0 failed.
- Deno checks for MCP, schedule expansion, push refresh, and reminders: passed.
- Android `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest`: passed. The unit-test task has no unit-test sources.
- Android `connectedDebugAndroidTest`: 19 passed, 0 failed, 0 errors on `SYSTEM_CI_QA_FINAL` API 36.
- Hosted database acceptance: not run yet. First complete source review, non-database CI, read-only hosted prechecks, and the compatibility baseline; apply the reviewed migrations before the full rollback acceptance.
- Production migrations or Edge Functions: not changed.
- Physical-device and owner ChatGPT acceptance: not run.

Exact commands and migration evidence are in [VERIFICATION.md](VERIFICATION.md).

## Release gates

1. Re-read the hosted migration list; confirm the applied `20261021100000` remains safe when earlier pending `20261021090030` is applied; confirm the consent probe object is absent.
2. Run `acceptance_pre_migration.sql` and verify its transaction rolled back with no test rows left.
3. Apply the four pending migrations in filename order only after source review, non-database CI, and migration-order and precheck results pass.
4. Run `acceptance_database.sql`; verify occurrence writes are denied, habit create/update/materialization and history preservation work, active token takeover is denied, and disabled-token reassignment works. Verify no acceptance rows remain.
5. Deploy/redeploy affected Edge Functions, run Supabase security advisors, and review every remaining warning.
6. Stop architecture changes after rollout. Continue with the owner phone acceptance below.

## Owner phone acceptance

After the backend release gates pass:

1. Deploy the consent site through the Vercel production workflow if still pending. Add `VERCEL_TOKEN` to GitHub Actions if that workflow still lacks access.
2. Install `android/app/build/outputs/apk/debug/app-debug.apk` on a Google Play services Android phone.
3. Sign in to Vanta. Confirm the phone's FCM token is enabled under the owner in Supabase.
4. Place the Vanta widget on the home screen.
5. Connect ChatGPT Developer mode to the Supabase MCP URL and complete OAuth as the same Supabase owner.
6. With Vanta closed, ask ChatGPT to add “50 push-ups tomorrow”. Verify exactly one task and occurrence in Supabase, then verify the widget updates.
7. Turn off the network and complete the task from the widget. Verify the local row disappears and the completion remains queued with the same idempotency key after force-stopping Vanta.
8. Reconnect and let sync replay. Verify the server marks the occurrence completed and the Room queue clears.
9. Ask ChatGPT “What do I have tomorrow?” and verify the completed occurrence is no longer pending.

## Definition of done

- Hosted migration history contains all expected versions and hosted rollback-safe database acceptance passes.
- Hosted rollout passes the stated rollback acceptance and security-advisor review.
- Android build, lint, instrumentation, retry behavior, cache isolation, and follow-up sync checks pass.
- The owner phone flow verifies ChatGPT → MCP → Supabase → FCM → Android → Room → widget, offline queue replay, and the final ChatGPT read.
- Documentation records the actual rollout and device results without claiming verification before they occur.
