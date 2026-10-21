-- Hosted compatibility baseline for the currently applied schema only.
-- Execute as the database owner. All synthetic records and Vault changes are
-- inside a transaction that must end with ROLLBACK.

begin;

select set_config('vanta.acceptance.owner_a', gen_random_uuid()::text, true);
select set_config('vanta.acceptance.owner_b', gen_random_uuid()::text, true);
select set_config('vanta.acceptance.run_id', gen_random_uuid()::text, true);

-- Fail refresh enqueue inside this transaction; rollback restores the secrets.
delete from vault.secrets where name in ('system_project_url', 'system_internal_secret');

insert into auth.users (id, aud, role, email, email_confirmed_at)
values
  (
    current_setting('vanta.acceptance.owner_a')::uuid,
    'authenticated', 'authenticated',
    'vanta-acceptance-pre-a+' || current_setting('vanta.acceptance.run_id') || '@example.invalid',
    now()
  ),
  (
    current_setting('vanta.acceptance.owner_b')::uuid,
    'authenticated', 'authenticated',
    'vanta-acceptance-pre-b+' || current_setting('vanta.acceptance.run_id') || '@example.invalid',
    now()
  );

set local role authenticated;
select set_config('request.jwt.claim.sub', current_setting('vanta.acceptance.owner_a'), true);

do $baseline$
declare
  owner_a constant uuid := current_setting('vanta.acceptance.owner_a')::uuid;
  owner_b constant uuid := current_setting('vanta.acceptance.owner_b')::uuid;
  marker constant text := 'VANTA_ACCEPTANCE_PRE_' || replace(current_setting('vanta.acceptance.run_id'), '-', '');
  operation_id constant uuid := '30000000-0000-4000-8000-000000000001';
  complete_operation constant uuid := '30000000-0000-4000-8000-000000000002';
  due_at timestamptz := now() + interval '1 day';
  create_result jsonb;
  create_replay jsonb;
  habit_result jsonb;
  goal_result jsonb;
  task_id uuid;
  occurrence_id uuid;
  schedule_id uuid;
  goal_id uuid;
  device_one uuid;
  device_two uuid;
  reassigned_device uuid;
  token_one text := marker || '_TOKEN_A';
  token_two text := marker || '_TOKEN_B';
  rejected boolean;
begin
  -- 90000 is intentionally pending; record that authenticated direct INSERT
  -- remains granted in the current hosted baseline, before testing its revoke.
  if not has_table_privilege('authenticated', 'public.task_occurrences', 'insert') then
    raise exception 'Baseline changed: authenticated occurrence INSERT is no longer granted';
  end if;

  create_result := public.mcp_create_task(operation_id, marker || '_TASK', null, due_at, false, null);
  create_replay := public.mcp_create_task(operation_id, marker || '_TASK', null, due_at, false, null);
  if create_result is distinct from create_replay then raise exception 'MCP create replay differed'; end if;
  task_id := (create_result #>> '{task,id}')::uuid;
  occurrence_id := (create_result #>> '{occurrence,id}')::uuid;
  if task_id is null or occurrence_id is null then raise exception 'MCP create did not return row IDs'; end if;
  if (select count(*) from public.tasks as t where t.id = task_id and t.title = marker || '_TASK') <> 1 then
    raise exception 'Idempotent MCP create did not persist exactly one task';
  end if;

  rejected := false;
  begin
    perform public.mcp_create_task(operation_id, marker || '_CHANGED', null, due_at, false, null);
  exception when sqlstate '22023' then
    rejected := true;
  end;
  if not rejected then raise exception 'Reusing a create operation ID with changed input was allowed'; end if;

  if public.mcp_complete_task(complete_operation, occurrence_id)
      is distinct from public.mcp_complete_task(complete_operation, occurrence_id)
  then
    raise exception 'MCP completion retry differed';
  end if;

  habit_result := public.mcp_create_habit(
    gen_random_uuid(), marker || '_HABIT', null, now() + interval '2 days',
    'FREQ=DAILY;COUNT=2', '{}'::timestamptz[], '{}'::timestamptz[], null
  );
  schedule_id := (habit_result #>> '{schedule,id}')::uuid;
  task_id := (habit_result #>> '{task,id}')::uuid;
  if schedule_id is null or task_id is null then raise exception 'MCP habit creation failed'; end if;

  goal_result := public.mcp_create_goal(gen_random_uuid(), marker || '_GOAL', null);
  goal_id := (goal_result ->> 'id')::uuid;
  if goal_id is null then raise exception 'MCP goal creation failed'; end if;
  perform public.mcp_link_goal_task(gen_random_uuid(), goal_id, task_id, 2);

  -- Verify this applied function is safe before the older pending migration
  -- is applied after it. Same-owner retries and multiple devices must work.
  device_one := public.system_register_device_token(token_one);
  if public.system_register_device_token(token_one) is distinct from device_one then
    raise exception 'Same-owner token registration was not idempotent';
  end if;
  device_two := public.system_register_device_token(token_two);
  if device_two = device_one then raise exception 'Two phone tokens collided'; end if;

  perform set_config('request.jwt.claim.sub', owner_b::text, true);
  if (select count(*) from public.tasks where id = task_id) <> 0 then raise exception 'Cross-user task read succeeded'; end if;
  if (select count(*) from public.habit_schedules where id = schedule_id) <> 0 then raise exception 'Cross-user habit read succeeded'; end if;
  if (select count(*) from public.goals where id = goal_id) <> 0 then raise exception 'Cross-user goal read succeeded'; end if;

  rejected := false;
  begin
    perform public.mcp_update_task(
      gen_random_uuid(), task_id, marker || '_TASK_TAMPER', true,
      null, false, null, false, false, null, false, false, false
    );
  exception when sqlstate 'P0002' then
    rejected := true;
  end;
  if not rejected then raise exception 'Cross-user task update was allowed'; end if;

  rejected := false;
  begin
    perform public.mcp_update_habit_schedule(
      gen_random_uuid(), task_id, now() + interval '15 days', now() + interval '15 days',
      'FREQ=DAILY;COUNT=2', '{}'::timestamptz[], '{}'::timestamptz[], null
    );
  exception when sqlstate 'P0002' then
    rejected := true;
  end;
  if not rejected then raise exception 'Cross-user habit update was allowed'; end if;

  rejected := false;
  begin
    perform public.mcp_update_goal(gen_random_uuid(), goal_id, marker || '_GOAL_TAMPER', null, true, false);
  exception when sqlstate 'P0002' then
    rejected := true;
  end;
  if not rejected then raise exception 'Cross-user goal update was allowed'; end if;

  rejected := false;
  begin
    perform public.system_register_device_token(token_one);
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then raise exception 'Active foreign-owner device token takeover was allowed'; end if;

  perform set_config('request.jwt.claim.sub', owner_a::text, true);
  update public.device_tokens set enabled = false where id = device_one;
  if not exists (select 1 from public.device_tokens where id = device_two and enabled) then
    raise exception 'Disabling one token affected the other device';
  end if;

  perform set_config('request.jwt.claim.sub', owner_b::text, true);
  reassigned_device := public.system_register_device_token(token_one);
  if reassigned_device is distinct from device_one
      or not exists (select 1 from public.device_tokens where id = device_one and user_id = owner_b and enabled)
  then
    raise exception 'Disabled token reassignment failed';
  end if;

  perform set_config('request.jwt.claim.sub', owner_a::text, true);
  perform public.mcp_create_task(gen_random_uuid(), marker || '_REFRESH_FAILURE', null, due_at, false, null);
  if not exists (select 1 from public.tasks where user_id = owner_a and title = marker || '_REFRESH_FAILURE') then
    raise exception 'Task mutation failed when refresh credentials were absent';
  end if;

  if has_table_privilege('anon', 'public.tasks', 'insert') then raise exception 'anon can INSERT tasks'; end if;
  raise notice 'PASS: applied-schema idempotency, RLS, token lifecycle ordering, and refresh isolation';
end
$baseline$;

reset role;
set local role anon;
do $anon_check$
declare
  rejected boolean := false;
begin
  begin
    insert into public.tasks(user_id, title)
    values (current_setting('vanta.acceptance.owner_a')::uuid, 'VANTA_ACCEPTANCE_PRE_ANON_MUST_FAIL');
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then raise exception 'anon task mutation was allowed'; end if;
end
$anon_check$;
reset role;

rollback;
