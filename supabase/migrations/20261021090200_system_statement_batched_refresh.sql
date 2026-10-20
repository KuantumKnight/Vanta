-- Refresh signals are hints. A missing Vault secret or pg_net failure must not
-- roll back the authoritative task mutation. Statement triggers also collapse
-- bulk recurrence inserts into one push request per affected owner.
drop trigger if exists system_tasks_widget_refresh on public.tasks;
drop trigger if exists system_occurrences_widget_refresh on public.task_occurrences;
drop trigger if exists system_schedules_widget_refresh on public.habit_schedules;
drop trigger if exists system_goals_widget_refresh on public.goals;
drop trigger if exists system_goal_links_widget_refresh on public.goal_links;

create or replace function private.system_refresh_widget_after_change()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, private
as $$
declare
  owner_id uuid;
begin
  if tg_op = 'INSERT' then
    for owner_id in execute 'select distinct user_id from new_rows where user_id is not null' loop
      begin
        perform private.system_invoke_edge_function(
          'sync-push', jsonb_build_object('record', jsonb_build_object('user_id', owner_id))
        );
      exception when others then
        raise warning 'SYSTEM refresh signal failed for %.% (SQLSTATE %).', tg_table_name, tg_op, sqlstate;
      end;
    end loop;
  elsif tg_op = 'UPDATE' then
    for owner_id in execute 'select user_id from old_rows where user_id is not null union select user_id from new_rows where user_id is not null' loop
      begin
        perform private.system_invoke_edge_function(
          'sync-push', jsonb_build_object('record', jsonb_build_object('user_id', owner_id))
        );
      exception when others then
        raise warning 'SYSTEM refresh signal failed for %.% (SQLSTATE %).', tg_table_name, tg_op, sqlstate;
      end;
    end loop;
  else
    for owner_id in execute 'select distinct user_id from old_rows where user_id is not null' loop
      begin
        perform private.system_invoke_edge_function(
          'sync-push', jsonb_build_object('record', jsonb_build_object('user_id', owner_id))
        );
      exception when others then
        raise warning 'SYSTEM refresh signal failed for %.% (SQLSTATE %).', tg_table_name, tg_op, sqlstate;
      end;
    end loop;
  end if;
  return null;
end;
$$;
revoke all on function private.system_refresh_widget_after_change() from public, anon, authenticated;

create trigger system_tasks_widget_refresh_insert after insert on public.tasks
  referencing new table as new_rows for each statement
  execute function private.system_refresh_widget_after_change();
create trigger system_tasks_widget_refresh_update after update on public.tasks
  referencing old table as old_rows new table as new_rows for each statement
  execute function private.system_refresh_widget_after_change();
create trigger system_tasks_widget_refresh_delete after delete on public.tasks
  referencing old table as old_rows for each statement
  execute function private.system_refresh_widget_after_change();

create trigger system_occurrences_widget_refresh_insert after insert on public.task_occurrences
  referencing new table as new_rows for each statement
  execute function private.system_refresh_widget_after_change();
create trigger system_occurrences_widget_refresh_update after update on public.task_occurrences
  referencing old table as old_rows new table as new_rows for each statement
  execute function private.system_refresh_widget_after_change();
create trigger system_occurrences_widget_refresh_delete after delete on public.task_occurrences
  referencing old table as old_rows for each statement
  execute function private.system_refresh_widget_after_change();

create trigger system_schedules_widget_refresh_insert after insert on public.habit_schedules
  referencing new table as new_rows for each statement
  execute function private.system_refresh_widget_after_change();
create trigger system_schedules_widget_refresh_update after update on public.habit_schedules
  referencing old table as old_rows new table as new_rows for each statement
  execute function private.system_refresh_widget_after_change();
create trigger system_schedules_widget_refresh_delete after delete on public.habit_schedules
  referencing old table as old_rows for each statement
  execute function private.system_refresh_widget_after_change();

create trigger system_goals_widget_refresh_insert after insert on public.goals
  referencing new table as new_rows for each statement
  execute function private.system_refresh_widget_after_change();
create trigger system_goals_widget_refresh_update after update on public.goals
  referencing old table as old_rows new table as new_rows for each statement
  execute function private.system_refresh_widget_after_change();
create trigger system_goals_widget_refresh_delete after delete on public.goals
  referencing old table as old_rows for each statement
  execute function private.system_refresh_widget_after_change();

create trigger system_goal_links_widget_refresh_insert after insert on public.goal_links
  referencing new table as new_rows for each statement
  execute function private.system_refresh_widget_after_change();
create trigger system_goal_links_widget_refresh_update after update on public.goal_links
  referencing old table as old_rows new table as new_rows for each statement
  execute function private.system_refresh_widget_after_change();
create trigger system_goal_links_widget_refresh_delete after delete on public.goal_links
  referencing old table as old_rows for each statement
  execute function private.system_refresh_widget_after_change();
