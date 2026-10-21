# Batch 4 Parallel Integration Notes

## Shared workspace status

- `docs/VANTA2_ARCHITECTURE_CONTRACT.md` is absent.
- The supplied folder has no `.git` directory. A dedicated branch/worktree and Git diff could not be used.
- The workspace contains Batch 3 planner/calendar work and its Room v7→v8 migration. Batch 4 left those feature sources, planner semantics, and `20261021180000_vanta2_scheduling_semantics.sql` unchanged.
- Batch 4 added its Room v6→v7 migration; the existing v7→v8 migration is still registered after it. Room remains at version 8.

## Shared seams changed additively

| Seam | Batch 4 addition | Compatibility boundary |
|---|---|---|
| `SystemDatabase` | v6→v7 history, local focus, review, and habit-skip tables | Existing v7→v8 scheduling migration remains in sequence; existing tables and queues are retained. |
| `SystemRepository` | Bounded occurrence/focus history reads; local-first review save; habit-skip replay; reminder preference sync | Existing completion queue, delta sync, reschedule queue, and scheduling fields remain intact. |
| `SystemApplication` | DataStore initialization and daily-notification rescheduling | Existing calendar initialization, migrations, and sync worker setup remain intact. |
| `VantaRoot` / dashboard | Supplies bounded feature models and callbacks, theme/preferences, and notification destinations | No planner scheduling algorithm, `DaySchedule`, or `ScheduleBlock` contract was introduced or renamed. |
| Today timeline | Adds optional day-window and snap-interval parameters populated from user preferences | Existing defaults are retained; task placement and scheduling semantics are not changed. |

## Batch 4 contracts

- `UserPreferencesRepository.preferences` is a typed Flow; `update` and `reset` own preference persistence. Account identity and task data are separate.
- `SettingsScreen` accepts an optional composable `calendarSettings` slot. Batch 4 does not import or require Batch 3 calendar settings.
- Habit rows are grouped by existing task IDs and use `kind = habit`. Streaks are based on bounded occurrence records, not mutable counters. Explicit skips are neutral; past pending occurrences break runs; today’s pending occurrence is still open. Streak units are expected occurrences so weekly gaps are not treated as misses.
- `occurrenceHistoryForRange(start, end, kind?)` is inclusive, owner-scoped, Room-first, and capped at 5,000 remote backfill rows. Analytics uses 7- or 30-day windows; habit screens request 365 days.
- `recordFocusSession` stores existing Focus Mode sessions locally by user. No external analytics service or new tracking SDK is added.
- Daily review notes are keyed by user and local review date, stored in Room before `system_save_daily_review`, and synced through a user-derived security-definer RPC. Occurrence history uses a security-invoker RPC with owner filtering and inclusive date bounds.
- Habit skips use the existing idempotent `mcp_skip_habit_occurrence` operation behind a Room outbox. Rollover uses the existing occurrence reschedule mutation.
- Morning/evening reminders are opt-in, scheduled as unique WorkManager work, use generic notification copy, and open Today or Evening Review. Reminder wall-clock times use the device timezone. Task-reminder preference is stored locally and synced for the current device token; the server filters disabled tokens before claiming reminders.

## Data and semantic limits

- Habit streak/history loads are bounded to 365 days. Values beyond that range may be under-reported; they are not inferred.
- The recurrence API supplies occurrences rather than an RFC5545 rule to the habit projection. Only recorded expected occurrences are counted; unrepresented dates are not synthesized.
- Recorded focus time is elapsed time between Focus Mode start and finish timestamps. It does not measure attention or foreground activity.
- Review choices to keep a deadline are temporary UI decisions; task reschedules and habit skips use durable existing offline queues. The optional note and completion marker are durable and owner-scoped.
- Daily review notes are limited to 4,000 characters in both Room-side validation and the Supabase RPC.

## Verification status

The Deno shared regression suite passed (34 tests), and `plan-day/index.ts` passed `deno check`. Android tests and assembly tasks were attempted but Gradle failed before project configuration with a loopback connection error. Supabase tests were authored but not run because the local CLI and Docker engine are unavailable. See [VANTA2_BATCH4_REPORT.md](VANTA2_BATCH4_REPORT.md) for the complete validation record and limits.
