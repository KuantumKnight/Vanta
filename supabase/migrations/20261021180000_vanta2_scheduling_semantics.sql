-- Scheduling semantics for Vanta tasks. Deadlines and scheduling windows are
-- independent of scheduled_for, which remains the legacy chosen start instant.
alter table public.tasks
  add column default_schedule_type text not null default 'SOFT'
    check (default_schedule_type in ('HARD', 'SOFT', 'FLEXIBLE')),
  add column default_priority text not null default 'NORMAL'
    check (default_priority in ('LOW', 'NORMAL', 'HIGH'));

alter table public.task_occurrences
  add column schedule_type text,
  add column priority text,
  add column deadline_at timestamptz,
  add column earliest_start timestamptz,
  add column latest_end timestamptz,
  add column preferred_start timestamptz;

-- Existing rows keep their old meaning: scheduled starts are soft commitments;
-- unscheduled rows belong in the flexible tray. A due date is never copied into
-- deadline_at because old data did not distinguish those concepts.
update public.task_occurrences
set schedule_type = case when scheduled_for is null then 'FLEXIBLE' else 'SOFT' end,
    priority = 'NORMAL';

update public.tasks t set
  default_schedule_type = o.schedule_type,
  default_priority = o.priority
from public.task_occurrences o
where o.task_id = t.id and o.schedule_id is null;

alter table public.task_occurrences
  alter column schedule_type set not null,
  alter column priority set not null,
  add constraint task_occurrences_schedule_type_check
    check (schedule_type in ('HARD', 'SOFT', 'FLEXIBLE')),
  add constraint task_occurrences_priority_check
    check (priority in ('LOW', 'NORMAL', 'HIGH')),
  add constraint task_occurrences_schedule_placement_check
    check (
      (schedule_type = 'FLEXIBLE' and scheduled_for is null)
      or (schedule_type in ('HARD', 'SOFT') and scheduled_for is not null)
    ),
  add constraint task_occurrences_schedule_window_check
    check (earliest_start is null or latest_end is null or latest_end >= earliest_start);

create index task_occurrences_user_schedule_type_start
  on public.task_occurrences(user_id, schedule_type, scheduled_for, status);
create index task_occurrences_user_deadline
  on public.task_occurrences(user_id, deadline_at, status)
  where deadline_at is not null;
create index task_occurrences_user_earliest_start
  on public.task_occurrences(user_id, earliest_start, status)
  where earliest_start is not null;
create index task_occurrences_user_latest_end
  on public.task_occurrences(user_id, latest_end, status)
  where latest_end is not null;

create or replace function private.system_apply_occurrence_schedule_defaults()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  task_schedule_type text;
  task_priority text;
begin
  select t.default_schedule_type, t.default_priority
  into task_schedule_type, task_priority
  from public.tasks t
  where t.id = new.task_id and t.user_id = new.user_id;

  if new.schedule_type is null then
    new.schedule_type := case
      when new.schedule_id is not null then coalesce(task_schedule_type, 'SOFT')
      when new.scheduled_for is null then 'FLEXIBLE'
      else 'SOFT'
    end;
  end if;
  if new.priority is null then new.priority := coalesce(task_priority, 'NORMAL'); end if;

  if tg_op = 'UPDATE' and old.scheduled_for is distinct from new.scheduled_for
     and old.schedule_type is not distinct from new.schedule_type then
    if old.scheduled_for is null and new.scheduled_for is not null and new.schedule_type = 'FLEXIBLE' then
      new.schedule_type := 'SOFT';
    elsif old.scheduled_for is not null and new.scheduled_for is null and new.schedule_type = 'SOFT' then
      new.schedule_type := 'FLEXIBLE';
    end if;
  end if;
  return new;
end;
$$;

create trigger task_occurrences_apply_schedule_defaults
before insert or update of scheduled_for on public.task_occurrences
for each row execute function private.system_apply_occurrence_schedule_defaults();
revoke all on function private.system_apply_occurrence_schedule_defaults() from public, anon, authenticated;

create or replace function private.system_sync_oneoff_schedule_defaults()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.schedule_id is null then
    update public.tasks set
      default_schedule_type = new.schedule_type,
      default_priority = new.priority
    where id = new.task_id and user_id = new.user_id;
  end if;
  return new;
end;
$$;

create trigger task_occurrences_sync_oneoff_schedule_defaults
after insert or update of scheduled_for, schedule_type, priority on public.task_occurrences
for each row execute function private.system_sync_oneoff_schedule_defaults();
revoke all on function private.system_sync_oneoff_schedule_defaults() from public, anon, authenticated;

-- Keep the legacy create_task writer's request and response shapes intact while
-- deriving a useful default scheduling type for newly inserted one-off tasks.
create or replace function public.create_one_off_task(
  p_title text,
  p_notes text,
  p_scheduled_for timestamptz,
  p_all_day boolean,
  p_reminder_at timestamptz,
  p_duration_minutes integer
) returns jsonb
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  current_user_id uuid := auth.uid();
  new_task public.tasks;
  new_occurrence public.task_occurrences;
begin
  if current_user_id is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  if p_duration_minutes is not null and p_duration_minutes not between 5 and 1440 then
    raise exception 'Duration must be between 5 and 1440 minutes' using errcode = '22023';
  end if;
  insert into public.tasks(user_id, title, notes, kind, default_duration_minutes,
    default_schedule_type, default_priority)
    values (current_user_id, p_title, p_notes, 'task', p_duration_minutes,
      case when p_scheduled_for is null then 'FLEXIBLE' else 'SOFT' end, 'NORMAL')
    returning * into new_task;
  insert into public.task_occurrences(task_id, user_id, scheduled_for, all_day, reminder_at,
    duration_minutes, schedule_type, priority)
    values (new_task.id, current_user_id, p_scheduled_for, p_all_day, p_reminder_at,
      p_duration_minutes, case when p_scheduled_for is null then 'FLEXIBLE' else 'SOFT' end, 'NORMAL')
    returning * into new_occurrence;
  return jsonb_build_object(
    'task', jsonb_build_object('id', new_task.id, 'title', new_task.title, 'kind', new_task.kind,
      'default_duration_minutes', new_task.default_duration_minutes,
      'default_schedule_type', new_task.default_schedule_type,
      'default_priority', new_task.default_priority),
    'occurrence', jsonb_build_object('id', new_occurrence.id, 'scheduled_for', new_occurrence.scheduled_for,
      'all_day', new_occurrence.all_day, 'reminder_at', new_occurrence.reminder_at,
      'duration_minutes', new_occurrence.duration_minutes, 'status', new_occurrence.status,
      'schedule_type', new_occurrence.schedule_type, 'priority', new_occurrence.priority,
      'deadline_at', new_occurrence.deadline_at, 'earliest_start', new_occurrence.earliest_start,
      'latest_end', new_occurrence.latest_end, 'preferred_start', new_occurrence.preferred_start)
  );
end;
$$;
revoke all on function public.create_one_off_task(text, text, timestamptz, boolean, timestamptz, integer)
  from public, anon, authenticated;

create or replace function public.mcp_create_task_with_scheduling(
  p_operation_id uuid,
  p_title text,
  p_notes text,
  p_scheduled_for timestamptz,
  p_all_day boolean,
  p_reminder_at timestamptz,
  p_duration_minutes integer,
  p_schedule_type text,
  p_priority text,
  p_deadline_at timestamptz,
  p_earliest_start timestamptz,
  p_latest_end timestamptz,
  p_preferred_start timestamptz
) returns jsonb
language plpgsql security definer set search_path = public, private, pg_temp as $$
declare
  request_payload jsonb := jsonb_build_object(
    'title', p_title, 'notes', p_notes, 'scheduled_for', p_scheduled_for,
    'all_day', p_all_day, 'reminder_at', p_reminder_at,
    'duration_minutes', p_duration_minutes, 'schedule_type', p_schedule_type,
    'priority', p_priority, 'deadline_at', p_deadline_at,
    'earliest_start', p_earliest_start, 'latest_end', p_latest_end,
    'preferred_start', p_preferred_start
  );
  replay jsonb;
  result jsonb;
  task_id uuid;
  occurrence_id uuid;
  effective_type text := coalesce(p_schedule_type, case when p_scheduled_for is null then 'FLEXIBLE' else 'SOFT' end);
  effective_priority text := coalesce(p_priority, 'NORMAL');
begin
  if effective_type not in ('HARD', 'SOFT', 'FLEXIBLE') then
    raise exception 'Invalid schedule_type' using errcode = '22023';
  end if;
  if effective_priority not in ('LOW', 'NORMAL', 'HIGH') then
    raise exception 'Invalid priority' using errcode = '22023';
  end if;
  if (effective_type = 'FLEXIBLE' and p_scheduled_for is not null)
     or (effective_type in ('HARD', 'SOFT') and p_scheduled_for is null) then
    raise exception 'schedule_type does not match the scheduled start' using errcode = '22023';
  end if;
  if p_earliest_start is not null and p_latest_end is not null and p_latest_end < p_earliest_start then
    raise exception 'latest_end must be at or after earliest_start' using errcode = '22023';
  end if;
  if p_deadline_at is not null and p_earliest_start is not null and p_deadline_at < p_earliest_start then
    raise exception 'deadline_at must not be before earliest_start' using errcode = '22023';
  end if;
  replay := private.begin_mcp_operation(p_operation_id, 'create_task', request_payload);
  if replay is not null then return replay; end if;
  result := public.create_one_off_task(p_title, p_notes, p_scheduled_for, p_all_day, p_reminder_at, p_duration_minutes);
  task_id := (result #>> '{task,id}')::uuid;
  occurrence_id := (result #>> '{occurrence,id}')::uuid;
  update public.tasks set default_schedule_type = effective_type, default_priority = effective_priority
  where id = task_id and user_id = auth.uid();
  update public.task_occurrences set
    schedule_type = effective_type, priority = effective_priority, deadline_at = p_deadline_at,
    earliest_start = p_earliest_start, latest_end = p_latest_end, preferred_start = p_preferred_start
  where id = occurrence_id and user_id = auth.uid();
  select result || jsonb_build_object(
    'task', (result->'task') || jsonb_build_object(
      'default_schedule_type', effective_type, 'default_priority', effective_priority),
    'occurrence', (result->'occurrence') || jsonb_build_object(
      'schedule_type', effective_type, 'priority', effective_priority,
      'deadline_at', p_deadline_at, 'earliest_start', p_earliest_start,
      'latest_end', p_latest_end, 'preferred_start', p_preferred_start)
  ) into result;
  result := result || jsonb_build_object('timezone', 'Asia/Kolkata');
  perform private.finish_mcp_operation(p_operation_id, result);
  return result;
end;
$$;

create or replace function public.mcp_update_task_with_scheduling(
  p_operation_id uuid,
  p_task_id uuid,
  p_title text,
  p_update_title boolean,
  p_notes text,
  p_update_notes boolean,
  p_scheduled_for timestamptz,
  p_update_schedule boolean,
  p_all_day boolean,
  p_reminder_at timestamptz,
  p_update_reminder boolean,
  p_archived boolean,
  p_update_archived boolean,
  p_duration_minutes integer,
  p_update_duration boolean,
  p_scheduling jsonb
) returns jsonb
language plpgsql security definer set search_path = public, private, pg_temp as $$
declare
  request_payload jsonb := jsonb_build_object(
    'task_id', p_task_id, 'title', p_title, 'update_title', p_update_title,
    'notes', p_notes, 'update_notes', p_update_notes, 'scheduled_for', p_scheduled_for,
    'update_schedule', p_update_schedule, 'all_day', p_all_day,
    'reminder_at', p_reminder_at, 'update_reminder', p_update_reminder,
    'archived', p_archived, 'update_archived', p_update_archived,
    'duration_minutes', p_duration_minutes, 'update_duration', p_update_duration,
    'scheduling', coalesce(p_scheduling, '{}'::jsonb)
  );
  replay jsonb;
  result jsonb;
  occurrence public.task_occurrences;
begin
  if p_scheduling is not null and jsonb_typeof(p_scheduling) <> 'object' then
    raise exception 'scheduling must be an object' using errcode = '22023';
  end if;
  if p_scheduling is not null and exists (
    select 1 from jsonb_object_keys(p_scheduling) as provided(key)
    where key not in ('schedule_type', 'priority', 'deadline_at', 'earliest_start', 'latest_end', 'preferred_start')
  ) then
    raise exception 'Unknown scheduling field' using errcode = '22023';
  end if;
  if p_scheduling is not null and exists (
    select 1 from jsonb_each(p_scheduling) as provided(key, value)
    where (key in ('schedule_type', 'priority') and jsonb_typeof(value) <> 'string')
       or (key in ('deadline_at', 'earliest_start', 'latest_end', 'preferred_start')
           and jsonb_typeof(value) not in ('string', 'null'))
  ) then
    raise exception 'Invalid scheduling field value' using errcode = '22023';
  end if;
  if p_scheduling ? 'schedule_type' and coalesce(p_scheduling->>'schedule_type', '') not in ('HARD', 'SOFT', 'FLEXIBLE') then
    raise exception 'Invalid schedule_type' using errcode = '22023';
  end if;
  if p_scheduling ? 'priority' and coalesce(p_scheduling->>'priority', '') not in ('LOW', 'NORMAL', 'HIGH') then
    raise exception 'Invalid priority' using errcode = '22023';
  end if;
  if p_scheduling ? 'earliest_start' and p_scheduling->'earliest_start' <> 'null'::jsonb
     and p_scheduling ? 'latest_end' and p_scheduling->'latest_end' <> 'null'::jsonb
     and (p_scheduling->>'latest_end')::timestamptz < (p_scheduling->>'earliest_start')::timestamptz then
    raise exception 'latest_end must be at or after earliest_start' using errcode = '22023';
  end if;
  if p_scheduling ? 'deadline_at' and p_scheduling->'deadline_at' <> 'null'::jsonb
     and p_scheduling ? 'earliest_start' and p_scheduling->'earliest_start' <> 'null'::jsonb
     and (p_scheduling->>'deadline_at')::timestamptz < (p_scheduling->>'earliest_start')::timestamptz then
    raise exception 'deadline_at must not be before earliest_start' using errcode = '22023';
  end if;
  replay := private.begin_mcp_operation(p_operation_id, 'update_task_with_scheduling', request_payload);
  if replay is not null then return replay; end if;
  result := public.update_one_off_task(
    p_task_id, p_title, p_update_title, p_notes, p_update_notes, p_scheduled_for,
    p_update_schedule, p_all_day, p_reminder_at, p_update_reminder, p_archived,
    p_update_archived, p_duration_minutes, p_update_duration
  );
  if p_scheduling is not null and p_scheduling <> '{}'::jsonb then
    select * into occurrence from public.task_occurrences
    where task_id = p_task_id and user_id = auth.uid() and schedule_id is null for update;
    if not found then raise exception 'Task occurrence not found' using errcode = 'P0002'; end if;
    if occurrence.status <> 'pending' then raise exception 'Only an unfinished task occurrence can be rescheduled' using errcode = '22023'; end if;
    update public.task_occurrences set
      schedule_type = case when p_scheduling ? 'schedule_type' then p_scheduling->>'schedule_type' else schedule_type end,
      priority = case when p_scheduling ? 'priority' then p_scheduling->>'priority' else priority end,
      deadline_at = case when p_scheduling ? 'deadline_at' then (p_scheduling->>'deadline_at')::timestamptz else deadline_at end,
      earliest_start = case when p_scheduling ? 'earliest_start' then (p_scheduling->>'earliest_start')::timestamptz else earliest_start end,
      latest_end = case when p_scheduling ? 'latest_end' then (p_scheduling->>'latest_end')::timestamptz else latest_end end,
      preferred_start = case when p_scheduling ? 'preferred_start' then (p_scheduling->>'preferred_start')::timestamptz else preferred_start end
    where id = occurrence.id returning * into occurrence;
    update public.tasks set
      default_schedule_type = occurrence.schedule_type,
      default_priority = occurrence.priority
    where id = p_task_id and user_id = auth.uid();
    result := result || jsonb_build_object('occurrence',
      coalesce(nullif(result->'occurrence', 'null'::jsonb), '{}'::jsonb) || jsonb_build_object(
        'schedule_type', occurrence.schedule_type, 'priority', occurrence.priority,
        'deadline_at', occurrence.deadline_at, 'earliest_start', occurrence.earliest_start,
        'latest_end', occurrence.latest_end, 'preferred_start', occurrence.preferred_start));
  end if;
  result := result || jsonb_build_object('updated', true, 'timezone', 'Asia/Kolkata');
  perform private.finish_mcp_operation(p_operation_id, result);
  return result;
end;
$$;

revoke all on function public.mcp_create_task_with_scheduling(uuid, text, text, timestamptz, boolean, timestamptz, integer, text, text, timestamptz, timestamptz, timestamptz, timestamptz) from public, anon;
revoke all on function public.mcp_update_task_with_scheduling(uuid, uuid, text, boolean, text, boolean, timestamptz, boolean, boolean, timestamptz, boolean, boolean, boolean, integer, boolean, jsonb) from public, anon;
grant execute on function public.mcp_create_task_with_scheduling(uuid, text, text, timestamptz, boolean, timestamptz, integer, text, text, timestamptz, timestamptz, timestamptz, timestamptz) to authenticated;
grant execute on function public.mcp_update_task_with_scheduling(uuid, uuid, text, boolean, text, boolean, timestamptz, boolean, boolean, timestamptz, boolean, boolean, boolean, integer, boolean, jsonb) to authenticated;
