begin;
create extension if not exists pgtap with schema extensions;
select plan(24);

insert into auth.users(id, instance_id, aud, role, email, encrypted_password, email_confirmed_at,
                       raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
values
  ('18000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'vanta-scheduling-a@example.invalid', '', now(), '{}', '{}', now(), now()),
  ('18000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'vanta-scheduling-b@example.invalid', '', now(), '{}', '{}', now(), now());

select ok(has_column('public', 'tasks', 'default_schedule_type'), 'tasks persist their default schedule type');
select ok(has_column('public', 'tasks', 'default_priority'), 'tasks persist their default priority');
select ok(has_column('public', 'task_occurrences', 'schedule_type'), 'occurrences persist schedule type');
select ok(has_column('public', 'task_occurrences', 'priority'), 'occurrences persist priority');
select ok(has_column('public', 'task_occurrences', 'deadline_at'), 'occurrences persist a separate deadline');
select ok(has_column('public', 'task_occurrences', 'earliest_start'), 'occurrences persist earliest start');
select ok(has_column('public', 'task_occurrences', 'latest_end'), 'occurrences persist latest end');
select ok(has_column('public', 'task_occurrences', 'preferred_start'), 'occurrences persist preferred start');

select set_config('request.jwt.claim.sub', '18000000-0000-4000-8000-000000000001', true);
set local role authenticated;

select is(
  (public.mcp_create_task('81000000-0000-4000-8000-000000000001', 'Scheduled legacy', null,
    now() + interval '2 hours', false, null)->'occurrence'->>'schedule_type'),
  'SOFT',
  'legacy scheduled tasks default to SOFT'
);
select is(
  (select default_schedule_type from public.tasks where title = 'Scheduled legacy'),
  'SOFT',
  'legacy scheduled task defaults stay aligned'
);
select is(
  (public.mcp_create_task('81000000-0000-4000-8000-000000000002', 'Flexible legacy', null,
    null, false, null)->'occurrence'->>'schedule_type'),
  'FLEXIBLE',
  'legacy unscheduled tasks default to FLEXIBLE'
);

select lives_ok(
  $$select public.mcp_create_task_with_scheduling(
    '81000000-0000-4000-8000-000000000003', 'Flexible report', null,
    null, false, null, 45, 'FLEXIBLE', 'HIGH',
    '2026-10-23T18:00:00Z', '2026-10-22T09:00:00Z', '2026-10-23T17:00:00Z', '2026-10-22T14:00:00Z'
  )$$,
  'typed scheduling RPC creates a bounded flexible task'
);
select is(
  (select scheduled_for from public.task_occurrences o join public.tasks t on t.id = o.task_id where t.title = 'Flexible report'),
  null::timestamptz,
  'deadline does not become a scheduled start'
);
select is(
  (select jsonb_build_array(o.schedule_type, o.priority, o.deadline_at, o.earliest_start, o.latest_end, o.preferred_start)
   from public.task_occurrences o join public.tasks t on t.id = o.task_id where t.title = 'Flexible report'),
  jsonb_build_array('FLEXIBLE', 'HIGH', '2026-10-23T18:00:00Z'::timestamptz,
    '2026-10-22T09:00:00Z'::timestamptz, '2026-10-23T17:00:00Z'::timestamptz,
    '2026-10-22T14:00:00Z'::timestamptz),
  'all scheduling constraints persist independently'
);
select is(
  (select jsonb_build_object(
      'schedule_type', value->'occurrence'->>'schedule_type',
      'priority', value->'occurrence'->>'priority',
      'deadline_at', (value->'occurrence'->>'deadline_at')::timestamptz,
      'earliest_start', (value->'occurrence'->>'earliest_start')::timestamptz,
      'latest_end', (value->'occurrence'->>'latest_end')::timestamptz,
      'preferred_start', (value->'occurrence'->>'preferred_start')::timestamptz)
   from jsonb_array_elements(public.system_get_occurrence_changes(0, 100)->'changes') as changes(value)
   where value->'occurrence'->>'id' = (select o.id::text from public.task_occurrences o join public.tasks t on t.id = o.task_id where t.title = 'Flexible report')
   order by (value->>'revision')::bigint desc limit 1),
  jsonb_build_object('schedule_type', 'FLEXIBLE', 'priority', 'HIGH',
    'deadline_at', '2026-10-23T18:00:00Z'::timestamptz,
    'earliest_start', '2026-10-22T09:00:00Z'::timestamptz,
    'latest_end', '2026-10-23T17:00:00Z'::timestamptz,
    'preferred_start', '2026-10-22T14:00:00Z'::timestamptz),
  'delta projection carries all six scheduling fields'
);
select is(
  (public.mcp_create_task_with_scheduling(
    '81000000-0000-4000-8000-000000000005', 'Hard appointment', null,
    now() + interval '4 hours', false, null, 30, 'HARD', 'NORMAL', null, null, null, null
  )->'occurrence'->>'schedule_type'),
  'HARD',
  'an explicit HARD commitment is persisted'
);
select is(
  (public.mcp_create_task_with_scheduling(
    '81000000-0000-4000-8000-000000000003', 'Flexible report', null,
    null, false, null, 45, 'FLEXIBLE', 'HIGH',
    '2026-10-23T18:00:00Z', '2026-10-22T09:00:00Z', '2026-10-23T17:00:00Z', '2026-10-22T14:00:00Z'
  )->'occurrence'->>'id'),
  (select o.id::text from public.task_occurrences o join public.tasks t on t.id = o.task_id where t.title = 'Flexible report'),
  'extended create replays the original occurrence idempotently'
);
select is(
  (public.mcp_update_task_with_scheduling(
    '82000000-0000-4000-8000-000000000001',
    (select id from public.tasks where title = 'Flexible report'),
    null, false, null, false, null, false, false, null, false, false, false, null, false,
    '{"priority":"LOW","deadline_at":null}'::jsonb
  )->'occurrence'->>'priority'),
  'LOW',
  'idempotent scheduling update changes selected fields only'
);
select is(
  (public.mcp_update_task_with_scheduling(
    '82000000-0000-4000-8000-000000000001',
    (select id from public.tasks where title = 'Flexible report'),
    null, false, null, false, null, false, false, null, false, false, false, null, false,
    '{"priority":"LOW","deadline_at":null}'::jsonb
  )->'occurrence'->>'priority'),
  'LOW',
  'extended update replays the same scheduling result idempotently'
);
select is(
  (select deadline_at from public.task_occurrences o join public.tasks t on t.id = o.task_id where t.title = 'Flexible report'),
  null::timestamptz,
  'scheduling update can clear a deadline without changing other bounds'
);
select throws_ok(
  $$select public.mcp_create_task_with_scheduling(
    '81000000-0000-4000-8000-000000000004', 'Bad bounds', null,
    null, false, null, 30, 'SOFT', 'NORMAL', null,
    '2026-10-24T09:00:00Z', '2026-10-23T09:00:00Z', null
  )$$,
  '22023', null, 'creation rejects an inverted planning window'
);
select throws_ok(
  $$select public.mcp_create_task_with_scheduling(
    '81000000-0000-4000-8000-000000000006', 'Scheduled flexible', null,
    now() + interval '2 hours', false, null, 30, 'FLEXIBLE', 'NORMAL', null, null, null, null
  )$$,
  '22023', null, 'creation rejects FLEXIBLE tasks with an exact scheduled start'
);
select throws_ok(
  $$select public.mcp_create_task_with_scheduling(
    '81000000-0000-4000-8000-000000000007', 'Unscheduled hard', null,
    null, false, null, 30, 'HARD', 'NORMAL', null, null, null, null
  )$$,
  '22023', null, 'creation rejects HARD tasks without a scheduled start'
);
select throws_ok(
  $$select public.mcp_update_task_with_scheduling(
    '82000000-0000-4000-8000-000000000002',
    '18000000-0000-4000-8000-000000000002',
    null, false, null, false, null, false, false, null, false, false, false, null, false,
    '{"priority":"HIGH"}'::jsonb
  )$$,
  'P0002', null, 'the scheduling update is owner scoped'
);

reset role;
select * from finish();
rollback;
