-- Push refreshes are a latency optimization; the Android sync worker remains
-- the source of recovery. A missing secret or an enqueue failure must never
-- prevent a task, occurrence, schedule, or goal mutation from committing.
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
    begin
      perform private.system_invoke_edge_function(
        'sync-push',
        jsonb_build_object(
          'table', tg_table_name,
          'operation', lower(tg_op),
          'record', jsonb_build_object('user_id', owner_id)
        )
      );
    exception when others then
      raise warning 'SYSTEM cache refresh was not queued for %.%: %',
        tg_table_name, lower(tg_op), sqlerrm;
    end;
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;
revoke all on function private.system_refresh_widget_after_change() from public, anon, authenticated;
