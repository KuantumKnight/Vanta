# Vanta 2 Batch 3 Report

Checked in the supplied workspace on 2026-10-21.

## Features

1. **Google Calendar.** Added Google Identity authorization for read-only calendar and event scopes, selectable calendars, a bounded local cache, explicit refresh, stale-cache refresh on foreground, disconnect, and read-only calendar blocks in Today and Week. The cache keeps seven past days and sixty future days. Recurring events are requested as Google-expanded instances. Calendar data lives in separate Room entities and never enters the Vanta task table.
2. **Hard, soft, and flexible scheduling.** Added the shared `ScheduleType` model, priority, independent deadline and placement-window fields, deterministic free-interval/candidate-slot functions, typed MCP scheduling arguments, an additive Supabase migration, and an additive Room v7→v8 migration. Existing scheduled occurrences backfill as SOFT; unscheduled occurrences backfill as FLEXIBLE. HARD blocks do not expose drag rescheduling and are rejected by automatic plan application.
3. **AI Plan My Day.** Added an authenticated `plan-day` Edge Function using strict structured output and canonical server-side task reads. It sends only compact busy intervals and required flexible-task fields. The AI response is validated on the server and again against the latest local schedule. The function never writes schedule data; Android requires preview and explicit apply.
4. **Conflict-aware replanning.** Added local conflict detection, candidate alternatives, and a remaining-day proposal for future unfinished SOFT and FLEXIBLE work. The user can review and apply the proposal through the existing local-first reschedule queue. Completed work, HARD blocks, past work, and read-only calendar blocks remain fixed. Late Focus Mode completion now offers a replan or keep-schedule choice when later blocks are affected.
5. **Weekly planner.** Added a Week destination with previous/current/next-week navigation, seven-day summaries, a lazy selected-day schedule, calendar blocks, completed-state styling, free-time summaries, and a flexible-task tray. Room loads at most 700 dated occurrences and 100 flexible occurrences for the selected week.

## Database Changes

- Added `20261021180000_vanta2_scheduling_semantics.sql` for task defaults, per-occurrence scheduling fields, validation constraints, indexes, triggers, and typed MCP create/update RPCs.
- Added a projection of schedule type, priority, deadline, and window fields to `20261021185000_vanta2_delta_sync.sql`. Stabilization moved this pending migration after scheduling semantics because its trigger reads scheduling columns created by `20261021180000_vanta2_scheduling_semantics.sql`; the SQL body is unchanged. No remote migration was applied.
- Existing `mcp_create_task` payloads remain valid. Extended scheduling inputs use `mcp_create_task_with_scheduling`; MCP validation rejects incompatible FLEXIBLE/scheduled-start combinations.

## Room Changes

- Added Room `MIGRATION_7_8` after Batch 4's `MIGRATION_6_7`. It adds scheduling metadata to cached, bootstrap, and pending-mutation rows, backfills scheduled rows to SOFT and unscheduled rows to FLEXIBLE, and adds indexes.
- Calendar catalogs, events, and sync state use a separate version-1 Room database. Calendar records are keyed by Vanta owner and opaque Google-account key; disconnect removes Google cache rows only.
- Added bounded planner-week observation and snapshot APIs, plus an atomic optimistic write for approved multi-item replans.

## Planner Architecture

- Pure scheduling models and algorithms live under `android/app/src/main/java/dev/system/app/planner/` and do not depend on Compose.
- `PlannerProjection` turns Room rows and separately cached calendar blocks into a day schedule and planning context. Deadlines never become scheduled starts.
- The deterministic engine clips busy intervals to planning bounds, creates candidate slots, and scores priority, deadline urgency, preferred time, and fragmentation. Scheduled SOFT blocks retain their deadline and placement bounds when conflict alternatives and replans are generated.
- Today and Week consume bounded Room and calendar ranges. The week surface uses lazy list rendering rather than a minute-by-minute grid.

## Calendar Architecture

- OAuth requests only `calendar.events.readonly` and `calendar.calendarlist.readonly`. Short-lived access tokens are held in memory and sent only to Google Calendar REST endpoints.
- The REST client requests only event IDs, titles, start/end, and calendar summaries. It asks Google to expand recurring events, handles pagination and time zones, and keeps all-day end dates exclusive.
- Cache refresh runs off the main thread. Today and Week render from Room after synchronization; Google blocks are read-only and visually distinct.
- Google OAuth consent-screen/API configuration and Android OAuth client setup are required for a real connected account.

## AI Safety and Validation

- The Edge Function authenticates the Supabase user, reloads each requested task through that user-scoped client, and checks owner, status, schedule type, duration, and task availability before calling the model.
- The model receives no OAuth token, notes, attendees, locations, conference data, raw calendar event IDs, or completed-task IDs. Requests use `store: false`.
- Both server and Android validation check IDs, duplicates, bounds, exact duration, task windows, deadlines, busy blocks, and collisions among proposed tasks. Apply re-reads the local week and queues the placements atomically.
- Configure `OPENAI_API_KEY` and `OPENAI_MODEL` as Edge Function secrets before deployment. Neither secret is stored in the Android app.

## Privacy

- Calendar tokens are neither persisted by Vanta nor sent to MCP or AI. Disconnect clears the local Google cache and attempts to revoke the read-only authorization.
- AI receives a compact schedule summary and canonical flexible-task details only. Calendar titles are included as busy labels; raw event metadata is not.
- Calendar cache ownership is local and user-scoped. Vanta tasks, completion queues, and offline mutations are separate.

## Performance

- Calendar storage is bounded to seven days in the past and sixty days in the future and is refreshed on user action or when stale at foreground; there is no polling loop.
- The weekly Room query caps dated/flexible rows, and its Compose surface renders one selected day's blocks in a lazy list.
- No device benchmark or end-to-end Android scenario could be run in this environment.

## Tests and Validation

- `deno test -A supabase/functions/_shared`: **34 passed, 0 failed**.
- `deno check functions/plan-day/index.ts functions/mcp/index.ts functions/expand-schedules/index.ts` from `supabase/`: **passed**. Added the OpenAI peer type mapping required by Supabase's Edge Runtime type declarations to `supabase/deno.json`.
- Targeted `deno fmt --check` for the day-planning and scheduling-validation files: **passed**. Repo-wide `deno fmt --check` reports 15 of 47 files unformatted, including unrelated existing files; those files were not reformatted as part of Batch 3.
- Android unit tests, lint, debug/release builds, and Android test assembly were attempted. Gradle failed before project configuration with `java.io.IOException: Unable to establish loopback connection`, so Kotlin/Room compilation and Android tests are unconfirmed. Added tests cover planner projection and constraint preservation, soft-task alternatives respecting deadlines/windows, and Google Calendar API pagination, recurrence expansion requests, timed/all-day parsing, timezone handling, empty calendars, and unauthorized responses.
- `supabase test db` could not run because the Supabase CLI is not installed and Docker Desktop's Linux engine is unavailable. The scheduling pgTAP test is authored but unexecuted; Docker-backed migration verification is still required.

## Known Limitations

- Google OAuth credentials/consent configuration and the deployed Supabase `OPENAI_API_KEY`/`OPENAI_MODEL` secrets are external setup steps.
- Supabase migrations have not been applied. Migration and pgTAP behavior is therefore not confirmed against a running database.
- Android build and tests remain unconfirmed because Gradle could not establish its loopback connection. No Android device was available for the end-to-end scenarios.
- The workspace has no `.git` metadata, so a dedicated Batch 3 branch/worktree, commits, and a Git diff could not be created here.

## Integration Notes

See [PARALLEL_INTEGRATION_NOTES_BATCH3.md](PARALLEL_INTEGRATION_NOTES_BATCH3.md) for shared APIs, Batch 4 handoff details, migration ordering, and the current integration seams.
