begin;
create extension if not exists pgtap with schema extensions;
select plan(18);

insert into auth.users(id, instance_id, aud, role, email, encrypted_password, email_confirmed_at,
                       raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
values
  ('15000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'vanta-delta-a@example.invalid', '', now(), '{}', '{}', now(), now()),
  ('15000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'vanta-delta-b@example.invalid', '', now(), '{}', '{}', now(), now());

select set_config('request.jwt.claim.sub', '15000000-0000-4000-8000-000000000001', true);
set local role authenticated;

select ok(
  not has_table_privilege('authenticated', 'private.system_occurrence_changes', 'SELECT'),
  'authenticated clients cannot read the private delta log directly'
);
select throws_ok(
  $$select * from private.system_occurrence_changes$$,
  '42501', null, 'direct private log reads are denied'
);
select is(
  (public.system_current_sync_revision()->>'revision')::bigint,
  0::bigint,
  'new owner starts at revision zero'
);
select is(
  jsonb_array_length(public.system_get_occurrence_changes(0, 10)->'changes'),
  0,
  'new owner starts with an empty delta page'
);

select lives_ok(
  $$select public.mcp_create_task(
    '75000000-0000-4000-8000-000000000001', 'Delta task', 'Initial notes',
    now() + interval '2 hours', false, null, 45
  )$$,
  'authenticated owner creates a task through the idempotent RPC'
);
select is(
  (public.system_get_occurrence_changes(0, 10)->'changes'->0->>'revision')::bigint,
  1::bigint,
  'task creation writes the first ordered change row'
);
select is(
  public.system_get_occurrence_changes(0, 10)->'changes'->0->'occurrence'->>'title',
  'Delta task',
  'creation delta contains the full cached occurrence projection'
);
select is(
  (public.mcp_create_task(
    '75000000-0000-4000-8000-000000000001', 'Delta task', 'Initial notes',
    now() + interval '2 hours', false, null, 45
  )->'occurrence'->>'id'),
  (select occurrence.id::text
   from public.task_occurrences occurrence
   join public.tasks task on task.id = occurrence.task_id
   where task.user_id = auth.uid() and task.title = 'Delta task'
   limit 1),
  'idempotent create replay returns the original occurrence'
);
select is(
  (public.system_current_sync_revision()->>'revision')::bigint,
  1::bigint,
  'idempotent create replay does not allocate a second revision'
);

reset role;
update public.tasks set title = 'Renamed delta task'
where user_id = '15000000-0000-4000-8000-000000000001' and title = 'Delta task';
set local role authenticated;

select is(
  (public.system_get_occurrence_changes(1, 10)->'changes'->0->>'revision')::bigint,
  2::bigint,
  'task title edits emit the next revision for each affected occurrence'
);
select is(
  public.system_get_occurrence_changes(1, 10)->'changes'->0->'occurrence'->>'title',
  'Renamed delta task',
  'task edits include updated joined task fields in the delta projection'
);
select lives_ok(
  $$select public.system_complete_task_occurrence(
    (select occurrence.id
     from public.task_occurrences occurrence
     join public.tasks task on task.id = occurrence.task_id
     where task.user_id = auth.uid() and task.title = 'Renamed delta task'
     limit 1),
    '76000000-0000-4000-8000-000000000001'
  )$$,
  'completion remains available through the owner-scoped completion RPC'
);
select is(
  public.system_get_occurrence_changes(2, 10)->'changes'->0->'occurrence'->>'status',
  'completed',
  'same-day completion remains an upsert for the widget cache'
);

reset role;
update public.tasks set archived_at = now()
where user_id = '15000000-0000-4000-8000-000000000001' and title = 'Renamed delta task';
set local role authenticated;

select is(
  public.system_get_occurrence_changes(3, 10)->'changes'->0->>'operation',
  'delete',
  'archiving emits a tombstone for the cached occurrence'
);
select is(
  (public.system_current_sync_revision()->>'revision')::bigint,
  4::bigint,
  'each owner-visible transition advances the per-user revision once'
);

select set_config('request.jwt.claim.sub', '15000000-0000-4000-8000-000000000002', true);
select lives_ok(
  $$select public.mcp_create_task(
    '75000000-0000-4000-8000-000000000002', 'Other owner task', null,
    now() + interval '1 day', false, null, null
  )$$,
  'a second account can create its own task'
);
select is(
  public.system_get_occurrence_changes(0, 10)->'changes'->0->'occurrence'->>'title',
  'Other owner task',
  'delta RPC returns only the authenticated owner projection'
);
select is(
  jsonb_array_length(public.system_get_occurrence_changes(4, 10)->'changes'),
  0,
  'owner A changes are not visible through owner B cursor'
);

reset role;
select * from finish();
rollback;
