-- Supabase projects can grant API roles rights through default privileges.
-- Clear those rights first, then grant only the operations SYSTEM clients use.
revoke all on public.tasks, public.habit_schedules, public.task_occurrences,
  public.completion_requests, public.goals, public.goal_links, public.device_tokens,
  public.reminder_deliveries, public.goal_progress
from public, anon, authenticated;

grant select on public.tasks, public.habit_schedules to authenticated;
grant select, insert on public.task_occurrences to authenticated;
grant select on public.goals, public.goal_links, public.goal_progress to authenticated;
grant select, insert, update on public.device_tokens to authenticated;

revoke all on table private.mcp_operation_requests from public, anon, authenticated;

-- Force task, habit, goal, and skip mutations through the operation-ID wrappers.
revoke execute on function public.create_one_off_task(text, text, timestamptz, boolean, timestamptz)
  from authenticated;
revoke execute on function public.update_one_off_task(uuid, text, boolean, text, boolean, timestamptz, boolean, boolean, timestamptz, boolean, boolean, boolean)
  from authenticated;
revoke execute on function public.create_habit_with_schedule(text, text, timestamptz, text, timestamptz[], timestamptz[], time)
  from authenticated;
revoke execute on function public.replace_habit_schedule(uuid, timestamptz, timestamptz, text, timestamptz[], timestamptz[], time)
  from authenticated;
revoke execute on function public.skip_habit_occurrence(uuid)
  from authenticated;
