# Vanta 2 Batch 5 Report

Date: 2026-10-22 | Branch: `vanta2-batch3-batch4-integration`

## Summary

Batch 5 delivers the shared Vanta design system and the premium-experience pass across Today, navigation, Week, Habits, Analytics, Settings, Focus, Quick Capture, widgets, and notifications. It now also includes one-off task detail and editing from Today and Week, backed by the existing authenticated task-update RPC and the durable Room mutation queue.

The task editor queues its sparse typed patch and optimistic Room image in one transaction. A Room v8→v9 migration adds update-specific payload and field-presence columns while preserving queued legacy mutations. Delta/bootstrap overlays continue to protect pending local changes. Replay uses the stable operation ID with `mcp_update_task_with_scheduling`, then replaces the optimistic row from an owner-scoped canonical read. No Supabase schema, RPC, grant, RLS policy, MCP tool, or planner algorithm was changed.

## Implementation

**PARTIAL** — the requested product work is implemented and the complete hosted release gate is green. The remaining evidence gap is the device-level visual/accessibility review and performance measurement; source and automated test coverage do not replace those checks.

The shared design system adds semantic colors, typography, spacing, shapes, elevation, icons, layout, timeline, and motion tokens plus reusable card, section-header, chip, and empty-state components. Navigation uses Today, Week, and Habits as primary destinations, with Upcoming, Goals, Analytics, Review, and Settings in More. Existing planning, calendar, AI proposal, offline completion, notification scheduling, widget completion, and focus flows were preserved by source review.

Today schedule projections are memoized across minute ticks. `TodayTimeline` still observes the minute clock at its parent and recomposes LazyColumn content on each tick; this isolates the expensive projection calculation but does not establish a frame-time improvement. The existing SOFT-only drag rule, snap/overlap checks, and time-picker alternative remain.

## Task Detail/Edit

**PASS** — Today and Week task rows open one shared detail sheet. The sheet opens in a read-only summary and offers a separate Edit mode for eligible tasks. The editor supports title, notes, date/time and all-day scheduling, duration, schedule type, priority, and deadline. It reuses the existing Quick Capture validator, applies a local optimistic update, queues sync without waiting for the network, and closes after the local save. Dirty edits prompt before discard. Focus and Complete actions remain available for pending tasks.

Only pending, server-backed, one-off task occurrences can be edited. Habits, completed tasks, local-created tasks awaiting initial sync, and tasks with another queued mutation or completion are read-only. Recurring habit occurrences are not rewritten as one-off tasks. Flexible tasks can be scheduled; the existing RPC does not support reverting a scheduled task to Flexible, and the editor explains this constraint. Placement-window fields (`earliest_start`, `latest_end`, `preferred_start`) and reminders are not exposed in this editor.

## Offline Task Editing

**PASS** — validation, patch construction, durable queue insertion, and optimistic row replacement are atomic in Room. The update survives database close/reopen; incoming deltas preserve the queued local row while advancing the sync checkpoint; canonical reconciliation removes the queue row; and retry payload construction retains the same operation ID and field-presence/null semantics. The existing replay loop dispatches the update through `mcp_update_task_with_scheduling` and refetches the canonical row.

The existing server RPC validates `auth.uid()` ownership, limits the scheduling payload to approved keys, and uses an idempotency key. Its existing pgTAP coverage for owner isolation/idempotency was inspected; no server migration or policy changed. Android tests do not inject the Supabase transport, so RPC dispatch, lost-response replay, and network error backoff were not exercised end-to-end for this new operation. Existing offline-completion retry tests cover the shared queue retry behavior, not the update transport itself.

## Android CI

**PASS** — the final hosted release gate run [39719944499](https://github.com/KuantumKnight/Vanta/actions/runs/39719944499) passed on commit `5c787482691029b639565e15b241529d2286de0b`, including Task Detail/Edit and the managed-device suite. An earlier instrumentation run exposed a test assertion that expected an off-screen field to be visible; the test now scrolls that field into view, and the final run passed.

| Gate | Result | Evidence |
|---|---|---|
| Gradle configuration | **PASS** | Final hosted release gate [39719944499](https://github.com/KuantumKnight/Vanta/actions/runs/39719944499) |
| Android JVM tests | **PASS** | Final hosted release gate `39719944499` |
| Lint | **PASS** | Final hosted release gate `39719944499` |
| Debug/release APKs | **PASS** | Final hosted release gate `39719944499` |
| Android test APK | **PASS** | Final hosted release gate `39719944499` |
| Managed-device instrumentation and Room migration tests | **PASS** | Final hosted release gate `39719944499` |
| Deno shared tests | **PASS** | Final hosted release gate `39719944499`; 6 passed, 0 failed |
| Deno Edge Function checks | **PASS** | Final hosted release gate `39719944499`; `mcp`, `expand-schedules`, `sync-push`, `send-reminders` |
| Consent lint/build | **PASS** | Final hosted release gate `39719944499`; `web/consent` lint and build |

The release gate covers the app source commit above. The follow-up documentation-only commit does not alter that tested source tree.

The local Windows Gradle wrapper still fails before project configuration with `Unable to establish loopback connection`; hosted Linux CI is the Android build/test evidence.

## Instrumentation

**PASS** — all 57 hosted managed-device tests passed in final run `39719944499` (0 failures, 0 errors, 0 skipped). This includes Task Detail/Edit, large-font scrolling, update-queue durability/reconciliation, and Room v8→v9 migration coverage.

No Android device is attached locally. No screenshot matrix or interactive manual device session was completed.

## Performance

**NOT MEASURED** — the source-level review found no new root-level second timer and verified bounded Week/widget queries, lazy Calendar/AI construction, and event-driven widget refresh. It also records known root collection and minute-recomposition risks. Macrobenchmark and Baseline Profile source exists but was not run, and there are no before/after numeric measurements. See [VANTA2_BATCH5_PERFORMANCE.md](VANTA2_BATCH5_PERFORMANCE.md).

## Accessibility

**PARTIAL** — source and Compose tests cover selected-state semantics, task-card labels, detail heading and control values, polite validation/save announcements, Week task action role, and a 1.8× font-scale scroll case. Device contrast, TalkBack traversal, keyboard operation, touch-target dimensions, reduced-motion behavior, and light/dark screenshots were not manually verified.

## Security and Secret Scan

The editor uses the existing authenticated owner-checked RPC; it does not add a direct table write or unrestricted JSON mutation. No Supabase database or policy files were changed. Gitleaks v8.30.1 scanned the Batch 5 commit range and final staged documentation diff with `--redact`; both found no committed or staged secrets. A broad workspace scan reported matches only in ignored or untracked local configuration and generated cache files, outside the pushed change set.

## Known Limitations

1. Device screenshots and manual visual review were unavailable; no local interactive emulator/device session was available.
2. Performance and Baseline Profile results are **NOT MEASURED**.
3. Accessibility remains **PARTIAL** pending TalkBack, contrast, keyboard, touch-target, and motion review on device.
4. Update-specific Supabase transport replay and retry are not exercised end-to-end because the repository client is not injectable; Room durability, payload mapping, delta shielding, and canonical reconciliation are covered.
5. The editor omits reminders and advanced placement-window fields. Scheduled tasks cannot be changed back to Flexible through this existing RPC.
6. Local-created tasks remain read-only until their initial create mutation syncs.
7. Analytics still maps a null summary to a preparation state because its state model has no distinct query-failure value.

## Recommended Next Step

Capture the screenshot matrix and perform TalkBack, contrast, reduced-motion, and macrobenchmark checks on a seeded Android device. Keep Batch 6 deferred until these remaining Batch 5 checks are completed or explicitly accepted as unavailable.

## Final Status

```text
Implementation: PARTIAL (manual visual/accessibility evidence and performance measurements remain unavailable)
Android CI: PASS (run 39719944499 on source commit 5c787482691029b639565e15b241529d2286de0b)
Instrumentation: PASS (run 39719944499, including Room migrations and Task Detail/Edit coverage)
Task Detail/Edit: PASS (hosted build and instrumentation passed)
Offline Task Editing: PASS for Room queue, restart, delta protection, payload and reconciliation; RPC transport replay is not integration-tested
Performance: NOT MEASURED
Accessibility: PARTIAL
Worktree: clean after final report commit
Batch 6: not started
```
