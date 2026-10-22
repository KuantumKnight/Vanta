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

An Android 16/API 36 Pixel 2-profile AVD was used for a sign-in-only inspection of the CI debug APK. It was an emulator, not a physical device; the app could not pass sign-in because the CI artifact had no Supabase configuration. Seven unmodified screenshots capture sign-in at 1.0×, 1.3×, and 2.0×, including light/dark and keyboard states. No authenticated app surface or full screenshot matrix was reached. See [VANTA2_BATCH51_ACCESSIBILITY.md](VANTA2_BATCH51_ACCESSIBILITY.md).

## Performance

**NOT MEASURED** — no startup, frame-time, recomposition, or Baseline Profile measurements were produced. Local Gradle failed before benchmark configuration, and the signed-out CI app had no benchmark fixture. The source review records timer scope, query bounds, and known root collection/recomposition risks without claiming runtime results. See [VANTA2_BATCH5_PERFORMANCE.md](VANTA2_BATCH5_PERFORMANCE.md) and [VANTA2_BATCH51_PERFORMANCE_EVIDENCE.md](VANTA2_BATCH51_PERFORMANCE_EVIDENCE.md).

## Accessibility

**PARTIAL** — source and Compose tests cover selected-state semantics, task-card labels, detail heading and control values, polite validation/save announcements, Week task action role, and a 1.8× font-scale scroll case. On the AVD, sign-in was checked at 1.0×, 1.3×, and 2.0× in light/dark and keyboard/IME states; two sign-in hit areas were spot-checked. At 2.0×, the scrolled sign-in subtitle visibly passes beneath the status-bar icons. This emulator-only overlap remains unpatched. TalkBack traversal, measured contrast, reduced-motion behavior, authenticated screens, and widget accessibility were not verified.

## Security and Secret Scan

The editor uses the existing authenticated owner-checked RPC; it does not add a direct table write or unrestricted JSON mutation. Batch 5.1 made no application, database, RPC, grant, or policy changes. Source review confirmed the update RPC's owner check, idempotency key, and scheduling-field allowlist; local pgTAP was not run because Docker's Linux engine could not start. Gitleaks scanned the final `docs/` acceptance artifacts with redaction and found no leaks. The earlier Batch 5 commit-range scan also reported no findings; no runtime database security result is claimed for this pass.

## Known Limitations

1. Physical-device review was not run. Emulator screenshots cover sign-in only; authenticated app surfaces and the full screenshot matrix were unavailable because the CI artifact could not authenticate.
2. At 2.0× font scale, scrolled sign-in copy overlaps the status-bar region in the tested AVD captures. Fix and retest this on a configured acceptance build.
3. Performance and Baseline Profile results are **NOT MEASURED**; the Gradle loopback failure prevented local benchmark APK configuration.
4. Accessibility is **PARTIAL** pending TalkBack, contrast, authenticated-flow keyboard and touch-target, reduced-motion, and expanded-screen review.
5. Offline task-update transport E2E is **NOT RUN**. Room durability, delta shielding, payload mapping, canonical reconciliation, and retry metadata are covered; process-death replay, authenticated RPC transport, response loss, and server convergence were not exercised. Local pgTAP was not run because the Docker Linux engine was unavailable.
6. The editor omits reminders and advanced placement-window fields. Scheduled tasks cannot be changed back to Flexible through this existing RPC.
7. Local-created tasks remain read-only until their initial create mutation syncs.
8. Analytics still maps a null summary to a preparation state because its state model has no distinct query-failure value.

## Recommended Next Step

Use an isolated non-production backend and a seeded acceptance account to reach authenticated surfaces; correct and retest the 2.0× sign-in status-bar overlap, then complete TalkBack, contrast, keyboard, touch-target, reduced-motion, widget, and physical-device checks. Resolve the Windows Gradle loopback failure to run Macrobenchmark on a release-like build. Add an isolated app-to-local-Supabase test setup before claiming authenticated offline replay; include process-death, response-loss, retry, ownership, and canonical server/Room convergence. Keep Batch 6 deferred until these Batch 5 gates are complete or their limitations are explicitly accepted.

## Final Status

```text
Implementation: PARTIAL (manual visual/accessibility evidence and performance measurements remain unavailable)
Android CI: PASS (run 39719944499 on source commit 5c787482691029b639565e15b241529d2286de0b)
Instrumentation: PASS (run 39719944499, including Room migrations and Task Detail/Edit coverage)
Task Detail/Edit: PASS (hosted build and instrumentation passed)
Offline Task Editing: PASS for Room queue, restart, delta protection, payload and reconciliation; RPC transport replay is not integration-tested
Performance: NOT MEASURED
Accessibility: PARTIAL
Worktree: CLEAN after the final documentation-only acceptance commit and push
Batch 6: not started
```

## Batch 5.1 Acceptance Gate

**Hosted CI: PASS.** Source run [39719944499](https://github.com/KuantumKnight/Vanta/actions/runs/39719944499) passed the full Android release gate, Deno, Edge Function, and consent checks. Documentation-only HEAD run [39721565799](https://github.com/KuantumKnight/Vanta/actions/runs/39721565799) also passed on `f96d27f19dbc70812f82803ef89679838934f4e2`.

**Managed-device tests: PASS.** Run `39719944499` reports 57 tests, 0 failures, 0 errors, and 0 skipped.

**Physical-device review: NOT RUN.** An Android 16/API 36 Pixel 2-profile emulator reached sign-in only; no physical Android device was available.

**Accessibility: PARTIAL.** Sign-in-only emulator checks and their seven screenshots are documented. TalkBack, measured contrast, reduced motion, authenticated surfaces, and widgets remain unverified; the 2.0× status-bar overlap remains an open finding.

**Performance: INSUFFICIENT EVIDENCE.** No benchmark measurements or Baseline Profile were produced. The local Gradle launcher failed before project configuration; source review is not a runtime measurement.

**Offline update transport E2E: NOT RUN.** Hosted tests cover the Room mutation/reconciliation layer, not authenticated RPC replay or server convergence. The local Supabase stack could not start because Docker's Linux engine pipe was unavailable; pgTAP was not run.

**Security regression: NONE FOUND.** No backend security code changed. Source review and the final documentation secret scan found no issue; runtime pgTAP checks were unavailable.

**Known limitations:** No physical-device or authenticated-flow acceptance; the 2.0× sign-in inset overlap remains; performance metrics are unavailable; end-to-end task-update transport remains unverified.

**Final Batch 5 status: PARTIAL.** Hosted CI and managed-device instrumentation pass, but the required offline transport E2E, performance measurements, and runtime accessibility acceptance are incomplete.
