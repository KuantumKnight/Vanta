-- Clients complete occurrences through the idempotent RPC. Direct inserts let
-- authenticated callers fabricate completed or skipped history.
revoke insert, update, delete on public.task_occurrences from public, anon, authenticated;
grant select on public.task_occurrences to authenticated;
