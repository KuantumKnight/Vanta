-- Hosted acceptance check for the personal project account.
-- Run with: npx supabase db query --linked --project-ref <project-ref> --file supabase/acceptance_rollback.sql
-- Every insert/update is inside one transaction and the final statement rolls it back.

begin;

select set_config(
  'request.jwt.claim.sub',
  (select id::text from auth.users order by created_at limit 1),
  true
) is not null as owner_claim_set;
set local role authenticated;

do $acceptance$
declare
  owner_id uuid := auth.uid();
  create_op uuid := gen_random_uuid();
  complete_op uuid := gen_random_uuid();
  android_key uuid := gen_random_uuid();
  create_result jsonb;
  create_replay jsonb;
  complete_result jsonb;
  complete_replay jsonb;
  android_first jsonb;
  android_second jsonb;
  task_id uuid;
  occurrence_id uuid;
  device_id uuid;
  device_id_again uuid;
  occurrence_status text;
  affected integer;
  visible_count integer;
  rejected boolean := false;
  token_value text := 'vanta-rollback-acceptance-' || gen_random_uuid()::text;
begin
  if owner_id is null then
    raise exception 'No authenticated test account available';
  end if;

  create_result := public.mcp_create_task(
    create_op,
    'Vanta rollback acceptance ' || create_op::text,
    null,
    now() + interval '1 day',
    false,
    null
  );
  create_replay := public.mcp_create_task(
    create_op,
    'Vanta rollback acceptance ' || create_op::text,
    null,
    now() + interval '1 day',
    false,
    null
  );
  if create_result is distinct from create_replay then
    raise exception 'MCP create replay differed';
  end if;

  task_id := (create_result #>> '{task,id}')::uuid;
  occurrence_id := (create_result #>> '{occurrence,id}')::uuid;
  select count(*) into visible_count from public.tasks where id = task_id;
  if visible_count <> 1 then raise exception 'Created task is not visible exactly once'; end if;
  select count(*) into visible_count from public.task_occurrences where id = occurrence_id;
  if visible_count <> 1 then raise exception 'Created occurrence is not visible exactly once'; end if;

  begin
    perform public.mcp_create_task(
      create_op,
      'Changed payload must reject ' || create_op::text,
      null,
      now() + interval '1 day',
      false,
      null
    );
  exception when sqlstate '22023' then
    rejected := true;
  end;
  if not rejected then raise exception 'Changed MCP create payload was not rejected'; end if;

  complete_result := public.mcp_complete_task(complete_op, occurrence_id);
  complete_replay := public.mcp_complete_task(complete_op, occurrence_id);
  if complete_result is distinct from complete_replay then
    raise exception 'MCP completion replay differed';
  end if;
  select status into strict occurrence_status
  from public.task_occurrences where id = occurrence_id;
  if occurrence_status <> 'completed' then raise exception 'Occurrence did not complete'; end if;

  select to_jsonb(result_row) into android_first
  from public.complete_task_occurrence(occurrence_id, android_key) as result_row;
  select to_jsonb(result_row) into android_second
  from public.complete_task_occurrence(occurrence_id, android_key) as result_row;
  if android_first is distinct from android_second then
    raise exception 'Android completion replay differed';
  end if;

  perform set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000000', true);
  select count(*) into visible_count from public.tasks where id = task_id;
  if visible_count <> 0 then raise exception 'RLS exposed another account task'; end if;
  begin
    update public.tasks set title = 'must not update' where id = task_id;
    get diagnostics affected = row_count;
    if affected <> 0 then raise exception 'RLS allowed cross-account update'; end if;
  exception when insufficient_privilege then
    -- Authenticated callers are not granted direct task-table updates.
    null;
  end;

  perform set_config('request.jwt.claim.sub', owner_id::text, true);
  device_id := public.system_register_device_token(token_value);
  device_id_again := public.system_register_device_token(token_value);
  if device_id is distinct from device_id_again then
    raise exception 'Device token registration was not idempotent';
  end if;

  perform set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000000', true);
  rejected := false;
  begin
    perform public.system_register_device_token(token_value);
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then raise exception 'An active token was reassigned across accounts'; end if;
  perform set_config('request.jwt.claim.sub', owner_id::text, true);

  select count(*) into visible_count
  from public.device_tokens
  where token = token_value and user_id = owner_id and enabled;
  if visible_count <> 1 then raise exception 'Registration did not leave one active token row'; end if;

  if has_function_privilege('anon', 'public.system_register_device_token(text)', 'execute') then
    raise exception 'Anonymous role can execute device registration';
  end if;
  if has_function_privilege(
    'anon',
    'public.mcp_create_task(uuid,text,text,timestamptz,boolean,timestamptz)',
    'execute'
  ) then
    raise exception 'Anonymous role can execute MCP writes';
  end if;
end
$acceptance$;

select 'PASS: create/replay, changed-payload rejection, completion replay, ownership isolation, token registration, and anonymous grants' as acceptance_result;
rollback;
