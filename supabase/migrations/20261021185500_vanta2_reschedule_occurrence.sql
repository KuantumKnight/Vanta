-- Idempotent, owner-scoped rescheduling for one pending occurrence. A recurrence
-- is not changed: only the selected task_occurrences row is updated. The delta
-- migration installed the row trigger that records this update and its revision.

create table private.system_reschedule_requests (
  user_id uuid not null references auth.users(id) on delete cascade,
  operation_id uuid not null,
  occurrence_id uuid not null,
  scheduled_for timestamptz not null,
  result jsonb,
  created_at timestamptz not null default now(),
  primary key (user_id, operation_id)
);

revoke all on private.system_reschedule_requests from public, anon, authenticated;

create or replace function public.system_reschedule_occurrence(
  p_occurrence_id uuid,
  p_scheduled_for timestamptz,
  p_operation_id uuid
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  prior_request private.system_reschedule_requests;
  updated_occurrence public.task_occurrences;
  occurrence_task public.tasks;
  current_revision bigint;
  result_payload jsonb;
begin
  if current_user_id is null then
    raise exception 'Authentication required' using errcode = '28000', detail = 'AUTH_REQUIRED';
  end if;
  if p_occurrence_id is null or p_scheduled_for is null or p_operation_id is null then
    raise exception 'Invalid reschedule request' using errcode = '22023', detail = 'INVALID_MUTATION';
  end if;

  -- A previously committed operation is replayed exactly, even if the task was
  -- completed or its parent task was archived after that operation succeeded.
  select * into prior_request
  from private.system_reschedule_requests
  where user_id = current_user_id and operation_id = p_operation_id
  for update;
  if found then
    if prior_request.occurrence_id <> p_occurrence_id
       or prior_request.scheduled_for is distinct from p_scheduled_for then
      raise exception 'Idempotency key was already used' using errcode = '22023', detail = 'IDEMPOTENCY_CONFLICT';
    end if;
    if prior_request.result is null then
      raise exception 'Reschedule operation is incomplete' using errcode = '40001', detail = 'RETRY_MUTATION';
    end if;
    return prior_request.result;
  end if;

  -- Lock both owner rows. Missing, foreign, archived, completed and skipped
  -- occurrences share the same error so this RPC does not disclose ownership.
  select o.* into updated_occurrence
  from public.task_occurrences o
  join public.tasks t on t.id = o.task_id and t.user_id = o.user_id
  where o.id = p_occurrence_id
    and o.user_id = current_user_id
    and o.status = 'pending'
    and t.archived_at is null
  for update of o, t;
  if not found then
    raise exception 'Task occurrence is no longer available' using errcode = 'P0002', detail = 'NOT_FOUND';
  end if;

  -- Reserve the owner-scoped idempotency key. A concurrent request using the
  -- same key is serialized by this unique key and then checked for exact reuse.
  insert into private.system_reschedule_requests(user_id, operation_id, occurrence_id, scheduled_for)
  values (current_user_id, p_operation_id, p_occurrence_id, p_scheduled_for)
  on conflict (user_id, operation_id) do nothing;
  select * into prior_request
  from private.system_reschedule_requests
  where user_id = current_user_id and operation_id = p_operation_id
  for update;
  if prior_request.occurrence_id <> p_occurrence_id
     or prior_request.scheduled_for is distinct from p_scheduled_for then
    raise exception 'Idempotency key was already used' using errcode = '22023', detail = 'IDEMPOTENCY_CONFLICT';
  end if;
  if prior_request.result is not null then
    return prior_request.result;
  end if;

  if updated_occurrence.scheduled_for is distinct from p_scheduled_for then
    update public.task_occurrences
    set scheduled_for = p_scheduled_for
    where id = p_occurrence_id and user_id = current_user_id
    returning * into updated_occurrence;
  end if;

  select * into occurrence_task
  from public.tasks
  where id = updated_occurrence.task_id and user_id = current_user_id;
  select revision into current_revision
  from private.system_sync_state
  where user_id = current_user_id;
  if current_revision is null then
    raise exception 'Sync revision is unavailable' using errcode = '40001', detail = 'RETRY_MUTATION';
  end if;

  result_payload := jsonb_build_object(
    'occurrence', jsonb_build_object(
      'id', updated_occurrence.id,
      'task_id', updated_occurrence.task_id,
      'title', occurrence_task.title,
      'notes', occurrence_task.notes,
      'kind', occurrence_task.kind,
      'scheduled_for', updated_occurrence.scheduled_for,
      'all_day', updated_occurrence.all_day,
      'duration_minutes', updated_occurrence.duration_minutes,
      'status', updated_occurrence.status,
      'completed_at', updated_occurrence.completed_at
    ),
    'revision', current_revision
  );

  update private.system_reschedule_requests
  set result = result_payload
  where user_id = current_user_id and operation_id = p_operation_id;
  return result_payload;
end;
$$;

revoke all on function public.system_reschedule_occurrence(uuid, timestamptz, uuid) from public, anon;
grant execute on function public.system_reschedule_occurrence(uuid, timestamptz, uuid) to authenticated;
