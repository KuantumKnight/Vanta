-- pg_net was first enabled in public during initial setup. Reinstall it in
-- Supabase's extensions schema, while preserving the separate net API schema.
do $jobs$
declare
  existing_job record;
begin
  for existing_job in
    select jobid from cron.job
    where jobname in ('system-expand-schedules', 'system-send-reminders')
  loop
    perform cron.unschedule(existing_job.jobid);
  end loop;
end;
$jobs$;

drop trigger if exists system_tasks_widget_refresh on public.tasks;
drop trigger if exists system_occurrences_widget_refresh on public.task_occurrences;
drop trigger if exists system_schedules_widget_refresh on public.habit_schedules;
drop trigger if exists system_goals_widget_refresh on public.goals;
drop trigger if exists system_goal_links_widget_refresh on public.goal_links;
drop function if exists private.system_refresh_widget_after_change();
drop function if exists private.system_invoke_edge_function(text, jsonb);

drop extension pg_net;
create extension pg_net with schema extensions;

create index if not exists tasks_user_id_idx on public.tasks(user_id);
create index if not exists habit_schedules_task_user_idx on public.habit_schedules(task_id, user_id);
create index if not exists habit_schedules_user_id_idx on public.habit_schedules(user_id);
create index if not exists task_occurrences_task_user_idx on public.task_occurrences(task_id, user_id);
create index if not exists task_occurrences_schedule_user_idx on public.task_occurrences(schedule_id, user_id) where schedule_id is not null;
create index if not exists completion_requests_occurrence_id_idx on public.completion_requests(occurrence_id) where occurrence_id is not null;
create index if not exists goals_user_id_idx on public.goals(user_id);
create index if not exists goal_links_goal_user_idx on public.goal_links(goal_id, user_id);
create index if not exists goal_links_task_user_idx on public.goal_links(task_id, user_id);
create index if not exists goal_links_user_id_idx on public.goal_links(user_id);
create index if not exists device_tokens_user_id_idx on public.device_tokens(user_id);
create index if not exists reminder_deliveries_device_user_idx on public.reminder_deliveries(device_id, user_id);
create index if not exists reminder_deliveries_user_id_idx on public.reminder_deliveries(user_id);

create or replace function private.system_invoke_edge_function(
  p_function text,
  p_body jsonb default '{}'::jsonb
)
returns bigint
language plpgsql
security definer
set search_path = pg_catalog, public, private, vault, net
as $$
declare
  project_url text;
  internal_secret text;
begin
  if p_function not in ('sync-push', 'expand-schedules', 'send-reminders') then
    raise exception 'Unknown SYSTEM function';
  end if;
  select decrypted_secret into project_url
  from vault.decrypted_secrets where name = 'system_project_url';
  select decrypted_secret into internal_secret
  from vault.decrypted_secrets where name = 'system_internal_secret';
  if project_url is null or internal_secret is null then
    raise exception 'SYSTEM server credentials are not configured in Vault';
  end if;
  return net.http_post(
    url := rtrim(project_url, '/') || '/functions/v1/' || p_function,
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'x-system-internal-secret', internal_secret
    ),
    body := coalesce(p_body, '{}'::jsonb),
    timeout_milliseconds := 10000
  );
end;
$$;
revoke all on function private.system_invoke_edge_function(text, jsonb) from public, anon, authenticated;
grant execute on function private.system_invoke_edge_function(text, jsonb) to service_role;

create or replace function private.system_refresh_widget_after_change()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, private
as $$
declare
  owner_id uuid;
begin
  if tg_op = 'DELETE' then owner_id := old.user_id; else owner_id := new.user_id; end if;
  if owner_id is not null then
    perform private.system_invoke_edge_function(
      'sync-push',
      jsonb_build_object(
        'table', tg_table_name,
        'operation', lower(tg_op),
        'record', jsonb_build_object('user_id', owner_id)
      )
    );
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;
revoke all on function private.system_refresh_widget_after_change() from public, anon, authenticated;

create trigger system_tasks_widget_refresh after insert or update or delete on public.tasks
for each row execute function private.system_refresh_widget_after_change();
create trigger system_occurrences_widget_refresh after insert or update or delete on public.task_occurrences
for each row execute function private.system_refresh_widget_after_change();
create trigger system_schedules_widget_refresh after insert or update or delete on public.habit_schedules
for each row execute function private.system_refresh_widget_after_change();
create trigger system_goals_widget_refresh after insert or update or delete on public.goals
for each row execute function private.system_refresh_widget_after_change();
create trigger system_goal_links_widget_refresh after insert or update or delete on public.goal_links
for each row execute function private.system_refresh_widget_after_change();

select cron.schedule(
  'system-expand-schedules', '17 * * * *',
  $$select private.system_invoke_edge_function('expand-schedules', '{}'::jsonb);$$
);
select cron.schedule(
  'system-send-reminders', '* * * * *',
  $$select private.system_invoke_edge_function('send-reminders', '{}'::jsonb);$$
);
