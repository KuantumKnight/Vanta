# Vanta 2 Batch 5 Performance and Accessibility Review

Date: 2026-10-22 | Branch: vanta2-batch3-batch4-integration
Status: NOT MEASURED — source review and benchmark journey updates are complete; hosted Android builds do not provide startup/frame measurements, and no local emulator or device was available for benchmark runs.

## Baseline

The pre-Batch 5 baseline is the integrated Batch 3/4 source, reviewed in docs/VANTA2_REGRESSION_PERFORMANCE_AUDIT.md on 2026-10-21. It established:

- Calendar and AI clients were lazy; analytics, habit, briefing, and review aggregation ran off the main thread.
- Week data was range-bounded; analytics/history were date-bounded; widget reads were owner-scoped and bounded.
- The Focus countdown updated once per second in a resumed leaf composable.
- Today’s current-time tick was minute-level and lived inside the timeline.
- Upcoming/search observed the full pending occurrence list for the owner without a SQL limit.
- Macrobenchmark and Baseline Profile source existed, but no measurements had run.

There is no numeric before measurement. The local verification attempt of `:app:compileDebugKotlin --no-daemon --stacktrace` failed in Windows Java selector/loopback initialization with `java.io.IOException: Unable to establish loopback connection`, before Gradle project configuration; no local Kotlin compilation occurred. Hosted Android release CI passed on checkpoint `39714036235`; final post-editor CI is tracked in [VANTA2_BATCH5_REPORT.md](VANTA2_BATCH5_REPORT.md). `adb devices -l` is empty, so local screenshot and benchmark tasks did not run.

## Changes reviewed

### Today and Focus

- Today schedule calculations and ordered task/Calendar display entries are now remembered by task, day, planning-window, and Calendar inputs rather than the minute clock. The minute-level tick still derives the current task, next task, and marker position. `TodayTimeline` observes that tick at its parent level, so its LazyColumn content still recomposes each minute; this change isolates the expensive schedule projection calculations, not the whole timeline composition. No frame-time reduction is claimed.
- Timeline rescheduling retains the existing soft-block rule, snap interval, overlap checks, callback, and time-picker alternative. The drag path adds live state descriptions and threshold/snap/confirm/reject haptics.
- Focus remains timestamp-based and updates only while resumed. Its layout is scrollable, uses scalable type tokens, exposes countdown/progress semantics, and uses a reduced-motion-aware progress transition. Completion/end actions add haptic feedback.

### Navigation and secondary screens

- The eight-peer tab strip is now three primary destinations—Today, Week, Habits—with a More menu for Upcoming, Goals, Analytics, Review, and Settings. Navigation remains within the existing tab state; selected semantics were added.
- Week selected-day semantics, bounded lazy content, and adaptive card sizing were improved.
- Habit history detail is scrollable; due-today habits are not duplicated in the “other routines” list.
- Analytics exposes a textual description of plotted planned/focused values. A null summary shows a preparation state, but the state model still has no distinct analytics failure value.
- Settings uses a lazy list and content-width constraint. Calendar retains actionable disconnected, stale-cache, and refresh failure states.
- Routine healthy sync timestamps are hidden; active sync, errors, offline saved-task copy, and completion queue recovery remain actionable.

### Widgets and notifications

- Widgets now use shared Vanta spacing/type tokens and a matching palette selected from the saved System/Light/Dark preference. The add action has a readable button label; local completion, offline queueing, and event-driven refresh logic were not changed.
- A theme preference change asks installed Glance widget families to re-render without rebuilding their day projection or scheduling refresh work.
- Morning and evening notification copy is shorter. Their unique one-time scheduling, expected-date checks, time-zone rollover handling, and idempotence logic were not changed.
- Task reminder presentation is driven by the FCM path in SystemMessagingService and was not changed in this UI pass.

### Design-token migration check

A source comparison of inline dp literals in the edited main screens found fewer local dimension literals after token adoption. This is a source-count comparison, not a performance measurement; unchanged screen-specific values remain where they still express layout constraints.

| Screen file | Before | After |
|---|---:|---:|
| DashboardScreen | 8 | 6 |
| TodayTimeline | 39 | 4 |
| QuickCaptureSheet | 8 | 1 |
| WeeklyPlannerScreen | 32 | 16 |
| HabitsScreen | 33 | 17 |
| AnalyticsScreen | 25 | 12 |
| SettingsScreen | 8 | 2 |

### Benchmark journey update

The existing Macrobenchmark and Baseline Profile journeys now navigate Week and Habits directly and open Upcoming/Goals through More. They return to Today and open/dismiss Quick Capture without creating task data. Dashboard-only interactions are skipped when the Today tab is absent, so fresh sign-in runs do not fabricate account state.

Task Detail/Edit now exists and its source/Compose tests cover opening and editing, but the macrobenchmark journey does not open it. There is no stable signed-in seeded benchmark fixture. A completion or edit journey should use a disposable test account and controlled fixture rather than mutate a user's existing task.

## Measurement results

| Measurement | Baseline | Batch 5 | Status |
|---|---:|---:|---|
| Cold startup | Not measured | Not measured | Local Gradle failed before configuration; no benchmark device |
| Warm startup | Not measured | Not measured | Local Gradle failed before configuration; no benchmark device |
| Today scroll frame timing | Not measured | Not measured | No attached emulator/device |
| Week/Habits navigation timing | Not measured | Not measured | No attached emulator/device |
| Quick Capture open/dismiss timing | Not measured | Not measured | No attached emulator/device |
| Task Detail/Edit open timing | Not measured | Not measured | Not in macrobenchmark journey; no seeded device fixture |
| Generated Baseline Profile | None recorded | Not generated | Device profile task not run |

The benchmark source requests five iterations for startup and frame timing, but it has not produced any measurements. ADB returned no connected device. Do not interpret the source changes as proof that startup or scrolling has no regression.

## Performance findings and limits

- The root continues to collect the full owner-scoped pending list for Upcoming/search. Its Room query has no LIMIT. The Week planner is separately limited to 700 dated rows and 100 flexible rows. Paging the pending list is deferred until account-scale measurements justify changing data access.
- VantaRoot still owns many screen states and observes Today/selected-week planner flows even when another destination is active. No root timer loop exists; only the Today clock and resumed Focus timer tick. Recomposition scope should be verified with Compose tracing on device.
- More specifically, VantaRoot always collects the owner-scoped pending list, completion queue, preferences, current-week and selected-week planner rows, and Calendar status/catalog/date-range flows. Destination-specific habit, analytics, briefing, and review projections are loaded from root effects when their destination is selected, but the state holders remain at the root. This is a source-level recomposition risk, not evidence that every screen recomposes for each emission.
- Analytics, habit, briefing, and review aggregation remain on Dispatchers.Default by prior source audit. Calendar and AI client construction remain lazy. Startup source was not redesigned.
- Startup still creates Room and the repository, initializes DataStore, creates notification channels, and schedules/collects notification and sync work in `SystemApplication.onCreate`; only optional Calendar and AI clients are lazy. Their startup cost has not been measured.
- Timeline overlap analysis remains proportional to the number of scheduled tasks; there is no minute-by-task rendering grid. Busy-day UI cost has not been profiled.
- Widget refresh remains local and event-driven. Device widget resize and AppWidget update cost were not measured.
- The local Gradle blocker and missing emulator prevent local compilation, lint, instrumentation, Macrobenchmark, and baseline-profile generation. Hosted Android CI confirms compilation and managed-device tests, but does not provide performance measurements.

## Accessibility review

Source-level improvements:

- Navigation exposes selected state and a named More destination; Week day selection exposes a selected state and full date description.
- Analytics chart data now has a TalkBack text equivalent. Focus countdown/progress exposes content descriptions and range information.
- Timeline drag exposes current movement text, keeps the explicit time-picker alternative, and adds confirmation/rejection haptics.
- Focus and habit detail can scroll; Settings and Analytics constrain long content on expanded widths. Widget Add controls are labeled.
- Reduced motion follows Android's animator-duration setting for the Focus progress transition. Existing Material controls continue to use system touch feedback.
- The shared Task Detail sheet gives its title heading semantics, exposes selected field values in picker descriptions, announces validation/save errors politely, and has a Compose test that scrolls long notes at 1.8× font scale. Week schedule blocks expose button role and action semantics. These are source/test checks, not manual TalkBack or contrast certification.
- Offline and error copy remains available while routine sync timestamps are removed.

Not verified on device:

- Contrast ratios for light/dark semantic pairs, TalkBack traversal order, keyboard navigation, touch-target bounds, large font scaling, landscape/tablet behavior, widget launcher resize classes, and reduced-motion behavior. Content-width constraints and scalable `sp` tokens are source evidence only; they do not prove layouts remain usable at large font scale or expanded widths.
- The fixed dashboard FAB and More popup interactions under TalkBack and keyboard navigation.
- Analytics loading and failure distinctions: null currently maps to “Preparing your activity”; no separate error state is available from the current state model.
- Offline transitions, queued completion replay, Calendar cache/error recovery, and notification behavior were not exercised on a device in this pass. The UI paths remain source-present, but no runtime reliability claim is made.

## Remaining risks and next checks

1. Record final post-editor Android release CI and instrumentation results in the Batch 5 report.
2. Run cold/warm startup and frame-timing Macrobenchmarks on a representative physical device and compare distributions to the Batch 3/4 baseline.
3. Generate and archive the Baseline Profile from a signed-in seeded test account; add controlled Task Detail/Edit and completion journeys only with disposable fixture data.
4. Test light/dark contrast, TalkBack, font scale, compact phone, expanded width, and landscape on device.
5. Revisit pending-list paging and root state isolation only if measured account sizes/recomposition profiles show a need.

No numeric performance target is reported as achieved by this review.
