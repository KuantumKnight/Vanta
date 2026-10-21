# Vanta 2 Batch 2

## Completed Features

1. **Timeline drag and reschedule.** Scheduled pending tasks can be long-pressed and dragged vertically. The preview snaps to 15-minute slots by default, shows overlap feedback, and commits only on drop. An accessible **Reschedule** action opens a time picker. Rescheduling changes one occurrence, preserves its duration, and does not edit the recurrence schedule or move other tasks.
2. **Quick Capture.** A global add button opens a structured sheet for title, optional notes, date, start time, optional duration, and all-day scheduling. Unscheduled tasks are supported. Widget launches pass their source and date into the sheet. The optional parser recognizes unambiguous today/tomorrow, clock-time, and duration patterns; the fields remain authoritative. Duration stays nullable to preserve the Batch 1 model.
3. **NOW / NEXT and TODAY COMPACT widgets.** Two Glance providers render a locally persisted day projection. Room supplies a bounded set of at most 128 rows for the active/today window and next future occurrence, plus exact today counts and planned minutes. The displayed Today list is capped at 12 rows. Widgets refresh after local mutations and sync changes, and a one-time worker refreshes at the next task or day boundary. Completion uses the existing offline completion queue.
4. **Focus Mode.** A focused task opens an isolated countdown screen. The timer derives remaining time from timestamps, pauses UI ticks while the app is backgrounded, and uses the task duration or a 25-minute default. Sessions are persisted locally per user. Completing from Focus routes through normal occurrence completion. A durable Room outbox reconciles focus sessions when an offline-created task receives its server ID.
5. **Incremental synchronization.** New owner-scoped delta RPCs return ordered occurrence projections or tombstones. Per-user revisions are serialized by a locked revision row. Initial bootstrap stages pending and same-day completed rows and swaps them into Room with a baseline checkpoint; steady-state sync fetches deltas. Each page and checkpoint update is applied in one Room transaction. Client-side contiguous-revision checks reject gaps and duplicates.

## Architecture

- `VantaRoot` integrates the repository callbacks, Focus Mode, session recovery, and widget-launched Quick Capture. `MainActivity` translates the widget intent contract into a one-shot capture request.
- Timeline gesture state stays local to the dragged row. The repository is called once after a valid drop or accessible time selection.
- Quick Capture uses typed fields and a small deterministic parser. Creation is handled by the repository and appears from Room immediately.
- Glance reads `WidgetDayStateStore`; it does not query Room while rendering. A Room refresh query builds the bounded projection and computes exact day aggregates.
- Focus state remains local to the installation. Its user-specific preference key is hashed; occurrence ID reconciliation uses a durable Room outbox so process death cannot lose the mapping.
- A single typed Room mutation queue handles `CREATE` and `RESCHEDULE`. The separate completion queue retains its existing replay and dead-letter behavior.

## New Migrations

- `supabase/migrations/20261021185000_vanta2_delta_sync.sql` adds private per-user revision and occurrence-change tables, trigger functions, and authenticated revision/delta RPCs. Definer functions use an empty `search_path`; direct access to private log tables is revoked. Stabilization ordered it after the scheduling-semantics migration because its trigger reads the new scheduling columns.
- `supabase/migrations/20261021185500_vanta2_reschedule_occurrence.sql` adds an owner-scoped, idempotent RPC for rescheduling one pending occurrence. It preserves duration and recurrence semantics and returns the resulting revision; stabilization ordered it after delta sync because it reads the sync state and trigger/helper.
- Existing migration files were left unchanged.
- The new migrations were not applied to a local or production database in this environment.

## Room Changes

Room advances from v5 to v6 through `MIGRATION_5_6`. It adds typed `PendingMutation`, per-user `SyncCheckpoint`, restart-safe bootstrap staging, and `PendingFocusReconciliation` entities and their indexes. It adds an index for completed-today widget queries. Existing cached occurrences and completion rows are retained during migration. `SystemApplication` registers the migration.

## Offline Mutation System

Create and reschedule operations share a durable typed queue with stable operation UUIDs, user ownership, queue order, retry metadata, and typed payload columns. A new task first appears with a `local:<operation UUID>` ID, which cannot collide with a server UUID. Server replay uses the same operation ID. Reconciliation rewrites queued references and protects optimistic rows from incoming deltas until the mutation is resolved. Same-occurrence mutations replay in order; a delayed earlier operation holds later operations for that occurrence while unrelated work can continue.

The existing completion queue remains separate. A queued completion waits until a preceding create or reschedule is reconciled, then replays through the existing idempotent completion RPC.

## Delta Sync Protocol

The server trigger writes complete projections or tombstones in the same transaction as occurrence/task changes. Revision allocation locks the per-user state row, preserving committed order under concurrent writes. The delta RPC derives the owner from `auth.uid()` and does not expose the private change log.

On bootstrap, Android captures the current revision, stages keyset-paged pending and same-day completed rows, then atomically merges the stage and saves the checkpoint. Later passes request changes after the saved revision. Delta pages are validated for contiguous, nonduplicate revisions before application. Pending local mutations and completions keep their optimistic projection while the checkpoint advances.

FCM `refresh` messages are coalesced sync hints. The periodic WorkManager fallback now runs every 24 hours. Device-token registration checks the saved user/token before making the registration RPC.

## Widget Architecture

`WidgetDayState` is persisted separately from Room rows and contains NOW, NEXT, up to 12 Today entries, exact day totals, the next refresh boundary, and a sync warning. The Room projection is capped at 128 rows and includes a still-active previous-day occurrence, today’s scheduled rows, and the next future occurrence. Aggregate count/minute queries keep displayed totals correct even when the row projection reaches its cap. Glance provider render paths only read the persisted projection. WorkManager schedules a single next-boundary refresh and repository changes trigger refreshes only when a widget instance exists.

## Focus Session Architecture

`FocusSession` stores a start timestamp and planned duration; the countdown does not accumulate timer ticks as its source of truth. The UI updates only its countdown leaf while resumed. Session state is stored per user in app-private preferences. A v5→v6 Room outbox makes the local-to-server occurrence ID change retry-safe and user-scoped.

## Performance

- Timeline drag uses transient Compose state and performs no Room write or network call until drop. The drag preview is confined to the affected row.
- Focus countdown ticks once per second only while resumed and does not recompose the dashboard.
- Widget rendering reads a small persisted projection; Room refreshes use matching user/status/date indexes and are bounded to 128 rows plus aggregate queries.
- Ordinary post-bootstrap synchronization uses delta pages instead of downloading and replacing the pending-occurrence snapshot. Periodic recovery is 24 hours.
- No startup, drag, Room, network-volume, or benchmark measurements were collected. No performance numbers are claimed.

## Security Validation

- Create uses the existing authenticated task-creation RPC and a user-scoped local queue.
- Reschedule checks `auth.uid()`, verifies the occurrence and parent task belong to that user, and uses a per-user idempotency key. Foreign and unavailable occurrences share the same not-found response.
- Delta RPCs derive the user from `auth.uid()`; private tables and helper functions are not granted to client roles.
- Local occurrences, mutation rows, checkpoints, and focus sessions are keyed by user. Account switching filters local projections by the active account.
- SQL tests cover private-log access, owner isolation, reschedule ownership, duration preservation, recurrence isolation, and idempotent replay. They were authored but could not be executed in this environment; see validation below.

## Automated Tests

- Deno regression suite: **19 passed, 0 failed**.
- Deno checks for MCP, schedule expansion, push, and reminder Edge Function entry points: **passed**.
- Consent site regression checks: `npm run build` and `npm run lint` **passed**.
- Android unit tests, lint, debug/release assembly, and Android test assembly were attempted together. Gradle failed before project configuration with `java.io.IOException: Unable to establish loopback connection`; retrying with `--no-daemon` and IPv4 preference produced the same failure.
- Supabase pgTAP was attempted with `npx supabase test db`, but the local database refused `127.0.0.1:54322` connections. Docker/local Supabase was unavailable.
- New Android tests cover timeline snapping/overlap, Quick Capture parsing/validation, Focus session persistence and ID reconciliation, Room v5→v6 migration, mutation persistence/overlay behavior, and bounded widget snapshots/totals. They are not reported as passing because Gradle could not start.

## Device Tests

No Android device or emulator was attached (`adb devices -l` returned an empty device list). The required online/offline reschedule, offline create/restart/replay, Focus background/restore/completion, widget timing/completion, and remote delta scenarios were not manually or instrumentally verified.

## Known Limitations

- Focus sessions are local to one installation and are not synchronized between devices.
- The widget row projection is capped at 128 rows, and the Today list displays at most 12; aggregate totals remain exact.
- Change-log retention/compaction is not included, so server-side change rows need a future retention policy that preserves clients with stale checkpoints.
- Individual mutation conflict review or dismissal is not exposed as a dedicated UI; sync status surfaces retry/failure state while optimistic data remains local.
- This workspace has no `.git` metadata, so the requested commit structure, branch/worktree isolation, and commit creation were unavailable.

## Deferred Work

Automatic conflict resolution, cascading, drag-to-resize, recurring schedule editing, broader planner features, multi-device Focus synchronization, and a full mutation recovery screen remain outside Batch 2.

## Recommended Batch 3

1. Run the new migrations and pgTAP suite against a disposable local Supabase stack, then validate the migration against a copy of the deployed schema.
2. Execute the online/offline acceptance scenarios on a device, including widget sizes, process death, account changes, and token refresh.
3. Add and benchmark change-log retention/bootstrap recovery and Room/widget behavior with large local datasets.
4. Add a user-facing mutation review/retry resolution path for persistent server conflicts.
