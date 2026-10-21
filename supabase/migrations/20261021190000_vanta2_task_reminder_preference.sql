alter table public.device_tokens
  add column if not exists task_reminders_enabled boolean not null default true;

create or replace function public.system_set_task_reminders_enabled(p_token text, p_enabled boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
begin
  if current_user_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;
  if p_enabled is null then
    raise exception 'A task reminder preference is required' using errcode = '22023';
  end if;
  if p_token is null or length(p_token) not between 20 and 4096 then
    raise exception 'Invalid device token' using errcode = '22023';
  end if;

  update public.device_tokens
  set task_reminders_enabled = p_enabled,
      updated_at = now()
  where user_id = current_user_id and token = p_token;
end;
$$;

revoke all on function public.system_set_task_reminders_enabled(text, boolean) from public, anon;
grant execute on function public.system_set_task_reminders_enabled(text, boolean) to authenticated;

create or replace function private.claim_due_reminders()
returns table (occurrence_id uuid, user_id uuid, task_title text, scheduled_for timestamptz, device_id uuid, token text)
language sql security definer set search_path = public, pg_temp as $$
  with due as (
    select o.id, o.user_id, t.title, o.scheduled_for, dt.id as device_id, dt.token
    from public.task_occurrences o
    join public.tasks t on t.id = o.task_id and t.user_id = o.user_id
    join public.device_tokens dt on dt.user_id = o.user_id and dt.enabled and dt.task_reminders_enabled
    where o.status = 'pending' and o.reminder_at <= now() and t.archived_at is null
      and not exists (
        select 1 from public.reminder_deliveries rd
        where rd.occurrence_id = o.id
          and rd.device_id = dt.id
          and (rd.sent_at is not null or rd.claimed_at > now() - interval '2 minutes')
      )
    order by o.reminder_at limit 100 for update of o skip locked
  ), claimed as (
    insert into public.reminder_deliveries(occurrence_id, device_id, user_id)
    select id, device_id, user_id from due
    on conflict (occurrence_id, device_id) do update set claimed_at = now(), last_error = null
      where public.reminder_deliveries.sent_at is null
        and public.reminder_deliveries.claimed_at <= now() - interval '2 minutes'
    returning occurrence_id, device_id
  )
  select due.id, due.user_id, due.title, due.scheduled_for, due.device_id, due.token
  from due join claimed on claimed.occurrence_id = due.id and claimed.device_id = due.device_id;
$$;
revoke all on function private.claim_due_reminders() from public, anon, authenticated;
grant execute on function private.claim_due_reminders() to service_role;
