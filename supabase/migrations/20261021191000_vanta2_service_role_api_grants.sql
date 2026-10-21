-- Keep service-role table access explicit instead of depending on project
-- default privileges. Public and anon receive no direct table privileges.
revoke all on public.tasks,
  public.habit_schedules,
  public.task_occurrences,
  public.completion_requests,
  public.goals,
  public.goal_links,
  public.device_tokens,
  public.reminder_deliveries,
  public.goal_progress,
  public.daily_reviews
from public, anon;

grant all on public.tasks,
  public.habit_schedules,
  public.task_occurrences,
  public.completion_requests,
  public.goals,
  public.goal_links,
  public.device_tokens,
  public.reminder_deliveries,
  public.daily_reviews
to service_role;

-- goal_progress is an aggregate security-invoker view; its backend caller only
-- needs SELECT, and the MCP Edge Function reads it with the service-role key.
grant select on public.goal_progress to service_role;
