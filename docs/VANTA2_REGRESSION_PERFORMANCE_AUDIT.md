# Vanta 2 Cross-Feature Regression and Performance Audit

Audited the integrated Batch 3/4 Android source on 2026-10-21. This is a source review; Android execution remains blocked before Gradle project configuration by the documented Java selector/loopback failure. No Android runtime scenarios or benchmarks are claimed as passed.

## Feature matrix

| Area | Source-review result | Evidence and limits |
|---|---|---|
| Authentication and restore | **COHERENT BY INSPECTION** | Supabase Auth uses PKCE and `sessionStatus`; the remembered local owner remains available when refresh fails. Email sign-in uses the `dev.system.app://auth` callback. No live sign-in/session restore was exercised. |
| Local state, delta sync, account isolation | **COHERENT BY INSPECTION** | Room occurrence, mutation, completion, habit-skip, focus, and review reads are owner-filtered. Bootstrap pages at 250 rows; delta sync validates revision continuity and caps each pass at 20 pages of 250. Account changes hide mismatched local state. Sign-out refuses unresolved completion/task mutation queues, drains focus reconciliation, unregisters the device token, then clears the active occurrence cache/checkpoint. Other queued history/review state remains owner-tagged for that account. Runtime account-switch/offline replay was not exercised. |
| Today and weekly planning | **COHERENT BY INSPECTION** | Today uses the local day timeline; the selected week comes from a seven-day Room range. Hard blocks are not movable, soft commitments are editable, and flexible work remains unscheduled until placed. Duration, priority, deadline, and placement-window fields flow through projections and mutations. The week surface uses lazy lists rather than a minute-by-minute grid. |
| Quick Capture and rescheduling | **COHERENT BY INSPECTION** | Quick Capture produces typed local task mutations; repository validation checks title/notes/duration and schedule semantics before optimistic writes. Reschedule checks pending state, preserves task metadata, and serializes mutations. These flows were not device-tested. |
| Conflicts and AI preview/apply | **COHERENT BY INSPECTION** | Conflict and placement logic is in pure planner modules. AI proposals are validated server-side and again against current local occurrences before mutation; HARD/completed rows and duration/window/deadline violations are rejected. The AI client is lazy and called only from an explicit Plan My Day action. No network or UI scenario ran. |
| Focus and completion | **COHERENT BY INSPECTION** | Focus state is account-keyed in the local store and uses wall-clock timestamps; ending a focus session does not complete the occurrence. Completion is optimistic through a durable owner-scoped queue and has idempotent replay handling. The timer runs only while the Focus screen is resumed. |
| Habits | **COHERENT BY INSPECTION** | Habits reuse occurrence history, and explicit skip uses a distinct owner-scoped outbox. Streaks count expected occurrences; past pending breaks a run, skipped is neutral, and today's pending remains open. Reads cover the latest 365 days, so displayed best streak is explicitly range-limited. |
| Morning briefing and evening review | **COHERENT BY INSPECTION** | Morning briefing is a local projection from today's rows and does not require Calendar. Evening review separates completed, remaining, and skipped rows and stores its note locally before sync. Daily review remote fallback is owner-filtered by the signed-in PostgREST client. Rollover and review sync were not runtime-tested. |
| Analytics | **COHERENT BY INSPECTION** | 7/30-day ranges use local occurrence and existing Focus history, not a second focus source. Queries are date-bounded and indexed by owner/date. Planned-versus-actual estimates require three distinct occurrence samples. History backfill is capped at 5,000 rows per load. |
| Widgets | **COHERENT BY INSPECTION** | NOW/NEXT/Today data is built from owner-scoped Room queries; the occurrence list is capped at 128 while exact day totals use aggregate queries. Widget refreshes occur after mutations/sync, with no periodic UI timer. Widget update behavior was not device-tested. |
| Settings and notifications | **COHERENT BY INSPECTION** | Typed DataStore preferences normalize day bounds, default duration, snap interval, week start, theme, review and reminder settings. Notification scheduling uses unique work and reschedules when relevant preferences change; device timezone is used for notification wall-clock times. |

## Performance and query review

- No unscoped whole-database Room `Flow` was found. Core dashboard state does observe the full pending-occurrence list for the signed-in owner because Upcoming/Search displays that collection. This list has no SQL limit; it can grow with long-range materialized schedules. The Week planner separately uses a bounded query (up to 700 scheduled rows and 100 flexible rows). Consider paging the Upcoming view if real account data shows the active pending cache growing materially.
- Repeated task/goal filtering in `DashboardScreen` is now memoized by the source list and search string. `VantaRoot` account-specific goals and Focus screen state is keyed by owner so an account change clears prior-account UI state.
- Calendar repository construction was already deferred, but `GoogleCalendarAuthorization` still called `Identity.getAuthorizationClient` during root composition. Both root and launcher now defer that client construction until a connect/refresh/disconnect path actually needs it. Ordinary app startup no longer constructs a Google Identity client. This is source-verified; compile/runtime behavior could not be checked.
- AI client construction remains lazy and disposal closes it only if initialized. No API secret is configured in Android source; the planner obtains the signed-in access token and server-side function configuration.
- Today timeline calculations are remembered by their inputs and its clock tick is once per minute inside that screen. Focus countdown refreshes once per second only while resumed. The root composable has no timer-driven recomposition loop.
- Morning briefing, analytics, habit, and evening-review aggregation run on `Dispatchers.Default`; history SQL reads are owner/date scoped. Today/day and week projections are remembered by their bounded input lists and preference/date keys. Week rendering is a seven-day selection with `LazyColumn` blocks, not a full time grid.
- Widgets are refreshed after successful local mutations and after sync rather than by a periodic UI loop. Completion can cause one optimistic refresh and a later post-sync refresh, which reflects both state transitions.

## Index alignment

| Query pattern | Current index/schema evidence | Assessment |
|---|---|---|
| User + status + scheduled time | `cached_occurrences(userId, status, scheduledFor)` | Aligned for pending/date and widget reads. |
| User + completed time | `cached_occurrences(userId, status, completedAt)` | Aligned for completed-day pruning/counts. |
| User + schedule type/time and deadline/status | `cached_occurrences(userId, scheduleType, scheduledFor)` and `(userId, deadlineAt, status)` | Covers planner filters; flexible-bound predicates also use window columns and may need measurement at scale. |
| User + occurrence-history date range | `occurrence_history(userId, scheduledDate)` plus `(userId, kind, scheduledDate)` | Aligned for all-history and habit-range queries. |
| User + mutation state/retry/order | `pending_mutations(userId, state, nextAttemptAt, queueOrder)` | Aligned for due ordered mutation replay. |
| User + focus-session date | `focus_session_history(userId, startedAtEpochMillis)` | Aligned for the 7/30-day focus range. |
| User + daily review date/sync state | Primary key `(userId, reviewDate)` and `(userId, syncPending, reviewDate)` | Aligned for one-day review lookup and pending review replay. |
| User + pending habit-skip retry time | `(userId, queuedAt)` and unique `(userId, occurrenceId)`; due query filters `nextAttemptAt` and orders by `queuedAt` | Ordering/account scope and occurrence uniqueness are covered; retry-time filtering is not a leading indexed column. The queue is currently replayed in batches of at most 25, so no unmeasured index/migration was added. Revisit if offline queue volume warrants a Room migration. |

## Source changes made during this audit

- `android/app/src/main/java/dev/system/app/ui/VantaRoot.kt`: defer Google authorization client creation; reset account-specific goals and Focus UI state on owner changes.
- `android/app/src/main/java/dev/system/app/ui/calendar/GoogleCalendarAuthorizationLauncher.kt`: defer authorization client creation until a user action or its returned interaction requires it.
- `android/app/src/main/java/dev/system/app/ui/navigation/DashboardScreen.kt`: memoize task/goal search filtering by list and query.

## Runtime coverage still required

Gradle currently fails in `Selector.open()` before project configuration, so these source changes and Android migrations have not compiled or run here. Test the auth/account-switch/sign-out boundary, offline completion and reschedule replay, Calendar connect/disconnect and missing-Play-Services behavior, AI preview rejection/apply, habit skip replay, daily-review rollover, widget refresh, and notification settings on a working Android/Gradle host. No benchmark was run; the observations above are static, not measured performance results.
