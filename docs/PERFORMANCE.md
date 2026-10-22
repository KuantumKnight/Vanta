# Android Performance

Status: source-reviewed; device measurements unavailable | Last reviewed: 2026-10-22 | Batch-specific findings: docs/VANTA2_BATCH5_PERFORMANCE.md

## Evidence

Vanta has Macrobenchmark and Baseline Profile source in the android/benchmark module. The configured journeys cover cold and warm startup, Today scrolling/navigation, Week, Habits, Upcoming/Goals through More, and opening then dismissing Quick Capture when the authenticated dashboard is available. No startup or frame-timing results are checked in, and no profile-generation run has completed for this batch.

The parent verification attempt of `:app:compileDebugKotlin --no-daemon --stacktrace` failed at Java selector/loopback initialization with `java.io.IOException: Unable to establish loopback connection`, before Android project configuration, as recorded in docs/VANTA2_PRE_BATCH5_GATE.md. `adb devices -l` is empty. Consequently startup latency, frame percentiles, jank, widget sizing, and runtime accessibility remain unmeasured. Source observations in the batch report are not performance measurements.

## Performance constraints

- Keep the Today schedule projection stable across its minute-level current-time marker tick.
- Keep the Focus countdown update inside its resumed, leaf composable.
- Keep week and history reads date-bounded; the current owner-scoped Upcoming/search pending list is intentionally unbounded and is a paging candidate if real account sizes warrant it.
- Keep analytics aggregation off the main thread.
- Keep Calendar and AI clients lazy; do not put optional network clients on the startup path.
- Keep widget refresh event-driven and its persisted projection bounded.
- Keep Compose lists lazy for potentially long task/history content.
- Optimize only against reproducible profiler or benchmark evidence; do not infer jank from source shape alone.

## Current source review

- Today now remembers task/calendar projection and ordered display entries independently of the current minute. Current/next task labels and the now-marker position are still recalculated from the minute clock, and `TodayTimeline`'s parent/LazyColumn content still recomposes on that tick. The expensive schedule projection is isolated from it; device tracing and frame timing are needed to establish actual cost.
- Focus continues to refresh its timestamp-based countdown once per second while resumed. Progress animation uses the shared duration helper, which maps Android's disabled animator scale to zero duration.
- Week planner reads remain bounded to a week, with DAO limits of 700 dated rows and 100 flexible rows. Analytics and history continue using bounded date ranges.
- The root still observes all owner-scoped pending occurrences for Upcoming/search and multiple destination-specific states. That query has no SQL limit; paging or further state isolation should be driven by profile data.
- Calendar and AI construction remain lazy. Startup still initializes Room/repository, preference collectors, notification channels, and periodic sync work.
- Widget rendering consumes persisted local state; refresh scheduling remains event-driven with one-time next-boundary work.

## Verification

Run the configured Android build, JVM, lint, APK, instrumentation, managed-device, Macrobenchmark, and Baseline Profile tasks on a working CI/device host. Record device/API, build variant, compilation mode, iteration count, p50/p90 startup or frame data, and comparison commit. Do not fill metric fields with targets or source estimates.

The current macro/profile source skips dashboard-only interactions when Today navigation is absent, so a fresh sign-in install does not fabricate account state. A seeded test account would be required to measure completion and realistic task-list scrolling. See docs/VANTA2_BATCH5_PERFORMANCE.md for the outstanding journey coverage.
