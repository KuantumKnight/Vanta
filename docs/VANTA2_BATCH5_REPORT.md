# Vanta 2 Batch 5 Report

Date: 2026-10-22 | Branch: `vanta2-batch3-batch4-integration`

## Summary

Batch 5 delivers the shared Vanta design system and the premium-experience pass across Today, navigation, Week, Habits, Analytics, Settings, Focus, Quick Capture, widgets, and notifications. It now also includes one-off task detail and editing from Today and Week, backed by the existing authenticated task-update RPC and the durable Room mutation queue.

The task editor queues its sparse typed patch and optimistic Room image in one transaction. A Room v8→v9 migration adds update-specific payload and field-presence columns while preserving queued legacy mutations. Delta/bootstrap overlays continue to protect pending local changes. Replay uses the stable operation ID with `mcp_update_task_with_scheduling`, then replaces the optimistic row from an owner-scoped canonical read. No Supabase schema, RPC, grant, RLS policy, MCP tool, or planner algorithm was changed.

## Implementation

**PARTIAL** — the requested product work is implemented and hosted CI is the acceptance gate for Android compilation and instrumentation. Source and automated test coverage do not replace visual review, device accessibility checks, or performance measurements; those remain unavailable.

The shared design system adds semantic colors, typography, spacing, shapes, elevation, icons, layout, timeline, and motion tokens plus reusable card, section-header, chip, and empty-state components. Navigation uses Today, Week, and Habits as primary destinations, with Upcoming, Goals, Analytics, Review, and Settings in More. Existing planning, calendar, AI proposal, offline completion, notification scheduling, widget completion, and focus flows were preserved by source review.

Today schedule projections are memoized across minute ticks. `TodayTimeline` still observes the minute clock at its parent and recomposes LazyColumn content on each tick; this isolates the expensive projection calculation but does not establish a frame-time improvement. The existing SOFT-only drag rule, snap/overlap checks, and time-picker alternative remain.

## Task Detail/Edit

**PASS** — Today and Week task rows open one shared detail sheet. The sheet opens in a read-only summary and offers a separate Edit mode for eligible tasks. The editor supports title, notes, date/time and all-day scheduling, duration, schedule type, priority, and deadline. It reuses the existing Quick Capture validator, applies a local optimistic update, queues sync without waiting for the network, and closes after the local save. Dirty edits prompt before discard. Focus and Complete actions remain available for pending tasks.

Only pending, server-backed, one-off task occurrences can be edited. Habits, completed tasks, local-created tasks awaiting initial sync, and tasks with another queued mutation or completion are read-only. Recurring habit occurrences are not rewritten as one-off tasks. Flexible tasks can be scheduled; the existing RPC does not support reverting a scheduled task to Flexible, and the editor explains this constraint. Placement-window fields (`earliest_start`, `latest_end`, `preferred_start`) and reminders are not exposed in this editor.

## Offline Task Editing

**PASS** — validation, patch construction, durable queue insertion, and optimistic row replacement are atomic in Room. The update survives database close/reopen; incoming deltas preserve the queued local row while advancing the sync checkpoint; canonical reconciliation removes the queue row; and retry payload construction retains the same operation ID and field-presence/null semantics. The existing replay loop dispatches the update through `mcp_update_task_with_scheduling` and refetches the canonical row.

The existing server RPC validates `auth.uid()` ownership, limits the scheduling payload to approved keys, and uses an idempotency key. Its existing pgTAP coverage for owner isolation/idempotency was inspected; no server migration or policy changed. Android tests do not inject the Supabase transport, so RPC dispatch, lost-response replay, and network error backoff were not exercised end-to-end for this new operation. Existing offline-completion retry tests cover the shared queue retry behavior, not the update transport itself.

## Android CI

**PASS** — hosted release gate run `39714036235` passed on the checkpoint commit after correcting two Kotlin imports found by the first hosted attempt. The checkpoint run preceded Task Detail/Edit. Final Batch 5 Android and hosted checks for the task update are recorded after the final CI run below.

| Gate | Result | Evidence |
|---|---|---|
| Gradle configuration | **PASS** | Hosted Android release gate `39714036235` |
| Android JVM tests | **PASS** | Hosted Android release gate `39714036235` |
| Lint | **PASS** | Hosted Android release gate `39714036235` |
| Debug/release APKs | **PASS** | Hosted Android release gate `39714036235` |
| Android test APK | **PASS** | Hosted Android release gate `39714036235` |
| Managed-device instrumentation and Room migration tests | **PASS** | Hosted Android release gate `39714036235`; this run tested the pre-editor checkpoint |
| Deno shared tests | **PASS** | 34 passed, 0 failed |
| Deno Edge Function checks | **PASS** | `mcp`, `expand-schedules`, `sync-push`, `send-reminders` |
| Consent lint/build | **PASS** | `web/consent`: `npm run lint` and `npm run build` |

**Final post-editor CI run:** pending.

The local Windows Gradle wrapper still fails before project configuration with `Unable to establish loopback connection`; hosted Linux CI is the Android build/test evidence.

## Instrumentation

**PASS** — hosted managed-device instrumentation and Room migration tests passed in the pre-editor checkpoint run `39714036235`. Added Task Detail/Edit, large-font scroll, DAO update-queue, and Room v8→v9 migration tests are included in the final hosted gate; final post-editor status is pending.

No Android device is attached locally. No screenshot matrix or interactive manual device session was completed.

## Performance

**NOT MEASURED** — the source-level review found no new root-level second timer and verified bounded Week/widget queries, lazy Calendar/AI construction, and event-driven widget refresh. It also records known root collection and minute-recomposition risks. Macrobenchmark and Baseline Profile source exists but was not run, and there are no before/after numeric measurements. See [VANTA2_BATCH5_PERFORMANCE.md](VANTA2_BATCH5_PERFORMANCE.md).

## Accessibility

**PARTIAL** — source and Compose tests cover selected-state semantics, task-card labels, detail heading and control values, polite validation/save announcements, Week task action role, and a 1.8× font-scale scroll case. Device contrast, TalkBack traversal, keyboard operation, touch-target dimensions, reduced-motion behavior, and light/dark screenshots were not manually verified.

## Security and Secret Scan

The editor uses the existing authenticated owner-checked RPC; it does not add a direct table write or unrestricted JSON mutation. No Supabase database or policy files were changed. The final secret-scan command and result will be recorded after scanning the completed diff.

## Known Limitations

1. Device screenshots and manual visual review were unavailable; there is no attached emulator/device.
2. Performance and Baseline Profile results are **NOT MEASURED**.
3. Accessibility remains **PARTIAL** pending TalkBack, contrast, keyboard, touch-target, and motion review on device.
4. Update-specific Supabase transport replay and retry are not exercised end-to-end because the repository client is not injectable; Room durability, payload mapping, delta shielding, and canonical reconciliation are covered.
5. The editor omits reminders and advanced placement-window fields. Scheduled tasks cannot be changed back to Flexible through this existing RPC.
6. Local-created tasks remain read-only until their initial create mutation syncs.
7. Analytics still maps a null summary to a preparation state because its state model has no distinct query-failure value.

## Recommended Next Step

Complete the final hosted release gate on the task-update commit and record its run. Then capture the screenshot matrix and perform TalkBack, contrast, reduced-motion, and macrobenchmark checks on a seeded Android device. Do not begin Batch 6 until the final CI evidence is recorded and these remaining Batch 5 checks are either completed or explicitly accepted as unavailable.

## Final Status

```text
Implementation: PARTIAL
Android CI: PASS (checkpoint run 39714036235; final post-editor run pending)
Instrumentation: PASS (checkpoint run 39714036235; final post-editor run pending)
Task Detail/Edit: PASS by source and added automated coverage; final hosted test result pending
Offline Task Editing: PASS for Room queue, restart, delta protection, payload and reconciliation; RPC transport replay is not integration-tested
Performance: NOT MEASURED
Accessibility: PARTIAL
Worktree: final status pending
Batch 6: not started
```
