-- Hosted acceptance suite. Run only after the reviewed migrations are applied.
-- Execute as the database owner through an explicit transaction. Every fixture
-- write is rolled back; do not remove the final ROLLBACK.

begin;

select set_config('vanta.acceptance.owner_a', gen_random_uuid()::text, true);
select set_config('vanta.acceptance.owner_b', gen_random_uuid()::text, true);
select set_config('vanta.acceptance.run_id', gen_random_uuid()::text, true);

-- Make refresh enqueue fail only inside this transaction. The final rollback
-- restores these Vault rows and prevents any queued network request from being
-- committed. This verifies that refresh signaling cannot undo task writes.
delete from vault.secrets where name in ('system_project_url', 'system_internal_secret');

insert into auth.users (id, aud, role, email, email_confirmed_at)
values
  (
    current_setting('vanta.acceptance.owner_a')::uuid,
    'authenticated', 'authenticated',
    'vanta-acceptance-a+' || current_setting('vanta.acceptance.run_id') || '@example.invalid',
    now()
  ),
  (
    current_setting('vanta.acceptance.owner_b')::uuid,
    'authenticated', 'authenticated',
    'vanta-acceptance-b+' || current_setting('vanta.acceptance.run_id') || '@example.invalid',
    now()
  );

set local role authenticated;
select set_config('request.jwt.claim.sub', current_setting('vanta.acceptance.owner_a'), true);

do $acceptance$
declare
  owner_a constant uuid := current_setting('vanta.acceptance.owner_a')::uuid;
  owner_b constant uuid := current_setting('vanta.acceptance.owner_b')::uuid;
  marker constant text := 'VANTA_ACCEPTANCE_' || replace(current_setting('vanta.acceptance.run_id'), '-', '');
  create_operation constant uuid := '20000000-0000-4000-8000-000000000001';
  complete_operation constant uuid := '20000000-0000-4000-8000-000000000002';
  lost_response_key constant uuid := '20000000-0000-4000-8000-000000000003';
  due_at timestamptz := now() + interval '1 day';
  create_result jsonb;
  create_replay jsonb;
  complete_result jsonb;
  complete_replay jsonb;
  lost_response_create jsonb;
  android_first public.task_occurrences;
  android_replay public.task_occurrences;
  v_task_id uuid;
  v_occurrence_id uuid;
  lost_response_occurrence_id uuid;
  history_occurrence_id uuid;
  completed_history_at timestamptz;
  habit_result jsonb;
  updated_habit jsonb;
  goal_result jsonb;
  v_goal_id uuid;
  v_schedule_id uuid;
  updated_schedule_id uuid;
  schedule_start timestamptz;
  updated_start timestamptz := now() + interval '12 days';
  inserted_count integer;
  visible_count integer;
  affected_count integer;
  device_one uuid;
  device_two uuid;
  reassigned_device uuid;
  token_one text := marker || '_TOKEN_A';
  token_two text := marker || '_TOKEN_B';
  rejected boolean;
begin
  create_result := public.mcp_create_task(
    create_operation, marker || '_TASK', null, due_at, false, null
  );
  create_replay := public.mcp_create_task(
    create_operation, marker || '_TASK', null, due_at, false, null
  );
  if create_result is distinct from create_replay then
    raise exception 'MCP create replay differed';
  end if;
  v_task_id := (create_result #>> '{task,id}')::uuid;
  v_occurrence_id := (create_result #>> '{occurrence,id}')::uuid;
  if v_task_id is null or v_occurrence_id is null then
    raise exception 'MCP create did not return task and occurrence IDs';
  end if;
  if (select count(*) from public.tasks as t where t.id = v_task_id and t.title = marker || '_TASK') <> 1 then
    raise exception 'Idempotent MCP create did not persist exactly one task';
  end if;

  rejected := false;
  begin
    perform public.mcp_create_task(
      create_operation, marker || '_CHANGED_PAYLOAD', null, due_at, false, null
    );
  exception when sqlstate '22023' then
    rejected := true;
  end;
  if not rejected then raise exception 'Reusing a create operation ID with changed input was allowed'; end if;

  complete_result := public.mcp_complete_task(complete_operation, v_occurrence_id);
  complete_replay := public.mcp_complete_task(complete_operation, v_occurrence_id);
  if complete_result is distinct from complete_replay then
    raise exception 'MCP completion replay differed';
  end if;

  -- This independent occurrence is completed by the first Android RPC. The
  -- second call models retry after the commit succeeded but its HTTP response
  -- was lost before Room could remove the queued completion.
  lost_response_create := public.mcp_create_task(
    gen_random_uuid(), marker || '_ANDROID_LOST_RESPONSE', null,
    now() + interval '2 days', false, null
  );
  lost_response_occurrence_id := (lost_response_create #>> '{occurrence,id}')::uuid;
  select * into strict android_first
  from public.complete_task_occurrence(lost_response_occurrence_id, lost_response_key);
  select * into strict android_replay
  from public.complete_task_occurrence(lost_response_occurrence_id, lost_response_key);
  if android_first.id is distinct from android_replay.id
      or android_first.status <> 'completed'
      or android_first.completed_at is distinct from android_replay.completed_at
  then
    raise exception 'Android completion replay after server commit was not idempotent';
  end if;

  -- An occurrence completed by another client is also an already-applied
  -- success, even when the queued Android key has not been seen before.
  select * into strict android_first
  from public.complete_task_occurrence(v_occurrence_id, gen_random_uuid());
  if android_first.status <> 'completed' then
    raise exception 'An already-completed occurrence was not accepted as success';
  end if;

  rejected := false;
  begin
    perform public.complete_task_occurrence(gen_random_uuid(), gen_random_uuid());
  exception when sqlstate 'P0002' then
    rejected := true;
  end;
  if not rejected then raise exception 'A deleted/missing occurrence was not reported as permanent failure'; end if;

  habit_result := public.mcp_create_habit(
    gen_random_uuid(), marker || '_HABIT', null,
    now() + interval '2 days', 'FREQ=DAILY;COUNT=3',
    '{}'::timestamptz[], '{}'::timestamptz[], null
  );
  v_schedule_id := (habit_result #>> '{schedule,id}')::uuid;
  schedule_start := (habit_result #>> '{schedule,dtstart}')::timestamptz;
  v_task_id := (habit_result #>> '{task,id}')::uuid;
  if v_schedule_id is null or schedule_start is null or v_task_id is null then
    raise exception 'create_habit did not return an active schedule and task';
  end if;
  inserted_count := public.system_materialize_schedule_occurrences(
    v_schedule_id,
    array[schedule_start, schedule_start + interval '1 day', schedule_start + interval '2 days']::timestamptz[]
  );
  if inserted_count <> 3 then raise exception 'Expected 3 habit occurrences, inserted %', inserted_count; end if;
  if (select count(*) from public.task_occurrences as o where o.schedule_id = v_schedule_id and o.user_id = owner_a) <> 3 then
    raise exception 'Habit occurrence rows were not created for the owner';
  end if;
  if public.system_materialize_schedule_occurrences(
    v_schedule_id,
    array[schedule_start, schedule_start + interval '1 day', schedule_start + interval '2 days']::timestamptz[]
  ) <> 0 then
    raise exception 'Schedule materialization did not honor the schedule/time unique key';
  end if;

  select o.id into strict history_occurrence_id
  from public.task_occurrences as o
  where o.schedule_id = v_schedule_id and o.scheduled_for = schedule_start;
  select completed_at into completed_history_at
  from public.complete_task_occurrence(history_occurrence_id, gen_random_uuid());
  if completed_history_at is null then raise exception 'Habit history completion did not persist'; end if;

  goal_result := public.mcp_create_goal(gen_random_uuid(), marker || '_GOAL', null);
  v_goal_id := (goal_result ->> 'id')::uuid;
  if v_goal_id is null then raise exception 'MCP goal create did not return an ID'; end if;
  perform public.mcp_link_goal_task(gen_random_uuid(), v_goal_id, v_task_id, 3);

  -- Direct occurrence table writes remain revoked even though the owner can
  -- use the narrow owner-checked materialization and completion RPCs.
  rejected := false;
  begin
    insert into public.task_occurrences(task_id, user_id, scheduled_for, all_day)
    values (v_task_id, owner_a, due_at + interval '1 day', false);
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then raise exception 'Authenticated direct occurrence INSERT was allowed'; end if;
  rejected := false;
  begin
    update public.task_occurrences set status = 'skipped' where id = history_occurrence_id;
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then raise exception 'Authenticated direct occurrence UPDATE was allowed'; end if;
  rejected := false;
  begin
    delete from public.task_occurrences where id = history_occurrence_id;
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then raise exception 'Authenticated direct occurrence DELETE was allowed'; end if;

  -- Simulate a different authenticated account. RLS must hide task, habit,
  -- goal, and goal-link rows; RPCs must not let B mutate A's records.
  perform set_config('request.jwt.claim.sub', owner_b::text, true);
  if (select count(*) from public.tasks as t where t.id = v_task_id) <> 0 then
    raise exception 'Authenticated user read another user task';
  end if;
  rejected := false;
  begin
    perform public.mcp_update_task(
      gen_random_uuid(), v_task_id, marker || '_TASK_TAMPER', true,
      null, false, null, false, false, null, false, false, false
    );
  exception when sqlstate 'P0002' then
    rejected := true;
  end;
  if not rejected then raise exception 'Cross-user task update RPC was allowed'; end if;

  if (select count(*) from public.habit_schedules as s where s.id = v_schedule_id) <> 0 then
    raise exception 'Authenticated user read another user habit schedule';
  end if;
  rejected := false;
  begin
    perform public.system_materialize_schedule_occurrences(v_schedule_id, array[schedule_start]::timestamptz[]);
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then raise exception 'Cross-user schedule materialization was allowed'; end if;
  rejected := false;
  begin
    perform public.mcp_update_habit_schedule(
      gen_random_uuid(), v_task_id, updated_start, updated_start,
      'FREQ=DAILY;COUNT=2', '{}'::timestamptz[], '{}'::timestamptz[], null
    );
  exception when sqlstate 'P0002' then
    rejected := true;
  end;
  if not rejected then raise exception 'Cross-user habit schedule update was allowed'; end if;

  if (select count(*) from public.goals as g where g.id = v_goal_id) <> 0 then
    raise exception 'Authenticated user read another user goal';
  end if;
  if (select count(*) from public.goal_links as gl where gl.goal_id = v_goal_id) <> 0 then
    raise exception 'Authenticated user read another user goal link';
  end if;
  update public.goals as g set title = marker || '_GOAL_TAMPER' where g.id = v_goal_id;
  get diagnostics affected_count = row_count;
  if affected_count <> 0 then raise exception 'Cross-user goal table update affected a row'; end if;
  rejected := false;
  begin
    perform public.mcp_update_goal(gen_random_uuid(), v_goal_id, marker || '_GOAL_TAMPER', null, true, false);
  exception when sqlstate 'P0002' then
    rejected := true;
  end;
  if not rejected then raise exception 'Cross-user goal update RPC was allowed'; end if;

  perform set_config('request.jwt.claim.sub', owner_a::text, true);
  if (select count(*) from public.tasks as t where t.id = v_task_id and t.title = marker || '_HABIT') <> 1 then
    raise exception 'Another user changed the task';
  end if;
  if (select count(*) from public.goals as g where g.id = v_goal_id and g.title = marker || '_GOAL') <> 1 then
    raise exception 'Another user changed the goal';
  end if;
  if (select count(*) from public.goal_links as gl where gl.goal_id = v_goal_id and gl.task_id = v_task_id and gl.target_count = 3) <> 1 then
    raise exception 'Owner goal/task link was not preserved';
  end if;

  updated_habit := public.mcp_update_habit_schedule(
    gen_random_uuid(), v_task_id, updated_start, updated_start,
    'FREQ=DAILY;COUNT=2', '{}'::timestamptz[], '{}'::timestamptz[], null
  );
  updated_schedule_id := (updated_habit #>> '{schedule,id}')::uuid;
  if updated_schedule_id is null then raise exception 'update_habit_schedule did not return a new schedule'; end if;
  if (select count(*) from public.task_occurrences
      where id = history_occurrence_id and status = 'completed' and completed_at = completed_history_at) <> 1
  then
    raise exception 'Completed habit history was not preserved by schedule update';
  end if;
  if public.system_materialize_schedule_occurrences(
    updated_schedule_id,
    array[updated_start, updated_start + interval '1 day']::timestamptz[]
  ) <> 2 then
    raise exception 'Updated habit schedule did not materialize';
  end if;

  device_one := public.system_register_device_token(token_one);
  if public.system_register_device_token(token_one) is distinct from device_one then
    raise exception 'Same-owner device token registration was not idempotent';
  end if;
  device_two := public.system_register_device_token(token_two);
  if device_two = device_one then raise exception 'Two phone tokens for one user collided'; end if;

  perform set_config('request.jwt.claim.sub', owner_b::text, true);
  rejected := false;
  begin
    perform public.system_register_device_token(token_one);
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then raise exception 'Active foreign-owner FCM token takeover was allowed'; end if;

  perform set_config('request.jwt.claim.sub', owner_a::text, true);
  update public.device_tokens set enabled = false where id = device_one;
  if not exists (select 1 from public.device_tokens where id = device_two and enabled) then
    raise exception 'Disabling one phone also disabled another phone';
  end if;
  perform set_config('request.jwt.claim.sub', owner_b::text, true);
  reassigned_device := public.system_register_device_token(token_one);
  if reassigned_device is distinct from device_one
      or not exists (select 1 from public.device_tokens where id = device_one and user_id = owner_b and enabled)
  then
    raise exception 'A disabled token could not be safely reassigned';
  end if;

  -- Return to A and prove an authoritative write succeeds while refresh
  -- credentials are absent in this transaction.
  perform set_config('request.jwt.claim.sub', owner_a::text, true);
  perform public.mcp_create_task(
    gen_random_uuid(), marker || '_REFRESH_FAILURE', null,
    now() + interval '3 days', false, null
  );
  if not exists (select 1 from public.tasks where user_id = owner_a and title = marker || '_REFRESH_FAILURE') then
    raise exception 'Task mutation was rolled back when refresh signaling lacked credentials';
  end if;

  if has_table_privilege('anon', 'public.tasks', 'insert') then
    raise exception 'anon can INSERT tasks';
  end if;
  if has_table_privilege('anon', 'public.task_occurrences', 'insert') then
    raise exception 'anon can INSERT task occurrences';
  end if;
  if has_table_privilege('authenticated', 'public.task_occurrences', 'insert')
      or has_table_privilege('authenticated', 'public.task_occurrences', 'update')
      or has_table_privilege('authenticated', 'public.task_occurrences', 'delete')
  then
    raise exception 'authenticated has a direct task_occurrences write privilege';
  end if;
  if has_function_privilege('anon', 'public.system_materialize_schedule_occurrences(uuid,timestamptz[])', 'execute') then
    raise exception 'anon can execute schedule materialization';
  end if;
  if not has_function_privilege('authenticated', 'public.system_materialize_schedule_occurrences(uuid,timestamptz[])', 'execute') then
    raise exception 'authenticated cannot execute owner-checked schedule materialization';
  end if;

  raise notice 'PASS: occurrence ACL, owner-safe habit create/update/materialization/history, RLS, MCP idempotency, token lifecycle, and best-effort refresh';
end
$acceptance$;

reset role;
set local role anon;
do $anon_check$
declare
  rejected boolean := false;
begin
  begin
    insert into public.tasks(user_id, title)
    values (current_setting('vanta.acceptance.owner_a')::uuid, 'VANTA_ACCEPTANCE_ANON_MUST_FAIL');
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then raise exception 'anon task mutation was allowed'; end if;
end
$anon_check$;
reset role;

rollback;
