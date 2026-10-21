-- Per-user ordered change log for the Android cache. Revisions are allocated
-- under a transaction-held user row lock, so committed writes cannot appear
-- behind a client's checkpoint after a concurrent transaction commits first.
create table private.system_sync_state (
  user_id uuid primary key references auth.users(id) on delete cascade,
  revision bigint not null default 0 check (revision >= 0)
);

create table private.system_occurrence_changes (
  user_id uuid not null references auth.users(id) on delete cascade,
  revision bigint not null check (revision > 0),
  operation text not null check (operation in ('upsert', 'delete')),
  occurrence_id uuid not null,
  task_id uuid,
  title text,
  notes text,
  scheduled_for timestamptz,
  all_day boolean,
  duration_minutes integer,
  kind text,
  status text,
  completed_at timestamptz,
  schedule_type text,
  priority text,
  deadline_at timestamptz,
  earliest_start timestamptz,
  latest_end timestamptz,
  preferred_start timestamptz,
  primary key (user_id, revision),
  check (
    operation = 'delete' or
    (task_id is not null and title is not null and all_day is not null and kind is not null and status is not null)
  )
);

revoke all on table private.system_sync_state, private.system_occurrence_changes from public, anon, authenticated;

create or replace function private.system_record_occurrence_change(
  p_user_id uuid,
  p_occurrence_id uuid
) returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_occurrence_user_id uuid;
  current_task_user_id uuid;
  current_task_id uuid;
  current_title text;
  current_notes text;
  current_kind text;
  current_archived_at timestamptz;
  current_scheduled_for timestamptz;
  current_all_day boolean;
  current_duration_minutes integer;
  current_status text;
  current_completed_at timestamptz;
  current_schedule_type text;
  current_priority text;
  current_deadline_at timestamptz;
  current_earliest_start timestamptz;
  current_latest_end timestamptz;
  current_preferred_start timestamptz;
  current_revision bigint;
  current_operation text := 'delete';
  visible_now boolean := false;
begin
  if p_user_id is null or p_occurrence_id is null then
    return;
  end if;

  select o.user_id, t.user_id, o.task_id, t.title, t.notes, t.kind, t.archived_at,
         o.scheduled_for, o.all_day, o.duration_minutes, o.status, o.completed_at,
         o.schedule_type, o.priority, o.deadline_at, o.earliest_start, o.latest_end, o.preferred_start
  into current_occurrence_user_id, current_task_user_id, current_task_id, current_title,
       current_notes, current_kind, current_archived_at, current_scheduled_for,
       current_all_day, current_duration_minutes, current_status, current_completed_at,
       current_schedule_type, current_priority, current_deadline_at, current_earliest_start,
       current_latest_end, current_preferred_start
  from public.task_occurrences o
  join public.tasks t on t.id = o.task_id
  where o.id = p_occurrence_id;

  if found
     and current_occurrence_user_id = p_user_id
     and current_task_user_id = p_user_id
     and current_archived_at is null
     and (
       current_status = 'pending'
       or (
         current_status = 'completed'
         and current_completed_at >= date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata'
         and current_completed_at < (date_trunc('day', now() at time zone 'Asia/Kolkata') + interval '1 day') at time zone 'Asia/Kolkata'
       )
     ) then
    visible_now := true;
    current_operation := 'upsert';
  end if;

  insert into private.system_sync_state(user_id, revision)
  values (p_user_id, 0)
  on conflict (user_id) do nothing;

  update private.system_sync_state
  set revision = revision + 1
  where user_id = p_user_id
  returning revision into current_revision;

  insert into private.system_occurrence_changes(
    user_id, revision, operation, occurrence_id,
    task_id, title, notes, scheduled_for, all_day, duration_minutes, kind, status, completed_at,
    schedule_type, priority, deadline_at, earliest_start, latest_end, preferred_start
  ) values (
    p_user_id, current_revision, current_operation, p_occurrence_id,
    case when visible_now then current_task_id end,
    case when visible_now then current_title end,
    case when visible_now then current_notes end,
    case when visible_now then current_scheduled_for end,
    case when visible_now then current_all_day end,
    case when visible_now then current_duration_minutes end,
    case when visible_now then current_kind end,
    case when visible_now then current_status end,
    case when visible_now then current_completed_at end,
    case when visible_now then current_schedule_type end,
    case when visible_now then current_priority end,
    case when visible_now then current_deadline_at end,
    case when visible_now then current_earliest_start end,
    case when visible_now then current_latest_end end,
    case when visible_now then current_preferred_start end
  );
end;
$$;

create or replace function private.system_log_occurrence_change_trigger()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op = 'DELETE' then
    perform private.system_record_occurrence_change(old.user_id, old.id);
    return old;
  elsif tg_op = 'UPDATE' then
    if old.user_id is distinct from new.user_id then
      perform private.system_record_occurrence_change(old.user_id, old.id);
    end if;
    perform private.system_record_occurrence_change(new.user_id, new.id);
    return new;
  else
    perform private.system_record_occurrence_change(new.user_id, new.id);
    return new;
  end if;
end;
$$;

drop trigger if exists system_occurrence_delta_log on public.task_occurrences;
create trigger system_occurrence_delta_log
after insert or update or delete on public.task_occurrences
for each row execute function private.system_log_occurrence_change_trigger();

create or replace function private.system_log_task_projection_changes()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  affected_occurrence_id uuid;
begin
  for affected_occurrence_id in
    select id from public.task_occurrences where task_id = new.id order by id
  loop
    perform private.system_record_occurrence_change(new.user_id, affected_occurrence_id);
  end loop;
  return new;
end;
$$;

drop trigger if exists system_task_delta_log on public.tasks;
create trigger system_task_delta_log
after update of title, notes, kind, archived_at on public.tasks
for each row
when (
  old.title is distinct from new.title
  or old.notes is distinct from new.notes
  or old.kind is distinct from new.kind
  or old.archived_at is distinct from new.archived_at
)
execute function private.system_log_task_projection_changes();

revoke all on function private.system_record_occurrence_change(uuid, uuid) from public, anon, authenticated;
revoke all on function private.system_log_occurrence_change_trigger() from public, anon, authenticated;
revoke all on function private.system_log_task_projection_changes() from public, anon, authenticated;

create or replace function public.system_current_sync_revision()
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, private
as $$
declare
  current_user_id uuid := auth.uid();
begin
  if current_user_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;
  return jsonb_build_object(
    'revision', coalesce((select revision from private.system_sync_state where user_id = current_user_id), 0)
  );
end;
$$;

create or replace function public.system_get_occurrence_changes(
  p_after_revision bigint,
  p_limit integer default 250
) returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, private
as $$
declare
  current_user_id uuid := auth.uid();
begin
  if current_user_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;
  if p_after_revision is null or p_after_revision < 0 or p_limit is null or p_limit not between 1 and 500 then
    raise exception 'Invalid occurrence change cursor' using errcode = '22023';
  end if;

  return (
    with current_state as (
      select coalesce((
        select revision from private.system_sync_state where user_id = current_user_id
      ), 0)::bigint as revision
    ), ordered_changes as (
      select changes.*, row_number() over (order by changes.revision) as row_number
      from private.system_occurrence_changes changes
      where changes.user_id = current_user_id and changes.revision > p_after_revision
      order by changes.revision
      limit p_limit + 1
    ), page_summary as (
      select
        coalesce(jsonb_agg(
          jsonb_build_object(
            'revision', revision,
            'operation', operation,
            'occurrence_id', occurrence_id,
            'occurrence', case when operation = 'upsert' then jsonb_build_object(
              'id', occurrence_id,
              'task_id', task_id,
              'title', title,
              'notes', notes,
              'kind', kind,
              'scheduled_for', scheduled_for,
              'all_day', all_day,
              'duration_minutes', duration_minutes,
              'status', status,
              'completed_at', completed_at,
              'schedule_type', schedule_type,
              'priority', priority,
              'deadline_at', deadline_at,
              'earliest_start', earliest_start,
              'latest_end', latest_end,
              'preferred_start', preferred_start
            ) else null end
          ) order by revision
        ) filter (where row_number <= p_limit), '[]'::jsonb) as changes,
        coalesce(max(row_number) > p_limit, false) as has_more
      from ordered_changes
    )
    select jsonb_build_object(
      'current_revision', current_state.revision,
      'changes', page_summary.changes,
      'has_more', page_summary.has_more
    )
    from current_state cross join page_summary
  );
end;
$$;

revoke all on function public.system_current_sync_revision() from public, anon;
revoke all on function public.system_get_occurrence_changes(bigint, integer) from public, anon;
grant execute on function public.system_current_sync_revision() to authenticated;
grant execute on function public.system_get_occurrence_changes(bigint, integer) to authenticated;
