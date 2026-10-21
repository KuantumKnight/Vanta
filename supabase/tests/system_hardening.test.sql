begin;
create extension if not exists pgtap with schema extensions;
select plan(32);

insert into auth.users(id, instance_id, aud, role, email, encrypted_password, email_confirmed_at,
                       raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
values
  ('10000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'vanta-test-a@example.invalid', '', now(), '{}', '{}', now(), now()),
  ('10000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'vanta-test-b@example.invalid', '', now(), '{}', '{}', now(), now());

select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000001', true);
set local role authenticated;

select ok(
  not has_table_privilege('authenticated', 'public.task_occurrences', 'INSERT'),
  'authenticated role has no direct occurrence INSERT privilege'
);
select throws_ok(
  $$insert into public.task_occurrences(task_id, user_id, scheduled_for, all_day)
    values ('20000000-0000-4000-8000-000000000001', auth.uid(), now(), false)$$,
  '42501', null, 'authenticated client cannot fabricate an occurrence'
);
select lives_ok(
  $$select public.mcp_create_habit(
    '30000000-0000-4000-8000-000000000001', 'hardening habit', null,
    now() + interval '1 day', 'FREQ=DAILY;COUNT=3', '{}'::timestamptz[], '{}'::timestamptz[], null
  )$$,
  'authenticated habit creation remains available through the owned RPC'
);
select is(
  public.mcp_create_habit(
    '30000000-0000-4000-8000-000000000001', 'hardening habit', null,
    now() + interval '1 day', 'FREQ=DAILY;COUNT=3', '{}'::timestamptz[], '{}'::timestamptz[], null
  ),
  public.mcp_create_habit(
    '30000000-0000-4000-8000-000000000001', 'hardening habit', null,
    now() + interval '1 day', 'FREQ=DAILY;COUNT=3', '{}'::timestamptz[], '{}'::timestamptz[], null
  ),
  'habit creation replays the same stored result'
);
select is(
  (select count(*) from public.habit_schedules where task_id = (select id from public.tasks where title = 'hardening habit')),
  1::bigint,
  'habit replay creates one owner schedule'
);

-- Model the trusted Edge Function path: it reads the canonical schedule as
-- service_role, then writes rows carrying that schedule's owner identity.
reset role;
set local role service_role;
insert into public.task_occurrences(task_id, schedule_id, user_id, scheduled_for, all_day)
select hs.task_id, hs.id, hs.user_id, hs.dtstart + make_interval(days => day_offset), false
from public.habit_schedules hs
join public.tasks t on t.id = hs.task_id
cross join generate_series(0, 2) as generated(day_offset)
where t.title = 'hardening habit' and hs.effective_until is null;
reset role;
set local role authenticated;
select is(
  (select count(*) from public.task_occurrences where task_id = (select id from public.tasks where title = 'hardening habit')),
  3::bigint,
  'trusted owner-scoped materialization writes the generated habit occurrences'
);
reset role;
set local role service_role;
select throws_ok(
  $$insert into public.task_occurrences(task_id, schedule_id, user_id, scheduled_for, all_day)
    select hs.task_id, hs.id, '10000000-0000-4000-8000-000000000002'::uuid, hs.dtstart + interval '1 hour', false
    from public.habit_schedules hs join public.tasks t on t.id = hs.task_id
    where t.title = 'hardening habit' and hs.effective_until is null$$,
  '23503', null, 'database ownership constraints reject cross-user materialization'
);
reset role;
set local role authenticated;

select lives_ok(
  $$select public.mcp_create_task(
    '40000000-0000-4000-8000-000000000001', 'hardening one-off', null,
    now() + interval '1 day', false, null
  )$$,
  'one-off task creation is available through the owner RPC'
);
select is(
  public.mcp_create_task('40000000-0000-4000-8000-000000000001', 'hardening one-off', null, now() + interval '1 day', false, null),
  public.mcp_create_task('40000000-0000-4000-8000-000000000001', 'hardening one-off', null, now() + interval '1 day', false, null),
  'task creation replays the same stored result'
);
select lives_ok(
  $$select public.mcp_update_task(
    '41000000-0000-4000-8000-000000000001',
    (select id from public.tasks where title = 'hardening one-off'),
    'hardening one-off renamed', true, null, false, null, false, false, null, false, false, false
  )$$,
  'one-off task update is available through the owner RPC'
);
select is(
  public.mcp_update_task(
    '41000000-0000-4000-8000-000000000001',
    (select id from public.tasks where title = 'hardening one-off renamed'),
    'hardening one-off renamed', true, null, false, null, false, false, null, false, false, false
  ),
  public.mcp_update_task(
    '41000000-0000-4000-8000-000000000001',
    (select id from public.tasks where title = 'hardening one-off renamed'),
    'hardening one-off renamed', true, null, false, null, false, false, null, false, false, false
  ),
  'task update replays the same stored result'
);
select is(
  (select title from public.tasks where title = 'hardening one-off renamed'),
  'hardening one-off renamed',
  'task update changed the canonical task once'
);
select is(
  public.system_complete_task_occurrence(
    (select o.id from public.task_occurrences o join public.tasks t on t.id = o.task_id where t.title = 'hardening one-off renamed'),
    '50000000-0000-4000-8000-000000000001'
  ) ->> 'outcome',
  'success',
  'first Android completion is applied'
);
select is(
  public.system_complete_task_occurrence(
    (select o.id from public.task_occurrences o join public.tasks t on t.id = o.task_id where t.title = 'hardening one-off renamed'),
    '50000000-0000-4000-8000-000000000001'
  ) ->> 'outcome',
  'already_applied',
  'same Android idempotency key safely replays'
);
select is(
  public.system_complete_task_occurrence(
    (select o.id from public.task_occurrences o join public.tasks t on t.id = o.task_id where t.title = 'hardening one-off renamed'),
    '50000000-0000-4000-8000-000000000002'
  ) ->> 'outcome',
  'already_applied',
  'completion already made by another client clears safely'
);
select throws_ok(
  $$select public.system_complete_task_occurrence(
    (select id from public.task_occurrences
     where task_id = (select id from public.tasks where title = 'hardening habit')
       and status = 'pending' order by scheduled_for limit 1),
    '50000000-0000-4000-8000-000000000001'
  )$$,
  '22023', 'Idempotency key was already used',
  'reusing a completion key for another occurrence is a stable conflict'
);

select lives_ok(
  $$select public.mcp_update_habit_schedule(
    '30000000-0000-4000-8000-000000000002',
    (select id from public.tasks where title = 'hardening habit'),
    now() + interval '2 days', now() + interval '2 days', 'FREQ=DAILY;COUNT=3',
    '{}'::timestamptz[], '{}'::timestamptz[], null
  )$$,
  'future habit schedule edit replaces future pending work'
);
select is(
  (select count(*) from public.task_occurrences where task_id = (select id from public.tasks where title = 'hardening habit') and status = 'skipped'),
  2::bigint,
  'schedule edit preserves removed future occurrences as skipped history'
);
reset role;
set local role service_role;
insert into public.task_occurrences(task_id, schedule_id, user_id, scheduled_for, all_day)
select hs.task_id, hs.id, hs.user_id, hs.dtstart + make_interval(days => day_offset), false
from public.habit_schedules hs
join public.tasks t on t.id = hs.task_id
cross join generate_series(0, 2) as generated(day_offset)
where t.title = 'hardening habit' and hs.effective_until is null;
reset role;
set local role authenticated;
select is(
  (select count(*) from public.task_occurrences o
   join public.habit_schedules hs on hs.id = o.schedule_id
   where hs.task_id = (select id from public.tasks where title = 'hardening habit')
     and hs.effective_until is null and o.status = 'pending'),
  3::bigint,
  'trusted materialization writes future rows for the replacement schedule'
);
select throws_ok(
  $$select public.system_complete_task_occurrence(
    (select id from public.task_occurrences where task_id = (select id from public.tasks where title = 'hardening habit') and status = 'skipped' order by scheduled_for limit 1),
    '50000000-0000-4000-8000-000000000003'
  )$$,
  'P0002', null, 'offline completion of an occurrence skipped by schedule edit becomes a permanent conflict'
);

select is(
  public.system_register_device_token('vanta-same-owner-token-000000000001'),
  public.system_register_device_token('vanta-same-owner-token-000000000001'),
  'same-owner token registration is idempotent'
);
select lives_ok(
  $$select public.system_register_device_token('vanta-new-device-token-000000000001')$$,
  'new device token registers'
);
select lives_ok(
  $$select public.system_register_device_token('vanta-active-token-owned-by-a-0000000001')$$,
  'active token initially belongs to account A'
);

select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000002', true);
select is(
  (select count(*) from public.tasks where title = 'hardening habit'),
  0::bigint,
  'RLS does not expose another user task'
);
select throws_ok(
  $$select public.mcp_update_habit_schedule(
    '30000000-0000-4000-8000-000000000002',
    (select id from public.tasks where title = 'hardening habit'),
    now() + interval '2 days', now() + interval '2 days', 'FREQ=DAILY;COUNT=3',
    '{}'::timestamptz[], '{}'::timestamptz[], null
  )$$,
  'P0002', null, 'another owner cannot materialize or replace this schedule'
);
select throws_ok(
  $$select public.system_register_device_token('vanta-active-token-owned-by-a-0000000001')$$,
  '42501', null, 'active device token cannot be hijacked by another user'
);

select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000001', true);
select lives_ok(
  $$update public.device_tokens set enabled = false where token = 'vanta-active-token-owned-by-a-0000000001'$$,
  'owner can disable an old token before transfer'
);
select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000002', true);
select lives_ok(
  $$select public.system_register_device_token('vanta-active-token-owned-by-a-0000000001')$$,
  'a disabled token can transfer to a new account'
);
select is(
  (select user_id from public.device_tokens where token = 'vanta-active-token-owned-by-a-0000000001'),
  '10000000-0000-4000-8000-000000000002'::uuid,
  'disabled token ownership transfers to account B'
);
select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000001', true);
with changed as (
  update public.device_tokens set enabled = false where token = 'vanta-active-token-owned-by-a-0000000001' returning 1
)
select is(
  (select count(*) from changed),
  0::bigint,
  'account A sign-out cannot disable account B token'
);
select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000002', true);
select ok(
  (select enabled from public.device_tokens where token = 'vanta-active-token-owned-by-a-0000000001'),
  'account B token remains enabled after account A signs out'
);
select ok(
  not has_function_privilege('anon', 'public.system_complete_task_occurrence(uuid,uuid)', 'execute'),
  'anonymous users cannot call the privileged completion RPC'
);

select * from finish();
rollback;
