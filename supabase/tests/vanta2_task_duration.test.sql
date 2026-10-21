begin;
create extension if not exists pgtap with schema extensions;
select plan(16);

insert into auth.users(id, instance_id, aud, role, email, encrypted_password, email_confirmed_at,
                       raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
values ('12000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000',
        'authenticated', 'authenticated', 'vanta-duration@example.invalid', '', now(), '{}', '{}', now(), now());

select has_column('public', 'tasks', 'default_duration_minutes', 'tasks has the nullable default duration column');
select has_column('public', 'task_occurrences', 'duration_minutes', 'occurrences have the nullable duration column');

select set_config('request.jwt.claim.sub', '12000000-0000-4000-8000-000000000001', true);
set local role authenticated;

select lives_ok(
  $$select public.mcp_create_task(
    '72000000-0000-4000-8000-000000000001', 'Legacy client task', null,
    now() + interval '1 hour', false, null
  )$$,
  'existing clients can create a task without a duration'
);
select is(
  (public.mcp_create_task(
    '72000000-0000-4000-8000-000000000001', 'Legacy client task', null,
    now() + interval '1 hour', false, null
  )->'occurrence'->>'duration_minutes'),
  null::text,
  'omitted duration remains null for old MCP clients'
);
select is(
  (select default_duration_minutes from public.tasks where title = 'Legacy client task'),
  null::integer,
  'legacy-created task has no default duration'
);

select is(
  (public.mcp_create_task(
    '72000000-0000-4000-8000-000000000002', 'Timed task', null,
    now() + interval '1 hour', false, null, 90
  )->'occurrence'->>'duration_minutes')::integer,
  90,
  'MCP task creation stores the explicit occurrence duration'
);
select is(
  (select default_duration_minutes from public.tasks where title = 'Timed task'),
  90,
  'one-time task retains its duration as the task default'
);
select is(
  (public.mcp_update_task(
    '73000000-0000-4000-8000-000000000001',
    (select id from public.tasks where title = 'Timed task'),
    null, false, null, false, null, false, false, null, false, false, false, 45, true
  )->'occurrence'->>'duration_minutes')::integer,
  45,
  'MCP task update changes the occurrence duration'
);
select is(
  (select default_duration_minutes from public.tasks where title = 'Timed task'),
  45,
  'MCP task update changes the task default duration'
);
select lives_ok(
  $$select public.mcp_update_task(
    '73000000-0000-4000-8000-000000000002',
    (select id from public.tasks where title = 'Timed task'),
    null, false, null, false, null, false, false, null, false, false, false, null, true
  )$$,
  'MCP update can explicitly clear a duration'
);
select is(
  (select duration_minutes from public.task_occurrences
   where task_id = (select id from public.tasks where title = 'Timed task') and schedule_id is null),
  null::integer,
  'cleared occurrence duration stays null'
);

select throws_ok(
  $$select public.mcp_create_task(
    '72000000-0000-4000-8000-000000000003', 'Invalid duration', null,
    now() + interval '1 hour', false, null, 4
  )$$,
  '22023', null, 'MCP creation rejects duration below five minutes'
);
select is(
  (public.mcp_create_task(
    '72000000-0000-4000-8000-000000000004', 'Minimum duration', null,
    now() + interval '1 hour', false, null, 5
  )->'occurrence'->>'duration_minutes')::integer,
  5,
  'minimum duration is accepted'
);
select is(
  (public.mcp_create_task(
    '72000000-0000-4000-8000-000000000005', 'Maximum duration', null,
    now() + interval '1 hour', false, null, 1440
  )->'occurrence'->>'duration_minutes')::integer,
  1440,
  '24 hour duration is accepted'
);

select lives_ok(
  $$select public.mcp_create_habit(
    '74000000-0000-4000-8000-000000000001', 'Recurring study', null,
    now() + interval '1 day', 'FREQ=DAILY;COUNT=2', '{}'::timestamptz[], '{}'::timestamptz[], null, 60
  )$$,
  'habit creation accepts a default duration for generated occurrences'
);
select is(
  (select default_duration_minutes from public.tasks where title = 'Recurring study'),
  60,
  'habit task default duration is persisted'
);

reset role;
select * from finish();
rollback;
