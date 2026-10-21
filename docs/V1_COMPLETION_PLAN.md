# SYSTEM V1 completion plan

Updated: 21 October 2026

Supabase remains the task authority. Android Room is an owner-scoped offline cache and completion queue. ChatGPT connects through authenticated MCP; the widget reads Room and can queue offline completion.

## Current state

| Area | Status | Evidence / remaining condition |
|---|---|---|
| Source fixes for deployment blockers | Deployed | All four reviewed migrations are applied; `mcp` is deployed as v6. |
| Hosted migration state | Complete | The four rollout versions are present in remote history and no migration is pending. |
| Database acceptance | PASS | The hosted suite passed inside an explicit transaction ending in `ROLLBACK`; read-only residue checks returned zero acceptance rows. |
| Android | Automated checks pass | Build, lint, APK/test APK assembly, and 19 API 36 instrumentation tests passed. No owner sign-in or live server replay has run. |
| Consent and owner linking | Owner action pending | Consent lint/build pass. The current production deployment does not verify the latest source; the workflow needs `VERCEL_TOKEN`. ChatGPT owner OAuth remains pending. |
| FCM and widget | Partially verified | Credentials and test code exist, but no owner device token or closed-app push-to-widget flow is verified. |
| V1 production milestone | Owner acceptance pending | Hosted schema and MCP deployment passed backend checks. The live owner OAuth, phone, widget, and offline replay flow has not run. |

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
- Hosted migration rollout: `20261021090000`, `20261021090030`, `20261021090100`, and `20261021090200` applied with the exact repository IDs; no pending migrations.
- Hosted rollback acceptance: passed. Anonymous mutation denial, cross-user isolation, direct occurrence write denial, habit creation/update/materialization/history, MCP and Android replay behavior, token ownership lifecycle, missing-occurrence classification, and best-effort refresh assertions all completed successfully.
- MCP Edge Function: deployed from v5 to v6 with `verify_jwt=false`; unauthenticated initialize returned HTTP 401. Live authenticated MCP requires owner OAuth and remains part of phone acceptance.
- Security advisor: 12 authenticated `SECURITY DEFINER` warnings reviewed as intentional and owner-guarded; leaked-password protection warning accepted while auth remains passwordless.
- Vercel: latest production consent deployment is READY but lacks current Git commit metadata; `VERCEL_TOKEN` is required to run the production workflow from `main`.
- Physical-device and owner ChatGPT acceptance: not run.

Exact commands and migration evidence are in [VERIFICATION.md](VERIFICATION.md).

## Release gates

1. **Complete:** migration history matches all expected versions; the four rollout IDs are applied.
2. **Complete:** `acceptance_database.sql` passed and rollback residue is zero.
3. **Complete:** only the affected MCP Edge Function was deployed; unauthenticated access returned 401.
4. **Complete:** security advisor warnings were reviewed and documented.
5. **Owner action:** add `VERCEL_TOKEN` and run the consent production workflow from `main`; verify READY.
6. **Owner action:** complete the phone and ChatGPT acceptance below. No further backend architecture work is planned.

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
