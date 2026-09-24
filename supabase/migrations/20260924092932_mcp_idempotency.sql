create table private.mcp_operation_requests (
  user_id uuid not null references auth.users(id) on delete cascade,
  operation_id uuid not null,
  tool_name text not null check (length(tool_name) between 1 and 80),
  request_payload jsonb not null,
  result jsonb,
  created_at timestamptz not null default now(),
  primary key (user_id, operation_id)
);
revoke all on table private.mcp_operation_requests from public, anon, authenticated;

create or replace function private.begin_mcp_operation(
  p_operation_id uuid,
  p_tool_name text,
  p_request_payload jsonb
) returns jsonb
language plpgsql
security definer
set search_path = private, pg_temp
as $$
declare
  current_user_id uuid := auth.uid();
  operation private.mcp_operation_requests;
begin
  if current_user_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;
  if p_operation_id is null then
    raise exception 'An operation_id is required' using errcode = '22023';
  end if;

  insert into private.mcp_operation_requests(user_id, operation_id, tool_name, request_payload)
  values (current_user_id, p_operation_id, p_tool_name, p_request_payload)
  on conflict (user_id, operation_id) do nothing;

  select * into operation
  from private.mcp_operation_requests
  where user_id = current_user_id and operation_id = p_operation_id
  for update;

  if operation.tool_name <> p_tool_name
     or operation.request_payload is distinct from p_request_payload then
    raise exception 'operation_id was already used for a different request' using errcode = '22023';
  end if;
  return operation.result;
end;
$$;

create or replace function private.finish_mcp_operation(
  p_operation_id uuid,
  p_result jsonb
) returns void
language plpgsql
security definer
set search_path = private, pg_temp
as $$
begin
  update private.mcp_operation_requests
  set result = p_result
  where user_id = auth.uid() and operation_id = p_operation_id;
  if not found then
    raise exception 'Operation was not started' using errcode = 'P0002';
  end if;
end;
$$;

revoke all on function private.begin_mcp_operation(uuid, text, jsonb) from public, anon, authenticated;
revoke all on function private.finish_mcp_operation(uuid, jsonb) from public, anon, authenticated;

create or replace function public.mcp_create_task(
  p_operation_id uuid,
  p_title text,
  p_notes text,
  p_scheduled_for timestamptz,
  p_all_day boolean,
  p_reminder_at timestamptz
) returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
  request_payload jsonb := jsonb_build_object(
    'title', p_title, 'notes', p_notes, 'scheduled_for', p_scheduled_for,
    'all_day', p_all_day, 'reminder_at', p_reminder_at
  );
  replay jsonb;
  result jsonb;
begin
  replay := private.begin_mcp_operation(p_operation_id, 'create_task', request_payload);
  if replay is not null then return replay; end if;
  result := public.create_one_off_task(p_title, p_notes, p_scheduled_for, p_all_day, p_reminder_at);
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
  p_update_archived boolean
) returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
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
  replay := private.begin_mcp_operation(p_operation_id, 'update_task', request_payload);
  if replay is not null then return replay; end if;
  result := public.update_one_off_task(
    p_task_id, p_title, p_update_title, p_notes, p_update_notes, p_scheduled_for,
    p_update_schedule, p_all_day, p_reminder_at, p_update_reminder, p_archived, p_update_archived
  );
  result := result || jsonb_build_object('updated', true, 'timezone', 'Asia/Kolkata');
  perform private.finish_mcp_operation(p_operation_id, result);
  return result;
end;
$$;

create or replace function public.mcp_complete_task(
  p_operation_id uuid,
  p_occurrence_id uuid
) returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
  request_payload jsonb := jsonb_build_object('occurrence_id', p_occurrence_id);
  replay jsonb;
  occurrence_json jsonb;
  result jsonb;
begin
  replay := private.begin_mcp_operation(p_operation_id, 'complete_task', request_payload);
  if replay is not null then return replay; end if;
  select to_jsonb(completed_row) into occurrence_json
  from public.complete_task_occurrence(p_occurrence_id, p_operation_id) as completed_row;
  if occurrence_json is null then
    raise exception 'Task occurrence not found' using errcode = 'P0002';
  end if;
  result := jsonb_build_object('completed', true, 'occurrence', occurrence_json);
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
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
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
  replay := private.begin_mcp_operation(p_operation_id, 'create_habit', request_payload);
  if replay is not null then return replay; end if;
  select * into schedule
  from public.create_habit_with_schedule(p_title, p_notes, p_dtstart, p_rrule, p_rdates, p_exdates, p_reminder_time);
  result := jsonb_build_object(
    'task', jsonb_build_object('id', schedule.task_id, 'title', p_title, 'kind', 'habit'),
    'schedule', to_jsonb(schedule)
  );
  perform private.finish_mcp_operation(p_operation_id, result);
  return result;
end;
$$;

create or replace function public.mcp_update_habit_schedule(
  p_operation_id uuid,
  p_task_id uuid,
  p_effective_from timestamptz,
  p_dtstart timestamptz,
  p_rrule text,
  p_rdates timestamptz[],
  p_exdates timestamptz[],
  p_reminder_time time
) returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
  request_payload jsonb := jsonb_build_object(
    'task_id', p_task_id, 'effective_from', p_effective_from, 'dtstart', p_dtstart,
    'rrule', p_rrule, 'rdates', coalesce(to_jsonb(p_rdates), '[]'::jsonb),
    'exdates', coalesce(to_jsonb(p_exdates), '[]'::jsonb), 'reminder_time', p_reminder_time
  );
  replay jsonb;
  schedule public.habit_schedules;
  result jsonb;
begin
  replay := private.begin_mcp_operation(p_operation_id, 'update_habit_schedule', request_payload);
  if replay is not null then return replay; end if;
  if p_effective_from <= now() then
    raise exception 'Choose a future effective date' using errcode = '22023';
  end if;
  select * into schedule
  from public.replace_habit_schedule(p_task_id, p_effective_from, p_dtstart, p_rrule, p_rdates, p_exdates, p_reminder_time);
  result := jsonb_build_object('updated', true, 'schedule', to_jsonb(schedule));
  perform private.finish_mcp_operation(p_operation_id, result);
  return result;
end;
$$;

create or replace function public.mcp_skip_habit_occurrence(
  p_operation_id uuid,
  p_occurrence_id uuid
) returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
  request_payload jsonb := jsonb_build_object('occurrence_id', p_occurrence_id);
  replay jsonb;
  occurrence_json jsonb;
  result jsonb;
begin
  replay := private.begin_mcp_operation(p_operation_id, 'skip_habit_occurrence', request_payload);
  if replay is not null then return replay; end if;
  select to_jsonb(skipped_row) into occurrence_json
  from public.skip_habit_occurrence(p_occurrence_id) as skipped_row;
  if occurrence_json is null then
    raise exception 'Pending habit occurrence not found' using errcode = 'P0002';
  end if;
  result := jsonb_build_object('skipped', true, 'occurrence', occurrence_json);
  perform private.finish_mcp_operation(p_operation_id, result);
  return result;
end;
$$;

create or replace function public.mcp_create_goal(
  p_operation_id uuid,
  p_title text,
  p_description text
) returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
  request_payload jsonb := jsonb_build_object('title', p_title, 'description', p_description);
  replay jsonb;
  new_goal public.goals;
  result jsonb;
begin
  replay := private.begin_mcp_operation(p_operation_id, 'create_goal', request_payload);
  if replay is not null then return replay; end if;
  insert into public.goals(title, description)
  values (btrim(p_title), p_description)
  returning * into new_goal;
  result := jsonb_build_object('id', new_goal.id, 'title', new_goal.title, 'description', new_goal.description, 'created_at', new_goal.created_at);
  perform private.finish_mcp_operation(p_operation_id, result);
  return result;
end;
$$;

create or replace function public.mcp_link_goal_task(
  p_operation_id uuid,
  p_goal_id uuid,
  p_task_id uuid,
  p_target_count integer
) returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
  request_payload jsonb := jsonb_build_object('goal_id', p_goal_id, 'task_id', p_task_id, 'target_count', p_target_count);
  replay jsonb;
  link_row public.goal_links;
  result jsonb;
begin
  replay := private.begin_mcp_operation(p_operation_id, 'link_goal_task', request_payload);
  if replay is not null then return replay; end if;
  insert into public.goal_links(goal_id, task_id, target_count)
  values (p_goal_id, p_task_id, p_target_count)
  on conflict (goal_id, task_id) do update set target_count = excluded.target_count
  returning * into link_row;
  result := jsonb_build_object('goal_id', link_row.goal_id, 'task_id', link_row.task_id, 'target_count', link_row.target_count);
  perform private.finish_mcp_operation(p_operation_id, result);
  return result;
end;
$$;

create or replace function public.mcp_update_goal(
  p_operation_id uuid,
  p_goal_id uuid,
  p_title text,
  p_description text,
  p_update_title boolean,
  p_update_description boolean
) returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
  request_payload jsonb := jsonb_build_object(
    'goal_id', p_goal_id, 'title', p_title, 'description', p_description,
    'update_title', p_update_title, 'update_description', p_update_description
  );
  replay jsonb;
  updated_goal public.goals;
  result jsonb;
begin
  replay := private.begin_mcp_operation(p_operation_id, 'update_goal', request_payload);
  if replay is not null then return replay; end if;
  update public.goals set
    title = case when p_update_title then btrim(p_title) else title end,
    description = case when p_update_description then p_description else description end
  where id = p_goal_id and user_id = auth.uid()
  returning * into updated_goal;
  if not found then raise exception 'Goal not found' using errcode = 'P0002'; end if;
  result := jsonb_build_object('id', updated_goal.id, 'title', updated_goal.title, 'description', updated_goal.description);
  perform private.finish_mcp_operation(p_operation_id, result);
  return result;
end;
$$;

revoke all on function public.mcp_create_task(uuid, text, text, timestamptz, boolean, timestamptz) from public, anon;
revoke all on function public.mcp_update_task(uuid, uuid, text, boolean, text, boolean, timestamptz, boolean, boolean, timestamptz, boolean, boolean, boolean) from public, anon;
revoke all on function public.mcp_complete_task(uuid, uuid) from public, anon;
revoke all on function public.mcp_create_habit(uuid, text, text, timestamptz, text, timestamptz[], timestamptz[], time) from public, anon;
revoke all on function public.mcp_update_habit_schedule(uuid, uuid, timestamptz, timestamptz, text, timestamptz[], timestamptz[], time) from public, anon;
revoke all on function public.mcp_skip_habit_occurrence(uuid, uuid) from public, anon;
revoke all on function public.mcp_create_goal(uuid, text, text) from public, anon;
revoke all on function public.mcp_link_goal_task(uuid, uuid, uuid, integer) from public, anon;
revoke all on function public.mcp_update_goal(uuid, uuid, text, text, boolean, boolean) from public, anon;

grant execute on function public.mcp_create_task(uuid, text, text, timestamptz, boolean, timestamptz) to authenticated;
grant execute on function public.mcp_update_task(uuid, uuid, text, boolean, text, boolean, timestamptz, boolean, boolean, timestamptz, boolean, boolean, boolean) to authenticated;
grant execute on function public.mcp_complete_task(uuid, uuid) to authenticated;
grant execute on function public.mcp_create_habit(uuid, text, text, timestamptz, text, timestamptz[], timestamptz[], time) to authenticated;
grant execute on function public.mcp_update_habit_schedule(uuid, uuid, timestamptz, timestamptz, text, timestamptz[], timestamptz[], time) to authenticated;
grant execute on function public.mcp_skip_habit_occurrence(uuid, uuid) to authenticated;
grant execute on function public.mcp_create_goal(uuid, text, text) to authenticated;
grant execute on function public.mcp_link_goal_task(uuid, uuid, uuid, integer) to authenticated;
grant execute on function public.mcp_update_goal(uuid, uuid, text, text, boolean, boolean) to authenticated;
