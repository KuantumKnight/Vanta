-- Clients complete occurrences through the idempotent RPC. Direct inserts let
-- authenticated callers fabricate completed or skipped history.
revoke insert, update, delete on public.task_occurrences from public, anon, authenticated;
grant select on public.task_occurrences to authenticated;

-- MCP expands the RRULE in the Edge Function, then submits only generated
-- timestamps. Ownership and all row identity fields are derived here from the
-- authenticated caller and the owned schedule; callers cannot choose user_id,
-- task_id, status, or schedule metadata.
create or replace function public.system_materialize_schedule_occurrences(
  p_schedule_id uuid,
  p_scheduled_for timestamptz[]
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_task_id uuid;
  v_timezone text;
  v_dtstart timestamptz;
  v_effective_from timestamptz;
  v_effective_until timestamptz;
  v_reminder_time time;
  v_now timestamptz := statement_timestamp();
  v_input_count integer;
  v_distinct_count integer;
  v_inserted integer;
begin
  if v_user_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;
  if p_schedule_id is null or p_scheduled_for is null or cardinality(p_scheduled_for) > 12000 then
    raise exception 'Invalid schedule materialization request' using errcode = '22023';
  end if;

  select s.task_id, s.timezone, s.dtstart, s.effective_from, s.effective_until, s.reminder_time
    into v_task_id, v_timezone, v_dtstart, v_effective_from, v_effective_until, v_reminder_time
  from public.habit_schedules as s
  join public.tasks as t on t.id = s.task_id and t.user_id = s.user_id
  where s.id = p_schedule_id
    and s.user_id = v_user_id
    and t.user_id = v_user_id
    and t.archived_at is null
    and s.effective_until is null
  for share of s, t;

  if not found then
    raise exception 'Schedule not found for current user' using errcode = '42501';
  end if;

  select count(*), count(distinct value)
    into v_input_count, v_distinct_count
  from unnest(p_scheduled_for) as timestamps(value);
  if v_input_count <> v_distinct_count
      or exists (select 1 from unnest(p_scheduled_for) as timestamps(value) where value is null)
  then
    raise exception 'Occurrence timestamps must be unique and non-null' using errcode = '22023';
  end if;

  if exists (
    select 1
    from unnest(p_scheduled_for) as timestamps(value)
    where value < greatest(v_dtstart, v_effective_from)
       or (v_effective_until is not null and value >= v_effective_until)
       or value < v_now - interval '1 day'
       or value > v_now + interval '120 days'
  ) then
    raise exception 'Occurrence timestamp is outside the active schedule window' using errcode = '22023';
  end if;

  insert into public.task_occurrences(task_id, user_id, schedule_id, scheduled_for, all_day, reminder_at)
  select
    v_task_id,
    v_user_id,
    p_schedule_id,
    generated.scheduled_for,
    false,
    case
      when v_reminder_time is null then null
      when ((generated.scheduled_for at time zone v_timezone)::date + v_reminder_time)
          at time zone v_timezone > v_now
        then ((generated.scheduled_for at time zone v_timezone)::date + v_reminder_time)
          at time zone v_timezone
      else null
    end
  from unnest(p_scheduled_for) as generated(scheduled_for)
  on conflict (schedule_id, scheduled_for) do nothing;

  get diagnostics v_inserted = row_count;
  return v_inserted;
end;
$$;

revoke all on function public.system_materialize_schedule_occurrences(uuid, timestamptz[]) from public, anon;
grant execute on function public.system_materialize_schedule_occurrences(uuid, timestamptz[]) to authenticated;

-- A queued Android completion may be replayed after the server committed but
-- the client died before removing the queue row. A completed owner occurrence
-- is therefore a successful result even when another client completed it
-- first. The existing idempotency key still cannot be reused for a different
-- occurrence, and skipped or missing occurrences remain explicit conflicts.
create or replace function public.complete_task_occurrence(p_occurrence_id uuid, p_idempotency_key uuid)
returns public.task_occurrences
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_previous_occurrence_id uuid;
  v_occurrence public.task_occurrences;
begin
  if v_user_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;

  insert into public.completion_requests(user_id, idempotency_key, occurrence_id)
  values (v_user_id, p_idempotency_key, null)
  on conflict (user_id, idempotency_key) do nothing;

  select occurrence_id into v_previous_occurrence_id
  from public.completion_requests
  where user_id = v_user_id and idempotency_key = p_idempotency_key;
  if v_previous_occurrence_id is not null and v_previous_occurrence_id <> p_occurrence_id then
    raise exception 'Idempotency key was already used for another occurrence' using errcode = '22023';
  end if;

  select * into v_occurrence
  from public.task_occurrences
  where id = p_occurrence_id and user_id = v_user_id
  for update;
  if not found then
    raise exception 'Task occurrence not found' using errcode = 'P0002';
  end if;
  if v_occurrence.status = 'skipped' then
    raise exception 'Task occurrence was skipped before completion' using errcode = '40001';
  end if;

  if v_occurrence.status = 'pending' then
    update public.task_occurrences
    set status = 'completed', completed_at = coalesce(completed_at, now())
    where id = p_occurrence_id and user_id = v_user_id
    returning * into v_occurrence;
  end if;

  update public.completion_requests
  set occurrence_id = p_occurrence_id
  where user_id = v_user_id and idempotency_key = p_idempotency_key;
  return v_occurrence;
end;
$$;
revoke all on function public.complete_task_occurrence(uuid, uuid) from public, anon;
grant execute on function public.complete_task_occurrence(uuid, uuid) to authenticated;
