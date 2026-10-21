# Batch 3 Parallel Integration Notes

This checkout has no `.git` metadata, so Batch 3 cannot use a dedicated Git branch/worktree or specialist commits here. Work is being kept modular with explicit file ownership. Batch 4 has active same-checkout Room work; its history/review/skip entities are now at schema version 7 with `MIGRATION_6_7`.

## Shared planner types

`android/app/src/main/java/dev/system/app/planner/PlannerModels.kt` is the shared pure-domain contract. It has no Compose or persistence dependency. Specialists should reuse these names and avoid parallel equivalents:

- `ScheduleType`: `HARD`, `SOFT`, or `FLEXIBLE`.
- `ScheduleSource`: Vanta task/habit, Google Calendar, or focus session.
- `ScheduleBlock`: a timed display interval plus scheduling metadata for deterministic movement suggestions; all-day source events are expanded to the planning zone's day bounds. Added `priority`, `deadlineAt`, `earliestStart`, `latestEnd`, and `preferredStart` as defaulted fields so existing constructors remain source-compatible.
- `FlexibleTask`: occurrence ID, duration, priority, deadline, and optional start/end preferences.
- `DaySchedule`, `FreeInterval`, `ScheduleConflict`, `CandidateSlot`, `PlanningContext`, and `PlanProposal`.
- A proposal is untrusted until a separate validator accepts it. `PlanPlacement` and `UnscheduledTask` carry only structured values.

## Ownership

| Workstream | Owned paths | Integration boundary |
|---|---|---|
| Google Calendar | New `calendar/` data/auth/cache files and `ui/calendar/`; Android dependencies only if needed | Do not edit `SystemDatabase`, `SystemRepository`, `VantaRoot`, or dashboard navigation. Lead wires the feature into the existing Room cache/navigation after review. Calendar tokens stay in the Google authorization client and memory only; never enter MCP or AI payloads. |
| Scheduling semantics | `planner/core/`, new forward Supabase migration, MCP task schemas/serialization | Must preserve old task write shapes and delta sync; add deterministic planner and SQL tests. Room/entity/repository edits are deferred to the lead while Batch 4's v7 work stabilizes. Coordinate the stable types in `planner/PlannerModels.kt`. |
| AI day planning | `planner/ai/`, new AI planning Edge Function/helper/tests, and `ui/planner/ai/` | Structured context/proposal only; locally/server validate IDs, time bounds, duration, deadlines and hard-block overlap. No writes until explicit preview approval. Do not edit MCP registration or core persistence files. |
| Conflict replanning | `planner/conflicts/` and `ui/planner/replan/` | Deterministic detection and proposal only; completed or past work remains unchanged. Use shared candidate/domain types. |
| Weekly planner | `ui/planner/week/` | New lazy UI receiving bounded date-range data via parameters; no DAO/repository or app-root edits. Lead adds the Week destination and supplies data/callbacks. |
| Lead integration | `VantaRoot.kt`, `DashboardScreen.kt`, `DashboardTab.kt`, `SystemDatabase.kt`, `SystemRepository.kt`, `SystemApplication.kt`, app-level wiring, final report | Integrates the isolated components, adds the additive Room v7→v8 migration without disturbing Batch 4's migration, performs security/performance review, and resolves any API or test gaps. |

## Shared constraints

- Keep calendar records separate from Vanta tasks and account-scoped in local storage.
- Keep planner algorithms independent of Compose; use `Instant` for intervals and carry `ZoneId`/`LocalDate` at planning boundaries.
- Preserve the existing offline completion and typed mutation replay behavior. Additive schema changes require a new SQL migration and Room migration.
- Do not change already-applied migrations. Keep all new database writes owner-scoped and idempotent.
- Calendar/AI are optional at startup and for Today rendering. AI placements require preview and explicit apply.
- The only shared model changes should be additive. Tell the lead before changing these signatures.
- Any Batch 3 Room change must chain from the active Batch 4 version 7 to version 8, preserve `MIGRATION_6_7` and its four Batch 4 entities, and be wired additively in `SystemApplication`. Do not edit Batch 4 feature records or repository behavior.

## Batch 4 handoff

Batch 4 should integrate after Batch 3's public schedule type and Room/schema APIs stabilize. In particular, reuse `ScheduleType`, `ScheduleBlock`, `PlanningContext`, and `PlanProposal`; preserve the additive Room migration sequence and Supabase occurrence projection used by delta sync. Do not add a second scheduling-type enum or reinterpret deadlines as scheduled starts.

## Scheduling persistence and API contract

The scheduling specialist finalized the Supabase fields and MCP payload names below. The lead should use the same names in Room v7→v8 and in any sync mutation payloads:

| Location | Field | Type / values | Meaning |
|---|---|---|---|
| `tasks` | `default_schedule_type` | `HARD`, `SOFT`, `FLEXIBLE` | Default inherited by generated occurrences. |
| `tasks` | `default_priority` | `LOW`, `NORMAL`, `HIGH` | Default inherited by generated occurrences. |
| `task_occurrences` | `schedule_type` | `HARD`, `SOFT`, `FLEXIBLE` | Per-occurrence placement semantics. |
| `task_occurrences` | `priority` | `LOW`, `NORMAL`, `HIGH` | Deterministic ranking input. |
| `task_occurrences` | `deadline_at` | nullable `timestamptz` | Completion deadline; independent of `scheduled_for`. |
| `task_occurrences` | `earliest_start` | nullable `timestamptz` | Lower placement bound. |
| `task_occurrences` | `latest_end` | nullable `timestamptz` | Upper placement bound for task end. |
| `task_occurrences` | `preferred_start` | nullable `timestamptz` | Soft ranking preference. |

Existing occurrence rows are backfilled `scheduled_for IS NOT NULL → SOFT`, otherwise `FLEXIBLE`; priority is `NORMAL`. Existing deadlines stay null and are never inferred from a start. Legacy `mcp_create_task` inputs remain callable; the new typed RPC is `mcp_create_task_with_scheduling`. `update_task` sends optional typed fields through `mcp_update_task_with_scheduling` as a `p_scheduling` JSON object; omitted keys are preserved and explicit null timestamp values clear that constraint. The Deno tool schema validates enums, RFC 3339 instants, and inverted earliest/latest bounds.

The scheduling migration is `supabase/migrations/20261021180000_vanta2_scheduling_semantics.sql`. The delta-sync migration is now `20261021185000_vanta2_delta_sync.sql`, ordered after the fields it projects. The earlier batch note recorded remote migration history through `20261021100000`; stabilization did not re-query remote history and relies on the supplied baseline that these migrations remain unapplied. The SQL body is unchanged.

For Room integration, use string ISO instants matching `CachedOccurrence.scheduledFor`; add `scheduleType` (migration default `SOFT`, backfill by `scheduledFor` to `FLEXIBLE` when null), `priority` (`NORMAL`), and nullable `deadlineAt`, `earliestStart`, `latestEnd`, `preferredStart`. Copy those fields through bootstrap, delta upsert, and typed offline mutation replay. Preserve the existing completion queue and per-user delta checkpoint ordering. Add Room indexes for owner/status/range lookups and a bounded repository API such as `observePlannerWeek(userId, startInclusive, endExclusive, flexibleLimit = 100): Flow<...>`. Its scheduled query should use `scheduledFor >= startInclusive AND scheduledFor < endExclusive`; its flexible query should require pending, unscheduled, `FLEXIBLE` rows whose deadline is in the week, whose placement window intersects the week, or whose window and deadline are both unconstrained, with unconstrained rows capped and ordered by priority/deadline. The pure mapper in `planner/core/CachedOccurrenceMapper.kt` accepts the scheduling metadata explicitly, maps scheduled tasks to `ScheduleBlock`, and maps unscheduled flexible tasks to `FlexibleTask` without using deadlines as starts. Completed and past blocks are marked read-only.

## Lead integration details

- `DashboardTab.Week` is inserted after Today. `WeeklyPlannerScreen` receives one bounded week from Room and a separate bounded calendar range; it renders only the selected day's blocks in its lazy detail list.
- `SystemApplication.googleCalendarRepository` is lazy. Calendar settings are embedded in the existing Settings screen through its optional `calendarSettings` slot. Authorization and refresh run outside composable rendering; tokens are passed in memory only to the Google REST client.
- `MIGRATION_7_8` adds fields to `cached_occurrences`, `bootstrap_occurrences`, and `pending_mutations` while retaining Batch 4's version-7 tables and `MIGRATION_6_7`. `CachedOccurrence` and `BootstrapOccurrence` schema defaults match the additive SQLite migration.
- `SystemRepository.applyReschedulePlan` re-reads owner-scoped rows, rejects HARD/completed tasks and stale durations/windows/deadlines, and writes approved local mutations in one Room transaction. The existing reschedule RPC trigger changes a newly scheduled FLEXIBLE item to SOFT during replay.
- AI proposals remain read-only until the user approves the preview. Android revalidates against a latest local snapshot; server validation re-reads authoritative task ownership and scheduling metadata.
- HARD blocks do not offer timeline drag or automatic conflict alternatives. A manually selected reschedule remains an explicit user action through the existing date picker and mutation queue.
- Focus Mode overrun notices appear only when later blocks overlap the delay. The user can request a reviewable remainder-of-day proposal or keep the existing schedule.
