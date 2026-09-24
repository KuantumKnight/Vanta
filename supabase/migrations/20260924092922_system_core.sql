create schema if not exists private;
revoke all on schema private from public, anon, authenticated;
grant usage on schema private to service_role;

create or replace function private.is_safe_rrule(value text)
returns boolean language plpgsql immutable set search_path = pg_catalog as $$
declare
  token text;
  field_name text;
  frequency text;
  interval_text text;
  bysecond_text text;
  byminute_text text;
  byhour_text text;
  minute_values integer[];
  second_values integer[];
  hour_values integer[];
  candidate_values integer[];
  candidate_index integer;
  candidate_count integer;
  candidate_span integer;
  second_count integer;
  minute_count integer;
  hour_count integer;
  raw_count integer;
  interval_value integer := 1;
begin
  if value is null or length(value) > 2048 then return false; end if;
  foreach token in array string_to_array(upper(value), ';') loop
    if token !~ '^[A-Z][A-Z0-9-]*=.+$' then return false; end if;
    field_name := split_part(token, '=', 1);
    if field_name <> all(array['FREQ','UNTIL','COUNT','INTERVAL','BYSECOND','BYMINUTE','BYHOUR','BYDAY','BYMONTHDAY','BYYEARDAY','BYWEEKNO','BYMONTH','BYSETPOS','WKST']) then return false; end if;
    if (select count(*) from unnest(string_to_array(upper(value), ';')) as fields(item)
        where split_part(item, '=', 1) = field_name) > 1 then return false; end if;
    if token like 'FREQ=%' then frequency := substring(token from 6); end if;
    if token like 'INTERVAL=%' then interval_text := substring(token from 10); end if;
    if token like 'BYSECOND=%' then bysecond_text := substring(token from 10); end if;
    if token like 'BYMINUTE=%' then byminute_text := substring(token from 10); end if;
    if token like 'BYHOUR=%' then byhour_text := substring(token from 8); end if;
  end loop;
  if frequency is null or frequency not in ('SECONDLY','MINUTELY','HOURLY','DAILY','WEEKLY','MONTHLY','YEARLY') then return false; end if;
  if position('COUNT=' in upper(value)) > 0 and position('UNTIL=' in upper(value)) > 0 then return false; end if;
  if interval_text is not null then
    if interval_text !~ '^[0-9]+$' then return false; end if;
    interval_value := interval_text::integer;
  end if;
  if interval_value < 1 then return false; end if;
  if bysecond_text is not null and bysecond_text !~ '^[0-9]+(,[0-9]+)*$' then return false; end if;
  if byminute_text is not null and byminute_text !~ '^[0-9]+(,[0-9]+)*$' then return false; end if;
  if byhour_text is not null and byhour_text !~ '^[0-9]+(,[0-9]+)*$' then return false; end if;

  if bysecond_text is null then second_values := array[0]; second_count := 1;
  else
    select array_agg(item_text::integer order by item_text::integer), count(*), count(distinct item_text::integer)
      into second_values, second_count, raw_count
      from unnest(string_to_array(bysecond_text, ',')) as valueset(item_text);
    if raw_count <> second_count or exists (select 1 from unnest(second_values) as valueset(item) where item > 60) then return false; end if;
  end if;
  if byminute_text is null then minute_values := array[0]; minute_count := 1;
  else
    select array_agg(item_text::integer order by item_text::integer), count(*), count(distinct item_text::integer)
      into minute_values, minute_count, raw_count
      from unnest(string_to_array(byminute_text, ',')) as valueset(item_text);
    if raw_count <> minute_count or exists (select 1 from unnest(minute_values) as valueset(item) where item > 59) then return false; end if;
  end if;
  if byhour_text is null then hour_values := array[0]; hour_count := 1;
  else
    select array_agg(item_text::integer order by item_text::integer), count(*), count(distinct item_text::integer)
      into hour_values, hour_count, raw_count
      from unnest(string_to_array(byhour_text, ',')) as valueset(item_text);
    if raw_count <> hour_count or exists (select 1 from unnest(hour_values) as valueset(item) where item > 23) then return false; end if;
  end if;

  if frequency = 'SECONDLY' and interval_value < 900 then return false; end if;
  if frequency = 'MINUTELY' and interval_value < 15 then return false; end if;
  if frequency in ('SECONDLY','MINUTELY') then
    if second_count > 1 then return false; end if;
  elsif frequency = 'HOURLY' then
    if minute_count * second_count > 10000 then return false; end if;
    select array_agg(minute_value * 60 + second_value order by minute_value * 60 + second_value)
      into candidate_values
      from unnest(minute_values) as minutes(minute_value)
      cross join unnest(second_values) as seconds(second_value);
    candidate_span := 3600;
  else
    if hour_count * minute_count * second_count > 10000 then return false; end if;
    select array_agg(hour_value * 3600 + minute_value * 60 + second_value
                     order by hour_value * 3600 + minute_value * 60 + second_value)
      into candidate_values
      from unnest(hour_values) as hours(hour_value)
      cross join unnest(minute_values) as minutes(minute_value)
      cross join unnest(second_values) as seconds(second_value);
    candidate_span := 86400;
  end if;
  candidate_count := coalesce(array_length(candidate_values, 1), 0);
  if candidate_count > 10000 then return false; end if;
  if candidate_count > 1 then
    for candidate_index in 1..candidate_count - 1 loop
      if candidate_values[candidate_index + 1] - candidate_values[candidate_index] < 900 then return false; end if;
    end loop;
    if candidate_values[1] + candidate_span - candidate_values[candidate_count] < 900 then return false; end if;
  end if;
  return case frequency
    when 'SECONDLY' then true
    when 'MINUTELY' then true
    else true
  end;
exception when others then return false;
end;
$$;

create table public.tasks (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  title text not null check (length(btrim(title)) between 1 and 200),
  notes text check (notes is null or length(notes) <= 4000),
  kind text not null default 'task' check (kind in ('task', 'habit')),
  archived_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (id, user_id)
);

create table public.habit_schedules (
  id uuid primary key default gen_random_uuid(),
  task_id uuid not null,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  dtstart timestamptz not null,
  timezone text not null default 'Asia/Kolkata' check (timezone = 'Asia/Kolkata'),
  reminder_time time,
  rrule text not null check (private.is_safe_rrule(rrule)),
  rdates timestamptz[] not null default '{}',
  exdates timestamptz[] not null default '{}',
  effective_from timestamptz not null,
  effective_until timestamptz,
  created_at timestamptz not null default now(),
  foreign key (task_id, user_id) references public.tasks(id, user_id) on delete cascade,
  check (effective_until is null or effective_until > effective_from),
  unique (id, user_id)
);

create table public.task_occurrences (
  id uuid primary key default gen_random_uuid(),
  task_id uuid not null,
  schedule_id uuid,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  scheduled_for timestamptz,
  all_day boolean not null default false,
  reminder_at timestamptz,
  status text not null default 'pending' check (status in ('pending', 'completed', 'skipped')),
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  foreign key (task_id, user_id) references public.tasks(id, user_id) on delete cascade,
  foreign key (schedule_id, user_id) references public.habit_schedules(id, user_id) on delete cascade,
  check (schedule_id is null or scheduled_for is not null),
  check ((status = 'completed') = (completed_at is not null)),
  check (schedule_id is not null or status <> 'skipped')
);
create unique index task_occurrences_one_off_task on public.task_occurrences(task_id) where schedule_id is null;
create unique index task_occurrences_schedule_time on public.task_occurrences(schedule_id, scheduled_for);
create index task_occurrences_user_time on public.task_occurrences(user_id, scheduled_for, status);

create table public.completion_requests (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  idempotency_key uuid not null,
  occurrence_id uuid,
  created_at timestamptz not null default now(),
  unique(user_id, idempotency_key)
);

create table public.goals (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  title text not null check (length(btrim(title)) between 1 and 200),
  description text check (description is null or length(description) <= 4000),
  created_at timestamptz not null default now(),
  unique (id, user_id)
);

create table public.goal_links (
  id uuid primary key default gen_random_uuid(),
  goal_id uuid not null,
  task_id uuid not null,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  target_count integer not null default 1 check (target_count between 1 and 1000000),
  created_at timestamptz not null default now(),
  foreign key (goal_id, user_id) references public.goals(id, user_id) on delete cascade,
  foreign key (task_id, user_id) references public.tasks(id, user_id) on delete cascade,
  unique (goal_id, task_id)
);

create table public.device_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  token text not null unique check (length(token) between 20 and 4096),
  platform text not null default 'android' check (platform = 'android'),
  enabled boolean not null default true,
  updated_at timestamptz not null default now()
);

create table public.reminder_deliveries (
  id uuid primary key default gen_random_uuid(),
  occurrence_id uuid not null references public.task_occurrences(id) on delete cascade,
  device_id uuid not null references public.device_tokens(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  claimed_at timestamptz not null default now(),
  sent_at timestamptz,
  last_error text,
  unique (occurrence_id, device_id)
);

create or replace function private.touch_updated_at()
returns trigger language plpgsql set search_path = pg_catalog as $$
begin new.updated_at := now(); return new; end;
$$;
create trigger tasks_touch_updated_at before update on public.tasks
for each row execute function private.touch_updated_at();

alter table public.tasks enable row level security;
alter table public.habit_schedules enable row level security;
alter table public.task_occurrences enable row level security;
alter table public.completion_requests enable row level security;
alter table public.goals enable row level security;
alter table public.goal_links enable row level security;
alter table public.device_tokens enable row level security;
alter table public.reminder_deliveries enable row level security;

create policy "owners manage tasks" on public.tasks for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "owners manage schedules" on public.habit_schedules for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "owners manage occurrences" on public.task_occurrences for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "owners manage completion requests" on public.completion_requests for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "owners manage goals" on public.goals for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "owners manage goal links" on public.goal_links for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "owners manage device tokens" on public.device_tokens for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "owners read reminder deliveries" on public.reminder_deliveries for select to authenticated
  using ((select auth.uid()) = user_id);

grant usage on schema public to authenticated, service_role;
grant select on public.tasks, public.habit_schedules to authenticated;
grant select, insert on public.task_occurrences to authenticated;
grant select, insert, update, delete on public.goals, public.goal_links to authenticated;
grant select, insert, update on public.device_tokens to authenticated;
grant all on public.tasks, public.habit_schedules, public.task_occurrences,
  public.completion_requests, public.goals, public.goal_links, public.device_tokens,
  public.reminder_deliveries to service_role;
revoke all on function private.is_safe_rrule(text) from public, anon, authenticated;
grant execute on function private.is_safe_rrule(text) to service_role;
grant execute on function private.touch_updated_at() to service_role;

create or replace function public.complete_task_occurrence(p_occurrence_id uuid, p_idempotency_key uuid)
returns public.task_occurrences language plpgsql security definer set search_path = public, pg_temp as $$
declare
  current_user_id uuid := auth.uid();
  previous_occurrence_id uuid;
  updated_occurrence public.task_occurrences;
begin
  if current_user_id is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  insert into public.completion_requests(user_id, idempotency_key, occurrence_id)
  values (current_user_id, p_idempotency_key, null)
  on conflict (user_id, idempotency_key) do nothing;
  select occurrence_id into previous_occurrence_id from public.completion_requests
  where user_id = current_user_id and idempotency_key = p_idempotency_key;
  if previous_occurrence_id is not null and previous_occurrence_id <> p_occurrence_id then
    raise exception 'Idempotency key was already used for another occurrence' using errcode = '22023';
  end if;
  update public.task_occurrences
  set status = 'completed', completed_at = coalesce(completed_at, now())
  where id = p_occurrence_id and user_id = current_user_id and status <> 'skipped'
  returning * into updated_occurrence;
  if not found then raise exception 'Task occurrence not found' using errcode = 'P0002'; end if;
  update public.completion_requests set occurrence_id = p_occurrence_id
  where user_id = current_user_id and idempotency_key = p_idempotency_key;
  return updated_occurrence;
end;
$$;
revoke all on function public.complete_task_occurrence(uuid, uuid) from public, anon;
grant execute on function public.complete_task_occurrence(uuid, uuid) to authenticated;

create or replace function public.skip_habit_occurrence(p_occurrence_id uuid)
returns public.task_occurrences language plpgsql security definer set search_path = public, pg_temp as $$
declare
  current_user_id uuid := auth.uid();
  skipped_occurrence public.task_occurrences;
begin
  if current_user_id is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  update public.task_occurrences set status = 'skipped'
  where id = p_occurrence_id and user_id = current_user_id and schedule_id is not null and status = 'pending'
  returning * into skipped_occurrence;
  if not found then raise exception 'Pending habit occurrence not found' using errcode = 'P0002'; end if;
  return skipped_occurrence;
end;
$$;
revoke all on function public.skip_habit_occurrence(uuid) from public, anon;
grant execute on function public.skip_habit_occurrence(uuid) to authenticated;

create or replace function private.claim_due_reminders()
returns table (occurrence_id uuid, user_id uuid, task_title text, scheduled_for timestamptz, device_id uuid, token text)
language sql security definer set search_path = public, pg_temp as $$
  with due as (
    select o.id, o.user_id, t.title, o.scheduled_for, dt.id as device_id, dt.token
    from public.task_occurrences o
    join public.tasks t on t.id = o.task_id and t.user_id = o.user_id
    join public.device_tokens dt on dt.user_id = o.user_id and dt.enabled
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

create or replace function public.system_claim_due_reminders()
returns table (occurrence_id uuid, user_id uuid, task_title text, scheduled_for timestamptz, device_id uuid, token text)
language sql security definer set search_path = public, private, pg_temp as $$
  select * from private.claim_due_reminders();
$$;
revoke all on function public.system_claim_due_reminders() from public, anon, authenticated;
grant execute on function public.system_claim_due_reminders() to service_role;

create or replace function private.finish_reminder(p_occurrence_id uuid, p_device_id uuid, p_error text default null)
returns void language sql security definer set search_path = public, pg_temp as $$
  update public.reminder_deliveries
  set sent_at = case when p_error is null then now() else sent_at end,
      last_error = left(p_error, 1000)
  where occurrence_id = p_occurrence_id and device_id = p_device_id;
$$;
revoke all on function private.finish_reminder(uuid, uuid, text) from public, anon, authenticated;
grant execute on function private.finish_reminder(uuid, uuid, text) to service_role;

create or replace function public.system_finish_reminder(p_occurrence_id uuid, p_device_id uuid, p_error text default null)
returns void language plpgsql security definer set search_path = public, private, pg_temp as $$
begin
  perform private.finish_reminder(p_occurrence_id, p_device_id, p_error);
end;
$$;
revoke all on function public.system_finish_reminder(uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.system_finish_reminder(uuid, uuid, text) to service_role;

create or replace view public.goal_progress with (security_invoker = true) as
select g.id as goal_id, g.user_id, g.title, g.description,
  coalesce(sum(least(link_progress.done_count, gl.target_count)), 0)::integer as completed_count,
  coalesce(sum(gl.target_count), 0)::integer as target_count,
  (count(gl.id) > 0 and sum(least(link_progress.done_count, gl.target_count)) >= sum(gl.target_count)) as completed
from public.goals g
left join public.goal_links gl on gl.goal_id = g.id and gl.user_id = g.user_id
left join lateral (
  select count(*)::integer as done_count from public.task_occurrences o
  where o.task_id = gl.task_id and o.user_id = gl.user_id and o.status = 'completed'
) link_progress on true
group by g.id, g.user_id, g.title, g.description;
grant select on public.goal_progress to authenticated;

revoke all on public.tasks, public.habit_schedules, public.task_occurrences,
  public.completion_requests, public.goals, public.goal_links, public.device_tokens,
  public.reminder_deliveries, public.goal_progress from anon;

create or replace function public.create_one_off_task(
  p_title text, p_notes text, p_scheduled_for timestamptz, p_all_day boolean, p_reminder_at timestamptz
) returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare
  current_user_id uuid := auth.uid();
  new_task public.tasks;
  new_occurrence public.task_occurrences;
begin
  if current_user_id is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  insert into public.tasks(user_id, title, notes, kind)
    values (current_user_id, p_title, p_notes, 'task') returning * into new_task;
  insert into public.task_occurrences(task_id, user_id, scheduled_for, all_day, reminder_at)
    values (new_task.id, current_user_id, p_scheduled_for, p_all_day, p_reminder_at)
    returning * into new_occurrence;
  return jsonb_build_object(
    'task', jsonb_build_object('id', new_task.id, 'title', new_task.title, 'kind', new_task.kind),
    'occurrence', jsonb_build_object('id', new_occurrence.id, 'scheduled_for', new_occurrence.scheduled_for,
      'all_day', new_occurrence.all_day, 'reminder_at', new_occurrence.reminder_at, 'status', new_occurrence.status)
  );
end;
$$;
revoke all on function public.create_one_off_task(text, text, timestamptz, boolean, timestamptz) from public, anon;
grant execute on function public.create_one_off_task(text, text, timestamptz, boolean, timestamptz) to authenticated;

create or replace function public.update_one_off_task(
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
) returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare
  current_user_id uuid := auth.uid();
  current_task public.tasks;
  updated_task public.tasks;
  updated_occurrence public.task_occurrences;
begin
  if current_user_id is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  select * into current_task from public.tasks
    where id = p_task_id and user_id = current_user_id for update;
  if not found then raise exception 'Task not found' using errcode = 'P0002'; end if;
  if current_task.kind = 'habit' and (p_update_schedule or p_update_reminder) then
    raise exception 'Use update_habit_schedule to change a recurring habit' using errcode = '22023';
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
    end
  where id = p_task_id and user_id = current_user_id
  returning * into updated_task;

  if p_update_schedule or p_update_reminder then
    select * into updated_occurrence from public.task_occurrences
      where task_id = p_task_id and user_id = current_user_id and schedule_id is null
      for update;
    if not found then raise exception 'Task occurrence not found' using errcode = 'P0002'; end if;
    if updated_occurrence.status <> 'pending' then
      raise exception 'Only an unfinished task occurrence can be rescheduled or reminded' using errcode = '22023';
    end if;
    update public.task_occurrences set
      scheduled_for = case when p_update_schedule then p_scheduled_for else scheduled_for end,
      all_day = case when p_update_schedule then p_all_day else all_day end,
      reminder_at = case when p_update_reminder then p_reminder_at else reminder_at end
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
        'status', updated_occurrence.status) end
  );
end;
$$;
revoke all on function public.update_one_off_task(uuid, text, boolean, text, boolean, timestamptz, boolean, boolean, timestamptz, boolean, boolean, boolean) from public, anon;
grant execute on function public.update_one_off_task(uuid, text, boolean, text, boolean, timestamptz, boolean, boolean, timestamptz, boolean, boolean, boolean) to authenticated;

create or replace function public.create_habit_with_schedule(
  p_title text, p_notes text, p_dtstart timestamptz, p_rrule text,
  p_rdates timestamptz[], p_exdates timestamptz[], p_reminder_time time
) returns setof public.habit_schedules language plpgsql security definer set search_path = public, pg_temp as $$
declare
  current_user_id uuid := auth.uid();
  new_task_id uuid;
  new_schedule public.habit_schedules;
begin
  if current_user_id is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  insert into public.tasks(user_id, title, notes, kind)
    values (current_user_id, p_title, p_notes, 'habit') returning id into new_task_id;
  insert into public.habit_schedules(task_id, user_id, dtstart, timezone, reminder_time, rrule, rdates, exdates, effective_from)
    values (new_task_id, current_user_id, p_dtstart, 'Asia/Kolkata', p_reminder_time, p_rrule,
      coalesce(p_rdates, '{}'), coalesce(p_exdates, '{}'), p_dtstart)
    returning * into new_schedule;
  return next new_schedule;
end;
$$;
revoke all on function public.create_habit_with_schedule(text, text, timestamptz, text, timestamptz[], timestamptz[], time) from public, anon;
grant execute on function public.create_habit_with_schedule(text, text, timestamptz, text, timestamptz[], timestamptz[], time) to authenticated;

create or replace function public.replace_habit_schedule(
  p_task_id uuid, p_effective_from timestamptz, p_dtstart timestamptz, p_rrule text,
  p_rdates timestamptz[], p_exdates timestamptz[], p_reminder_time time
) returns setof public.habit_schedules language plpgsql security definer set search_path = public, pg_temp as $$
declare
  current_user_id uuid := auth.uid();
  active_schedule public.habit_schedules;
  new_schedule public.habit_schedules;
begin
  if current_user_id is null then raise exception 'Authentication required' using errcode = '28000'; end if;
  select * into active_schedule from public.habit_schedules
    where task_id = p_task_id and user_id = current_user_id and effective_until is null for update;
  if not found then raise exception 'Active habit schedule not found' using errcode = 'P0002'; end if;
  if p_effective_from <= active_schedule.effective_from then
    raise exception 'The change date must be after the active schedule start' using errcode = '22023';
  end if;
  update public.habit_schedules set effective_until = p_effective_from where id = active_schedule.id;
  update public.task_occurrences set status = 'skipped'
    where schedule_id = active_schedule.id and scheduled_for >= p_effective_from and status = 'pending';
  insert into public.habit_schedules(task_id, user_id, dtstart, timezone, reminder_time, rrule, rdates, exdates, effective_from)
    values (p_task_id, current_user_id, p_dtstart, 'Asia/Kolkata', p_reminder_time, p_rrule,
      coalesce(p_rdates, '{}'), coalesce(p_exdates, '{}'), p_effective_from)
    returning * into new_schedule;
  return next new_schedule;
end;
$$;
revoke all on function public.replace_habit_schedule(uuid, timestamptz, timestamptz, text, timestamptz[], timestamptz[], time) from public, anon;
grant execute on function public.replace_habit_schedule(uuid, timestamptz, timestamptz, text, timestamptz[], timestamptz[], time) to authenticated;
