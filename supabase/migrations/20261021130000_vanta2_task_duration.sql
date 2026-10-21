-- Duration is optional so existing occurrences and clients remain compatible.
alter table public.tasks
  add column default_duration_minutes integer,
  add constraint tasks_default_duration_minutes_check
    check (default_duration_minutes is null or default_duration_minutes between 5 and 1440);

alter table public.task_occurrences
  add column duration_minutes integer,
  add constraint task_occurrences_duration_minutes_check
    check (duration_minutes is null or duration_minutes between 5 and 1440);

-- Extended internal writer. The pre-existing writer remains available to old
-- function bodies and is still not directly executable by authenticated users.
create function public.create_one_off_task(
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
  insert into public.tasks(user_id, title, notes, kind, default_duration_minutes)
    values (current_user_id, p_title, p_notes, 'task', p_duration_minutes) returning * into new_task;
  insert into public.task_occurrences(task_id, user_id, scheduled_for, all_day, reminder_at, duration_minutes)
    values (new_task.id, current_user_id, p_scheduled_for, p_all_day, p_reminder_at, p_duration_minutes)
    returning * into new_occurrence;
  return jsonb_build_object(
    'task', jsonb_build_object('id', new_task.id, 'title', new_task.title, 'kind', new_task.kind,
      'default_duration_minutes', new_task.default_duration_minutes),
    'occurrence', jsonb_build_object('id', new_occurrence.id, 'scheduled_for', new_occurrence.scheduled_for,
      'all_day', new_occurrence.all_day, 'reminder_at', new_occurrence.reminder_at,
      'duration_minutes', new_occurrence.duration_minutes, 'status', new_occurrence.status)
  );
end;
$$;
revoke all on function public.create_one_off_task(text, text, timestamptz, boolean, timestamptz, integer)
  from public, anon, authenticated;

create function public.update_one_off_task(
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
  p_update_duration boolean
) returns jsonb
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  current_user_id uuid := auth.uid();
  current_task public.tasks;
  updated_task public.tasks;
  updated_occurrence public.task_occurrences;
begin
  if current_user_id is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  if p_update_duration and p_duration_minutes is not null and p_duration_minutes not between 5 and 1440 then
    raise exception 'Duration must be between 5 and 1440 minutes' using errcode = '22023';
  end if;
  select * into current_task from public.tasks
    where id = p_task_id and user_id = current_user_id for update;
  if not found then raise exception 'Task not found' using errcode = 'P0002'; end if;
  if current_task.kind = 'habit' and (p_update_schedule or p_update_reminder or p_update_duration) then
    raise exception 'Recurring habit settings cannot be changed with update_task' using errcode = '22023';
  end if;
  if p_update_schedule and p_scheduled_for is null then
    raise exception 'A due date is required when changing a schedule' using errcode = '22023';
  end if;
  if p_update_archived and p_archived = false and current_task.kind = 'habit'
    and current_task.archived_at is not null
    and not exists (select 1 from public.habit_schedules where task_id = p_task_id and user_id = current_user_id and effective_until is null) then
    raise exception 'Create a new habit schedule before reactivating this archived habit' using errcode = '22023';
  end if;

  update public.tasks set
    title = case when p_update_title then p_title else title end,
    notes = case when p_update_notes then p_notes else notes end,
    archived_at = case
      when p_update_archived and p_archived then coalesce(archived_at, now())
      when p_update_archived then null
      else archived_at
    end,
    default_duration_minutes = case when p_update_duration then p_duration_minutes else default_duration_minutes end
  where id = p_task_id and user_id = current_user_id
  returning * into updated_task;

  if p_update_schedule or p_update_reminder or p_update_duration then
    select * into updated_occurrence from public.task_occurrences
      where task_id = p_task_id and user_id = current_user_id and schedule_id is null
      for update;
    if not found then raise exception 'Task occurrence not found' using errcode = 'P0002'; end if;
    if updated_occurrence.status <> 'pending' then
      raise exception 'Only an unfinished task occurrence can be rescheduled, reminded, or assigned a duration' using errcode = '22023';
    end if;
    update public.task_occurrences set
      scheduled_for = case when p_update_schedule then p_scheduled_for else scheduled_for end,
      all_day = case when p_update_schedule then p_all_day else all_day end,
      reminder_at = case when p_update_reminder then p_reminder_at else reminder_at end,
      duration_minutes = case when p_update_duration then p_duration_minutes else duration_minutes end
    where id = updated_occurrence.id
    returning * into updated_occurrence;
  end if;

  if p_update_archived and p_archived then
    update public.habit_schedules set
      effective_until = greatest(now(), effective_from + interval '1 microsecond')
    where task_id = p_task_id and user_id = current_user_id and effective_until is null;
    update public.task_occurrences set status = 'skipped'
    where task_id = p_task_id and user_id = current_user_id and schedule_id is not null and status = 'pending';
  end if;

  return jsonb_build_object(
    'task_id', updated_task.id,
    'title', updated_task.title,
    'archived', updated_task.archived_at is not null,
    'occurrence', case when updated_occurrence.id is null then null else
      jsonb_build_object('id', updated_occurrence.id, 'scheduled_for', updated_occurrence.scheduled_for,
        'all_day', updated_occurrence.all_day, 'reminder_at', updated_occurrence.reminder_at,
        'duration_minutes', updated_occurrence.duration_minutes, 'status', updated_occurrence.status) end
  );
end;
$$;
revoke all on function public.update_one_off_task(uuid, text, boolean, text, boolean, timestamptz, boolean, boolean, timestamptz, boolean, boolean, boolean, integer, boolean)
  from public, anon, authenticated;

create function public.create_habit_with_schedule(
  p_title text,
  p_notes text,
  p_dtstart timestamptz,
  p_rrule text,
  p_rdates timestamptz[],
  p_exdates timestamptz[],
  p_reminder_time time,
  p_default_duration_minutes integer
) returns setof public.habit_schedules
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  current_user_id uuid := auth.uid();
  new_task_id uuid;
  new_schedule public.habit_schedules;
begin
  if current_user_id is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  if p_default_duration_minutes is not null and p_default_duration_minutes not between 5 and 1440 then
    raise exception 'Duration must be between 5 and 1440 minutes' using errcode = '22023';
  end if;
  insert into public.tasks(user_id, title, notes, kind, default_duration_minutes)
    values (current_user_id, p_title, p_notes, 'habit', p_default_duration_minutes) returning id into new_task_id;
  insert into public.habit_schedules(task_id, user_id, dtstart, timezone, reminder_time, rrule, rdates, exdates, effective_from)
    values (new_task_id, current_user_id, p_dtstart, 'Asia/Kolkata', p_reminder_time, p_rrule,
      coalesce(p_rdates, '{}'), coalesce(p_exdates, '{}'), p_dtstart)
    returning * into new_schedule;
  return next new_schedule;
end;
$$;
revoke all on function public.create_habit_with_schedule(text, text, timestamptz, text, timestamptz[], timestamptz[], time, integer)
  from public, anon, authenticated;

-- Keep the old MCP signatures callable. Their payloads omit duration keys so
-- idempotency replay for requests made by older clients remains compatible.
create or replace function public.mcp_create_task(
  p_operation_id uuid,
  p_title text,
  p_notes text,
  p_scheduled_for timestamptz,
  p_all_day boolean,
  p_reminder_at timestamptz,
  p_duration_minutes integer
) returns jsonb
language plpgsql security definer set search_path = public, private, pg_temp as $$
declare
  request_payload jsonb := jsonb_build_object(
    'title', p_title, 'notes', p_notes, 'scheduled_for', p_scheduled_for,
    'all_day', p_all_day, 'reminder_at', p_reminder_at
  );
  replay jsonb;
  result jsonb;
begin
  if p_duration_minutes is not null and p_duration_minutes not between 5 and 1440 then
    raise exception 'Duration must be between 5 and 1440 minutes' using errcode = '22023';
  end if;
  if p_duration_minutes is not null then
    request_payload := request_payload || jsonb_build_object('duration_minutes', p_duration_minutes);
  end if;
  replay := private.begin_mcp_operation(p_operation_id, 'create_task', request_payload);
  if replay is not null then return replay; end if;
  result := public.create_one_off_task(p_title, p_notes, p_scheduled_for, p_all_day, p_reminder_at, p_duration_minutes);
  result := result || jsonb_build_object('timezone', 'Asia/Kolkata');
  perform private.finish_mcp_operation(p_operation_id, result);
  return result;
end;
$$;

create or replace function public.mcp_update_task(
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
  p_update_duration boolean
) returns jsonb
language plpgsql security definer set search_path = public, private, pg_temp as $$
declare
  request_payload jsonb := jsonb_build_object(
    'task_id', p_task_id, 'title', p_title, 'update_title', p_update_title,
    'notes', p_notes, 'update_notes', p_update_notes, 'scheduled_for', p_scheduled_for,
    'update_schedule', p_update_schedule, 'all_day', p_all_day,
    'reminder_at', p_reminder_at, 'update_reminder', p_update_reminder,
    'archived', p_archived, 'update_archived', p_update_archived
  );
  replay jsonb;
  result jsonb;
begin
  if p_update_duration and p_duration_minutes is not null and p_duration_minutes not between 5 and 1440 then
    raise exception 'Duration must be between 5 and 1440 minutes' using errcode = '22023';
  end if;
  if p_update_duration then
    request_payload := request_payload || jsonb_build_object(
      'duration_minutes', p_duration_minutes, 'update_duration', true
    );
  end if;
  replay := private.begin_mcp_operation(p_operation_id, 'update_task', request_payload);
  if replay is not null then return replay; end if;
  result := public.update_one_off_task(
    p_task_id, p_title, p_update_title, p_notes, p_update_notes, p_scheduled_for,
    p_update_schedule, p_all_day, p_reminder_at, p_update_reminder, p_archived,
    p_update_archived, p_duration_minutes, p_update_duration
  );
  result := result || jsonb_build_object('updated', true, 'timezone', 'Asia/Kolkata');
  perform private.finish_mcp_operation(p_operation_id, result);
  return result;
end;
$$;

create or replace function public.mcp_create_habit(
  p_operation_id uuid,
  p_title text,
  p_notes text,
  p_dtstart timestamptz,
  p_rrule text,
  p_rdates timestamptz[],
  p_exdates timestamptz[],
  p_reminder_time time,
  p_default_duration_minutes integer
) returns jsonb
language plpgsql security definer set search_path = public, private, pg_temp as $$
declare
  request_payload jsonb := jsonb_build_object(
    'title', p_title, 'notes', p_notes, 'dtstart', p_dtstart, 'rrule', p_rrule,
    'rdates', coalesce(to_jsonb(p_rdates), '[]'::jsonb),
    'exdates', coalesce(to_jsonb(p_exdates), '[]'::jsonb), 'reminder_time', p_reminder_time
  );
  replay jsonb;
  schedule public.habit_schedules;
  result jsonb;
begin
  if p_default_duration_minutes is not null and p_default_duration_minutes not between 5 and 1440 then
    raise exception 'Duration must be between 5 and 1440 minutes' using errcode = '22023';
  end if;
  if p_default_duration_minutes is not null then
    request_payload := request_payload || jsonb_build_object('default_duration_minutes', p_default_duration_minutes);
  end if;
  replay := private.begin_mcp_operation(p_operation_id, 'create_habit', request_payload);
  if replay is not null then return replay; end if;
  select * into schedule
  from public.create_habit_with_schedule(
    p_title, p_notes, p_dtstart, p_rrule, p_rdates, p_exdates, p_reminder_time, p_default_duration_minutes
  );
  result := jsonb_build_object(
    'task', jsonb_build_object('id', schedule.task_id, 'title', p_title, 'kind', 'habit',
      'default_duration_minutes', p_default_duration_minutes),
    'schedule', to_jsonb(schedule)
  );
  perform private.finish_mcp_operation(p_operation_id, result);
  return result;
end;
$$;

create or replace function public.mcp_create_habit(
  p_operation_id uuid,
  p_title text,
  p_notes text,
  p_dtstart timestamptz,
  p_rrule text,
  p_rdates timestamptz[],
  p_exdates timestamptz[],
  p_reminder_time time
) returns jsonb
language plpgsql security definer set search_path = public, private, pg_temp as $$
begin
  return public.mcp_create_habit(
    p_operation_id, p_title, p_notes, p_dtstart, p_rrule, p_rdates, p_exdates,
    p_reminder_time, null::integer
  );
end;
$$;

revoke all on function public.mcp_create_task(uuid, text, text, timestamptz, boolean, timestamptz, integer) from public, anon;
revoke all on function public.mcp_update_task(uuid, uuid, text, boolean, text, boolean, timestamptz, boolean, boolean, timestamptz, boolean, boolean, boolean, integer, boolean) from public, anon;
revoke all on function public.mcp_create_habit(uuid, text, text, timestamptz, text, timestamptz[], timestamptz[], time, integer) from public, anon;

grant execute on function public.mcp_create_task(uuid, text, text, timestamptz, boolean, timestamptz, integer) to authenticated;
grant execute on function public.mcp_update_task(uuid, uuid, text, boolean, text, boolean, timestamptz, boolean, boolean, timestamptz, boolean, boolean, boolean, integer, boolean) to authenticated;
grant execute on function public.mcp_create_habit(uuid, text, text, timestamptz, text, timestamptz[], timestamptz[], time, integer) to authenticated;

create or replace function public.mcp_update_task(
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
  p_update_archived boolean
) returns jsonb
language plpgsql security definer set search_path = public, private, pg_temp as $$
begin
  return public.mcp_update_task(
    p_operation_id, p_task_id, p_title, p_update_title, p_notes, p_update_notes,
    p_scheduled_for, p_update_schedule, p_all_day, p_reminder_at, p_update_reminder,
    p_archived, p_update_archived, null::integer, false
  );
end;
$$;

create or replace function public.mcp_create_task(
  p_operation_id uuid,
  p_title text,
  p_notes text,
  p_scheduled_for timestamptz,
  p_all_day boolean,
  p_reminder_at timestamptz
) returns jsonb
language plpgsql security definer set search_path = public, private, pg_temp as $$
begin
  return public.mcp_create_task(p_operation_id, p_title, p_notes, p_scheduled_for, p_all_day, p_reminder_at, null::integer);
end;
$$;
