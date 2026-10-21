# Vanta 2 Batch 2 Integration Plan

## Verified baseline

The current code has Batch 1 branding, extracted Compose screens, duration-aware Room v5, Today Timeline, and Android benchmark scaffolding. Sync still pages every pending occurrence and replaces the Room snapshot. Baseline checks are recorded in `docs/VANTA2_BATCH2_BASELINE.md`.

## Conflict matrix

| Owner | Primary files | Shared-file boundary |
|---|---|---|
| Agent 1 — reschedule | `ui/today/timeline/**`, new time-slot helper/tests, a dedicated forward SQL migration and pgTAP test | Does not edit `SystemRepository`, `SystemDatabase`, `CachedOccurrence`, `VantaRoot`, or widget code. Exposes a callback contract for lead integration. Its migration follows Agent 5's delta migration so reschedule writes are covered by the change log. |
| Agent 2 — Quick Capture | New `ui/quickcapture/**`, parser/domain tests, `ui/navigation/DashboardScreen.kt` | Does not edit repository/database/sync files or `VantaRoot`; emits a structured create callback. |
| Agent 3 — widgets | `widget/**`, dedicated widget resources/provider declarations, state calculation/store/tests | Does not edit Room/repository files or `SystemApplication`; preserves the current `SystemWidget().updateAll(context)` integration surface. |
| Agent 4 — Focus | New `ui/focus/**`, local session store/domain/tests | Uses isolated SharedPreferences-backed focus persistence; does not edit Room/repository, `VantaRoot`, or timeline files. |
| Agent 5 — delta sync | `SystemDatabase.kt`, `CachedOccurrence.kt`, `SystemRepository.kt`, `SystemApplication.kt`, sync/FCM files, and a separate delta migration/tests | Owns shared Room and synchronization integration. Coordinates the typed mutation API with Agents 1 and 2 before implementation. |
| Lead | `VantaRoot.kt`, final wiring, report, integration fixes | Integrates callback boundaries after specialist components are ready. |

No Git metadata exists in this workspace, so specialists use this ownership matrix in the shared checkout instead of worktrees or commits.

## Shared offline mutation contract

Use one durable, typed Room mutation queue for task creation and schedule changes. Keep the existing completion queue because completion has its own replay and dead-letter behavior.

- Repository API: `createTask(title, notes, scheduledFor, allDay, durationMinutes)` and `rescheduleOccurrence(occurrenceId, scheduledFor)` persist the local projection and mutation before returning. UI callbacks remain non-blocking and run these methods from the existing coroutine scope.
- A local create uses `local:<idempotency UUID>` as its temporary occurrence ID; server IDs remain UUIDs, so the namespaces cannot collide. The same immutable operation UUID is used on every retry.
- The Room mutation row stores typed operation fields, owner ID, creation order, retry state, and idempotency key. It must not serialize arbitrary JSON or reuse the completion table.
- Server updates received while a row has a pending local mutation must not overwrite its visible optimistic projection. Successful replay atomically reconciles the canonical server row/ID, rewrites any queued completion reference, stores the returned revision/checkpoint, and removes the mutation.
- Create and reschedule are owner-scoped and idempotent. A generated recurring occurrence reschedule changes that occurrence only; schedule editing remains a separate operation.
- Agent 5 owns the final Room/API shape and will publish it before Agents 1 and 2 integrate repository calls. The lead owns final callback wiring.
- Current proposal: Room v6 adds typed `PendingMutation`, per-user `SyncCheckpoint`, and restart-safe bootstrap staging. Existing `PendingCompletion` remains independent. Local creates use `local:<operation UUID>` and reconciliation rewrites the occurrence and queued completion references atomically.

## Delta-sync contract

The server contract must be owner-scoped, ordered, idempotent, deletion-aware, and safe under concurrent commits. Agent 5's design uses a locked per-user revision row and transactional change log (not a bare sequence), an owner-only delta RPC, full occurrence projections/tombstones, and UUID-keyset bootstrap staging captured at a baseline revision. The Android client applies each fetched page and advances its per-user checkpoint in one Room transaction. Mutations with a local overlay are not overwritten by incoming deltas; replay drains through its reported revision while the overlay remains, then reconciles the canonical row and removes the queue item atomically. Stabilization ordered the delta migration as `20261021185000_vanta2_delta_sync.sql` after scheduling semantics, and reschedule as `20261021185500_vanta2_reschedule_occurrence.sql` after delta sync to satisfy SQL dependencies.

FCM is a coalesced sync hint. Periodic work remains a stale-state recovery path. No normal render depends on a network request.
