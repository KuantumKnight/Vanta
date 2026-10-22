# Vanta 2 Batch 5.1 Performance Evidence

Date: 2026-10-22
Branch: `vanta2-batch3-batch4-integration`
Source revision inspected: `f96d27f`
Result: **INSUFFICIENT EVIDENCE**. This initial local attempt produced no startup, frame-time, recomposition, query-latency, or Baseline Profile output. A later hosted run generated a signed-out Baseline Profile but no acceptance-grade performance measurements; see the hosted follow-up addendum below. The source audit is not runtime performance evidence.

## Measurement attempt and blockers

The existing `android/benchmark` module contains Macrobenchmark tests for cold and warm startup and a dashboard journey. Startup and frame tests request five iterations. Its Baseline Profile test requests up to five iterations and includes startup. The app has a non-debuggable, profileable `benchmark` build type based on `release`.

The available `SYSTEM_CI_QA_FINAL` emulator was an Android 16/API 36 Pixel 2 image (`sdk_gphone64_x86_64`). At inspection it had only `dev.system.app` CI debug installed (version 1.0, version code 1; `DEBUGGABLE`), at the sign-in screen because the CI Supabase configuration had no authenticated test account or seeded data. CI run `39719944499` supplied `app-release-unsigned.apk` (3,908,022 bytes), plus debug and app instrumentation APKs; it did not supply a Macrobenchmark APK. No benchmark APK or generated Baseline Profile output was present in the downloaded artifacts.

The benchmark APK could not be built locally. These commands failed before project configuration:

```powershell
cd android
.\gradlew.bat :benchmark:tasks --no-daemon --stacktrace
$env:JAVA_TOOL_OPTIONS='-Djava.net.preferIPv4Stack=true'
.\gradlew.bat :benchmark:tasks --no-daemon --stacktrace
```

Environment: Gradle wrapper 9.6.0, Temurin OpenJDK 17.0.20.1. Both attempts returned `java.io.IOException: Unable to establish loopback connection`; the stack trace ends in `sun.nio.ch.PipeImpl` / `WEPollSelectorImpl`, caused by `java.net.SocketException: Invalid argument: connect`, while Gradle connects to its single-use daemon. No Gradle task graph was configured. The benchmark was not run against the debug APK or a manually timed shell launch because that would not establish release Macrobenchmark results.

| Evidence requested | Result | Exact limit |
|---|---|---|
| Cold startup (`StartupTimingMetric`) | **NOT MEASURED** | Gradle launcher failed before `:benchmark` configuration; no Macrobenchmark APK was built. |
| Warm startup (`StartupTimingMetric`) | **NOT MEASURED** | Same Gradle failure and missing Macrobenchmark APK. |
| Today scroll and navigation (`FrameTimingMetric`) | **NOT MEASURED** | Same build blocker; installed app was unauthenticated, so dashboard navigation and task data were unavailable. |
| Quick Capture open/dismiss | **NOT MEASURED** | Same build blocker; the sign-in screen does not expose the dashboard action. |
| Task Detail/Edit | **NOT MEASURED** | Not included in the current macro journey, with no signed-in disposable fixture or benchmark APK. |
| Generated Baseline Profile | **GENERATED LATER — SIGNED-OUT ONLY** | This initial local attempt produced no profile. Hosted run `39747774113` later completed an 84.9-second collection and packaged `baseline.prof` and `baseline.profm`, but authenticated routes were not exercised. |
| Focus recomposition count / frame cost | **NOT MEASURED** | No Compose tracing, Layout Inspector, or recomposition counter capture was made. |
| DAO/query duration or returned-row counts | **NOT MEASURED** | No authenticated local dataset or query instrumentation run was available. |
| Startup trace / lazy initialization cost | **NOT MEASURED** | No startup trace or runtime profile was collected. |

The emulator was shut down after inspection and is no longer attached (`adb devices -l` returned no devices). Its font scale was 2.0 and night mode was off when inspected; no benchmark setting changes were made.

## Source audit (not measurements)

### Focus countdown recomposition scope

`FocusScreen.kt` places the one-second `nowEpochMillis` state and its resumed-only loop in `FocusCountdown` (`LaunchedEffect` and `delay(TIMER_REFRESH_MILLIS)`, around lines 177–187). The timestamp-derived remaining time, elapsed time, progress, animation, and visible countdown are read in that child composable (around lines 189–241). `FocusScreen` passes its active session to the countdown, and the per-second clock state is not read in the parent. This supports the source-level expectation that the clock invalidates the countdown subtree; no runtime recomposition count or frame impact was captured.

### Query and collection bounds

- `SystemDao.observePending` in `SystemDatabase.kt` filters pending rows by owner and orders them, but has no SQL `LIMIT` (around lines 16–25). `SystemRepository.pendingOccurrences` exposes this flow (around lines 105–107), and `VantaRoot` collects it unconditionally at the root (around line 155). Upcoming/search therefore retain an unbounded pending-row list in root state; no optimization was made without measured scale evidence.
- Week planner reads in `observePlannerRange` are date-range filtered and cap scheduled rows at 700 and flexible rows at 100 (`SystemDatabase.kt`, around lines 142–180). `VantaRoot` collects the current and selected week flows (around lines 203–209).
- `occurrenceHistoryRange` and `focusHistoryRange` are date/time-range filtered but have no SQL row limit (`SystemDatabase.kt`, around lines 227–234). The repository caps remote history backfill at 5,000 rows per load (`SystemRepository.kt`, `MAX_HISTORY_ROWS_PER_LOAD` and its paging loop), which does not cap all local Room result sizes. Habit history uses the preceding 365 calendar days; analytics and review projections are destination-driven and built on `Dispatchers.Default` (`VantaRoot.kt`, around lines 593–650). No changes were justified by timing or scale measurements.

### Startup construction and lazy dependencies

`SystemApplication.onCreate` creates Room, DataStore preferences, and `SystemRepository`, creates notification channels, collects notification preference flows, and schedules unique 24-hour periodic sync work (`SystemApplication.kt`, around lines 40–80). `SystemRepository` creates its Supabase client as a property and starts collecting auth session status in its IO scope (`SystemRepository.kt`, around lines 85–95 and 117–120); this source review does not establish whether or how much network work occurs during startup.

Calendar repository and authorization are held in lazy delegates in `VantaRoot` (around lines 162–170); the repository is only forced along account-backed observation paths. `AiPlanClient` is also held in a lazy delegate (around lines 231–238) and is created when the explicit planning path requests it. These are construction-path observations only. Startup cost, auth behavior, and service initialization were not traced.

## Reproduction conditions

Retry after the local Gradle/JDK selector failure is resolved and the Macrobenchmark APK can be built. Keep the measurement release-like, use a benchmark-capable emulator or device, and collect the existing five-iteration startup and frame journeys plus the profile journey. To measure dashboard routes, provide a disposable authenticated test account with controlled local data; the current signed-out fallback deliberately skips dashboard navigation rather than fabricating state. Save the benchmark output and device/build metadata before comparing it to a baseline. No regression or performance target can be assessed from this attempt.

## Hosted follow-up addendum

Hosted performance workflow [39747774113](https://github.com/KuantumKnight/Vanta/actions/runs/39747774113) on `6ac4d5d835882d02cba6811a2ec28cb1020c28ad` generated and packaged a Baseline Profile in 84.9 seconds. The output contains `assets/dexopt/baseline.prof` and `assets/dexopt/baseline.profm`. The profile journey used a signed-out app and therefore is not evidence for authenticated Today, Week, Quick Capture, or task-detail flows.

All five Macrobenchmark measurement methods were rejected before collecting samples with `AssumptionViolatedException` for `ERRORS (not suppressed): EMULATOR`. The target was a hosted Android 16/API 36 x86_64 emulator, not a physical or representative device. Retry [39749603818](https://github.com/KuantumKnight/Vanta/actions/runs/39749603818) was launched with the emulator warning explicitly suppressed for provisional diagnostics; any resulting emulator-only data remains non-acceptance evidence. Full status and measurement limits are recorded in [VANTA2_BATCH5_FINAL_PERFORMANCE.md](VANTA2_BATCH5_FINAL_PERFORMANCE.md).
