# VANTA 2 Batch 5 — Final Performance Evidence

Date: 2026-10-22
Acceptance result: **INSUFFICIENT EVIDENCE** — hosted emulator-only diagnostic samples were collected, but no representative-device or authenticated product-route measurements exist.

## Summary

The first API 36 benchmark run generated and packaged a Baseline Profile, then all five Macrobenchmark methods stopped at AndroidX Benchmark’s `ERROR: Running on Emulator` check before producing samples. A single bounded retry opted into suppressing only that warning. It completed five-iteration cold/warm startup tests and a combined signed-out scroll/navigation journey. Those numbers are diagnostic for the hosted emulator and are not acceptance-grade.

The retry generated a Baseline Profile for the signed-out initial journey, packaged it in the release-derived profileable `benchmark` APK, and completed a cold-start benchmark configured with `CompilationMode.Partial(BaselineProfileMode.Require)`. The Macrobenchmark journey had no disposable authenticated fixture: the test stayed at sign-in, did not reach Today or Week, and had no Quick Capture action. Task Detail/Edit is not part of the benchmark source.

| Run | Source revision | Result | Artifact |
|---|---|---|---|
| [First attempt 39747774113](https://github.com/KuantumKnight/Vanta/actions/runs/39747774113) | `6ac4d5d835882d02cba6811a2ec28cb1020c28ad` | Profile generation and profileable APK assembly succeeded; all five Macrobenchmark methods failed at the emulator check before samples. | `12412557060` |
| [Emulator-warning retry 39749603818](https://github.com/KuantumKnight/Vanta/actions/runs/39749603818) | `4412bf909ae0a47d4d952bc4c106c9d5662e4b5e` | Startup and signed-out scroll Macrobenchmark methods completed with an opt-in `EMULATOR` suppression. | `12413482717` |

The runner-specific opt-in was `-PallowEmulatorBenchmarks=true`, which sets `androidx.benchmark.suppressErrors=EMULATOR` only for the retry’s instrumentation run. AndroidX’s warning explicitly says emulator timing is not representative and suppression compromises accuracy. This retry does not replace a physical or approved benchmark-device run.

## Environment and reproduction

| Field | Evidence |
|---|---|
| Device | Hosted Android 16 / API 36 x86_64 emulator, generic `emu64x` system image; KVM enabled. |
| Device resources | 2 virtual CPU cores, reported maximum 2.0 GHz, 2,593,071,104 bytes memory; CPU was not locked. |
| Build | `app:benchmark`, derived from the release build type, non-debuggable and profileable; debug signing is used by the benchmark build type. |
| Account and data | No authenticated account, test password, or seeded task data was present in this workflow. Firebase configuration was a non-production placeholder; no Supabase fixture was injected. |
| Iterations | Five per startup and scroll/navigation test, zero warmup iterations. |
| Source branch | Isolated CI harness branch `vanta2-perf-closure`; this is not the acceptance branch. |

The workflow is `.github/workflows/android-performance.yml`. Reproduce its measurement commands from the Android project directory on a supported connected API 36 device/emulator:

```sh
./gradlew --no-daemon :app:generateBenchmarkBaselineProfile
./gradlew --no-daemon :app:assembleBenchmark
./gradlew --no-daemon -PallowEmulatorBenchmarks=true :benchmark:connectedCheck
```

The first two commands do not suppress emulator errors. The final property is a diagnostic-only opt-in used because this hosted runner provided an emulator and no physical Android device. For a physical device, run the connected check without the suppression property. The CI workflow also provisions Android SDK 37/build tools and API 36, installs the emulator runtime dependency `libpulse0`, boots the emulator with bounded timeouts, and uploads raw Android test reports, BenchmarkData JSON, Perfetto traces, device metadata, APK, and generated profile artifacts.

## Collected metrics

`StartupTimingMetric` recorded `timeToInitialDisplayMs`. The table reports the Macrobenchmark median and observed minimum/maximum over five runs. These are retry-run emulator values only.

| Journey | Compilation mode | Median initial display | Observed min–max | Samples |
|---|---|---:|---:|---:|
| Cold startup | Default | 914.945 ms | 883.798–1022.729 ms | 5 |
| Cold startup | `CompilationMode.None` (without Baseline Profile) | 1055.927 ms | 950.265–1071.089 ms | 5 |
| Cold startup | `CompilationMode.Partial(BaselineProfileMode.Require)` | 947.811 ms | 861.841–1105.353 ms | 5 |
| Warm startup | Default | 392.794 ms | 174.326–908.128 ms | 5; coefficient of variation 0.605 |

The profile-required cold-start method completed, so the benchmark exercised the required-profile compilation path. The apparent differences between profile modes are not evidence of a speedup: the run was on a generic emulator, had only five samples per method, and has no pre-Batch 5 or representative-device baseline.

The `todayScrollAndDashboardNavigation` method recorded `FrameTimingMetric`, but the app was signed out. Its navigation helper returns when the dashboard's “More destinations” control is absent, so the test scrolled the first visible sign-in container; it did not scroll Today or navigate Week. The frame data must be read as sign-in journey diagnostics, not Today/Week performance.

| Signed-out scroll metric | P50 | P90 | P95 | P99 |
|---|---:|---:|---:|---:|
| CPU frame duration (`frameDurationCpuMs`) | 180.113 ms | 486.504 ms | 540.677 ms | 734.475 ms |
| Frame overrun (`frameOverrunMs`) | 314.235 ms | 684.510 ms | 727.304 ms | 820.597 ms |

The test reported 3–5 frames per iteration (median 5). These very small, high-cost samples on an emulator are not a representative scroll distribution and are not an acceptance signal.

## Baseline Profile evidence

- `:app:generateBenchmarkBaselineProfile` completed in the retry. Its `VantaBaselineProfileTest.startupAndPrimaryScreens` collection on the API 36 emulator took 106.076 seconds and emitted a signed-out startup profile file of 2,611,986 bytes.
- `:app:assembleBenchmark` completed. `app-benchmark.apk` contained `assets/dexopt/baseline.prof` and `assets/dexopt/baseline.profm`; generated profile metadata included API 28–30 and API 31+ variants (`app-benchmark.dm`, 12,883 and 12,727 bytes respectively).
- `coldStartupRequiringBaselineProfile` completed with `BaselineProfileMode.Require` and emitted five startup samples.
- During `:benchmark:connectedCheck`, the separate `BaselineProfileRule` test recorded an emulator assumption violation; it did not collect a second profile in that stage. The profile evidence comes from the preceding explicit `generateBenchmarkBaselineProfile` task.
- The collected profile follows startup and sign-in only. It does not cover authenticated Today, Week, task details, or Quick Capture.

The profile’s generation, packaging, and required-profile startup path are evidenced. Its effect on physical-device performance and coverage of signed-in screens are **NOT MEASURED**.

## NOT MEASURED and source review

| Required area | Status | Exact reason |
|---|---|---|
| Today scroll/frame timing | **NOT MEASURED** | No authenticated dashboard fixture; the measured scroll was the sign-in container. |
| Week navigation and scrolling | **NOT MEASURED** | The test never reached the dashboard or Week destination. |
| Quick Capture open/dismiss | **NOT MEASURED** | The signed-out screen has no “Create a task” dashboard action, so the benchmark helper returned. |
| Task Detail/Edit open/save | **NOT MEASURED** | Not included in Macrobenchmark source; no seeded authenticated task fixture. |
| Focus recomposition/frame cost | **NOT MEASURED** | No Compose recomposition counter or runtime trace was collected. Source inspection finds a one-second timestamp loop while resumed inside `FocusCountdown`; it does not count recompositions. |
| DAO/query latency, row counts, and query plans | **NOT MEASURED** | No authenticated dataset or runtime SQL instrumentation/trace was supplied. Source inspection only establishes query predicates and limits. |
| Startup component breakdown/lazy-init cost | **NOT MEASURED** | Startup timing measures initial display only; no trace attributed work to Application, Room, DataStore, repository, Calendar, or AI initialization. |
| Before/after performance comparison | **NOT MEASURED** | The Batch 3/4 baseline was source-reviewed, but it has no numeric Macrobenchmark sample from the same device and fixture. |

Source-level bounds and startup behavior, not measured runtime results:

- `SystemDao.observePending` is owner/status filtered and ordered but has no SQL `LIMIT`; `observeCompletionQueue` is also owner-filtered and ordered without a `LIMIT`. `VantaRoot` collects these flows at its root.
- The Week planner range query caps scheduled rows at 700 and flexible rows at 100, with date-range predicates.
- `FocusCountdown` refreshes its timestamp once per second while resumed. The state is local to that child composable; whether this causes avoidable work or dropped frames was not measured.
- `SystemApplication.onCreate` builds the Room database and repository, creates DataStore preferences and notification channels, starts preference-driven scheduling/sync collectors, and enqueues periodic sync work.
- The Calendar repository is behind a lazy delegate, but signed-in `VantaRoot` calendar flow subscriptions access it. The AI plan client is lazy until a planning action uses it. Neither cost was isolated in a startup trace.

These observations are static source review. They do not establish query speed, database result volume, recomposition counts, or startup cost. No optimization was made without a demonstrated runtime regression.

## Acceptance

**Performance remains INSUFFICIENT EVIDENCE.** The retry supplied reproducible, artifact-backed emulator diagnostics and demonstrated the generated Baseline Profile package/required-profile path. Acceptance still requires a representative physical or approved benchmark device, authenticated controlled data, Today/Week/Quick Capture/Task Detail journeys, runtime Focus recomposition/query/startup evidence, and a comparable Batch 3/4 baseline. The complete source notes and risks are in [VANTA2_BATCH5_PERFORMANCE.md](VANTA2_BATCH5_PERFORMANCE.md); the earlier Batch 5.1 measurement record is [VANTA2_BATCH51_PERFORMANCE_EVIDENCE.md](VANTA2_BATCH51_PERFORMANCE_EVIDENCE.md).
