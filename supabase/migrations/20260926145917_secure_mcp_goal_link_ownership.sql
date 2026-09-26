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
  current_user_id uuid := auth.uid();
  request_payload jsonb := jsonb_build_object(
    'goal_id', p_goal_id,
    'task_id', p_task_id,
    'target_count', p_target_count
  );
  replay jsonb;
  link_row public.goal_links;
  result jsonb;
begin
  replay := private.begin_mcp_operation(p_operation_id, 'link_goal_task', request_payload);
  if replay is not null then return replay; end if;

  if not exists (
    select 1 from public.goals
    where id = p_goal_id and user_id = current_user_id
  ) or not exists (
    select 1 from public.tasks
    where id = p_task_id and user_id = current_user_id
  ) then
    raise exception 'Goal or task not found' using errcode = 'P0002';
  end if;

  insert into public.goal_links as existing_link(goal_id, task_id, target_count)
  values (p_goal_id, p_task_id, p_target_count)
  on conflict (goal_id, task_id) do update
    set target_count = excluded.target_count
    where existing_link.user_id = current_user_id
  returning * into link_row;

  if not found then
    raise exception 'Goal link not found' using errcode = 'P0002';
  end if;

  result := jsonb_build_object(
    'goal_id', link_row.goal_id,
    'task_id', link_row.task_id,
    'target_count', link_row.target_count
  );
  perform private.finish_mcp_operation(p_operation_id, result);
  return result;
end;
$$;
