# Vanta 2 Batch 4 Report

Checked in the supplied workspace on 2026-10-21.

## Features

1. **Habit System V2.** Added a Habits destination, per-habit detail, a weekly view aligned to the selected week start, and a 28-day heatmap backed by a bounded 365-day occurrence query. Streaks use recorded expected occurrences. Past pending occurrences break a run, explicit skips are neutral, and a pending occurrence for today remains in progress. Weekly/non-daily habits count expected occurrences rather than unscheduled calendar days.
2. **Morning briefing.** Added a deterministic local summary card with scheduled item count, distinct habit count, planned duration when every timed item has a duration, first item, and high-priority task count when priority data is available. No count is invented when it is absent.
3. **Evening review.** Added a daily summary for planned, completed, remaining, skipped, and recorded focus time. Each remaining occurrence can be moved to tomorrow, kept at its existing deadline, or rescheduled; only habit occurrences can be skipped. Review notes are optional, limited to 4,000 characters, saved in Room first, and replayed to an owner-scoped Supabase table. Rollover uses the existing offline reschedule queue.
4. **Planned vs actual analytics.** Added 7-day and 30-day summaries for task completion, planned task duration, recorded Focus Mode session time, habit completion, and planned-versus-recorded time. Aggregation runs locally over bounded history. Estimation guidance appears only after three tasks have planned duration and recorded focus.
5. **Settings and personalization.** Added typed Preferences DataStore for planning bounds, default duration, timeline snap interval, week start, daily notification times, task-reminder preference, theme, and analytics display choice. Defaults include 07:00–23:00, 30 minutes, 15 minutes, Monday, and disabled morning/evening notifications. Reset affects Vanta preferences only. Theme and timeline choices update live.

## Schema Changes

- Added `20261021170000_vanta2_daily_reviews.sql` with an owner-keyed `daily_reviews` table, a validated save RPC, and an owner-scoped bounded occurrence-history RPC.
- Added `20261021190000_vanta2_task_reminder_preference.sql` with a per-device `task_reminders_enabled` column, an owner-checked update RPC, and a due-reminder claim filter that ignores opted-out devices.
- Existing Supabase migrations were not edited.

## Room Changes

- Added migration `MIGRATION_6_7` for occurrence history, local Focus Mode history, daily reviews, and a durable habit-skip outbox. History and focus queries use user/date indexes; skip replay uses a user/queue index and a unique user/occurrence index.
- Kept Batch 3's `MIGRATION_7_8` in the chain; the current database version remains 8.
- Completed rows are copied into local history before the existing cache pruning removes them. Daily reviews and skipped occurrences are stored locally before network sync.

## Notification Architecture

- Morning and evening reminders use unique, one-time WorkManager requests. The next wall-clock trigger is recalculated after each delivery, preference update, timezone change, date change, or clock change. No exact alarm permission is used.
- Notifications use generic lock-screen-safe copy and explicit actions to Today or Evening Review. Permission and app notification settings are checked before display.
- Task reminder preferences are stored in DataStore, synchronized for the current FCM device token, and applied server-side before due reminders are claimed. App-delivered reminder messages also check the local preference.

## Habit and Streak Semantics

- One streak unit is one expected occurrence. Daily habits therefore advance across consecutive expected dates; weekly and other non-daily habits advance across consecutive expected occurrences.
- Completed expected occurrences extend a run. A past pending occurrence breaks it. A pending occurrence on the current local date is still in progress. Explicit skips neither extend nor break a run.
- Current and best streaks are computed from occurrence history, never a mutable counter. The screen requests the last 365 days, so a streak extending farther back can be under-reported. Unrepresented recurrence dates are not synthesized.

## Analytics Definitions

- **Planned tasks and minutes:** dated task occurrences in the selected window, excluding skipped tasks; only valid stored durations contribute minutes.
- **Completed tasks and completion rate:** completed task occurrences divided by planned task occurrences. The rate is absent when no tasks were planned.
- **Focused time:** elapsed time between saved Focus Mode start and finish timestamps, grouped by the local date on which the session started. Days without finished sessions have no measured value.
- **Habit completion:** completed expected habit occurrences divided by recorded expected occurrences. Skipped occurrences are excluded from the task denominator and remain visible in habit history as neutral.
- **Estimation difference:** focused minutes minus planned minutes for tasks having both values, divided by planned minutes. It is shown only after three such tasks.

## Preferences Architecture

- `UserPreferencesRepository` exposes a typed DataStore-backed `Flow`, update operation, and preference-only reset. Authentication identity, credentials, tasks, and sync state stay in their existing stores.
- Planning window, duration, snap interval, and week start feed the existing UI. Theme and analytics display changes are reactive. Morning and evening reminders are off until enabled.

## Performance

- Habit history is limited to 365 days; analytics reads 7 or 30 days; daily review reads one date. Remote occurrence backfill is owner-scoped and capped at 5,000 rows per load, in pages of 500.
- Room reads use date/user indexes. Feature projection and analytics aggregation run on `Dispatchers.Default`; no whole-history observation or chart dependency was added.
- No emulator/device benchmark was available.

## Privacy

- Analytics are derived on-device from Vanta occurrence and Focus Mode records. No third-party analytics or advertising SDK was added, and no analytics are sent externally.
- Review notes are keyed by user and review date, protected by RLS, saved through a user-derived RPC, and capped at 4,000 characters.
- Notifications do not expose task titles or review notes on the lock screen. Device reminder settings can only update a token owned by the signed-in user.

## Tests and Validation

- `deno test -A supabase/functions/_shared`: **34 passed, 0 failed**.
- `deno check supabase/functions/plan-day/index.ts`: **passed**.
- Added Android unit tests for streak semantics, analytics aggregation, notification timing/deep links, dashboard projections, preference validation/persistence, and briefing summaries. Added Supabase pgTAP tests for review ownership/history bounds and per-device task reminder settings.
- The requested Android test, lint, debug/release assembly, and Android test assembly tasks were attempted together. Gradle failed before project configuration with `java.io.IOException: Unable to establish loopback connection`, including the `--no-daemon` and IPv4-preference retry. Android tests are therefore unconfirmed.
- Supabase pgTAP tests could not run because the Supabase CLI is not installed and Docker Desktop's Linux engine is unavailable. The new SQL migrations have not been applied to a database.

## Known Limitations

- The provided directory has no `.git` metadata and no `docs/VANTA2_ARCHITECTURE_CONTRACT.md`. A branch/worktree, Git status, and commits could not be created or verified here.
- Habit current/best streaks use the available 365-day window and can under-report longer runs. The projection counts recorded expected occurrences; it does not infer unmaterialized dates from arbitrary RFC5545 rules.
- Focus analytics measure saved Focus Mode elapsed time, not device activity or attention. Estimation metrics are hidden until the minimum sample count is met.
- Review choices to keep a deadline are temporary UI decisions. Reschedules, skips, review notes, and completion markers use durable local storage and sync paths.
- No device/emulator manual scenarios or performance benchmark were available.
