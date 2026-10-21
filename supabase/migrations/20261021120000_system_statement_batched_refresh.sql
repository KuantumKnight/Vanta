-- A bulk occurrence statement should request at most one refresh per affected
-- owner. The refresh is still best effort; the database write remains primary.
create or replace function private.system_refresh_widget_owners(
  p_table text,
  p_operation text,
  p_owner_ids uuid[]
) returns void
language plpgsql
security definer
set search_path = pg_catalog, public, private
as $$
declare
  owner_id uuid;
begin
  for owner_id in
    select distinct candidate
    from unnest(coalesce(p_owner_ids, '{}'::uuid[])) as owners(candidate)
    where candidate is not null
  loop
    begin
      perform private.system_invoke_edge_function(
        'sync-push',
        jsonb_build_object(
          'table', p_table,
          'operation', p_operation,
          'record', jsonb_build_object('user_id', owner_id)
        )
      );
    exception when others then
      raise warning 'SYSTEM cache refresh was not queued for %.%: %',
        p_table, p_operation, sqlerrm;
    end;
  end loop;
end;
$$;

create or replace function private.system_refresh_occurrences_after_insert()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, private
as $$
declare
  owners uuid[];
begin
  select array_agg(distinct user_id) into owners from new_rows where user_id is not null;
  perform private.system_refresh_widget_owners(tg_table_name, lower(tg_op), owners);
  return null;
end;
$$;

create or replace function private.system_refresh_occurrences_after_update()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, private
as $$
declare
  owners uuid[];
begin
  select array_agg(distinct user_id) into owners
  from (
    select user_id from old_rows
    union
    select user_id from new_rows
  ) affected
  where user_id is not null;
  perform private.system_refresh_widget_owners(tg_table_name, lower(tg_op), owners);
  return null;
end;
$$;

create or replace function private.system_refresh_occurrences_after_delete()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, private
as $$
declare
  owners uuid[];
begin
  select array_agg(distinct user_id) into owners from old_rows where user_id is not null;
  perform private.system_refresh_widget_owners(tg_table_name, lower(tg_op), owners);
  return null;
end;
$$;

drop trigger if exists system_occurrences_widget_refresh on public.task_occurrences;
drop trigger if exists system_occurrences_widget_refresh_insert on public.task_occurrences;
drop trigger if exists system_occurrences_widget_refresh_update on public.task_occurrences;
drop trigger if exists system_occurrences_widget_refresh_delete on public.task_occurrences;
create trigger system_occurrences_widget_refresh_insert
after insert on public.task_occurrences
referencing new table as new_rows
for each statement execute function private.system_refresh_occurrences_after_insert();
create trigger system_occurrences_widget_refresh_update
after update on public.task_occurrences
referencing old table as old_rows new table as new_rows
for each statement execute function private.system_refresh_occurrences_after_update();
create trigger system_occurrences_widget_refresh_delete
after delete on public.task_occurrences
referencing old table as old_rows
for each statement execute function private.system_refresh_occurrences_after_delete();

revoke all on function private.system_refresh_widget_owners(text, text, uuid[]) from public, anon, authenticated;
revoke all on function private.system_refresh_occurrences_after_insert() from public, anon, authenticated;
revoke all on function private.system_refresh_occurrences_after_update() from public, anon, authenticated;
revoke all on function private.system_refresh_occurrences_after_delete() from public, anon, authenticated;
