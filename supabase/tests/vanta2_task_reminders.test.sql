begin;
create extension if not exists pgtap with schema extensions;
select plan(11);

insert into auth.users(id, instance_id, aud, role, email, encrypted_password, email_confirmed_at,
                       raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
values
  ('18000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'vanta-reminder-a@example.invalid', '', now(), '{}', '{}', now(), now()),
  ('18000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'vanta-reminder-b@example.invalid', '', now(), '{}', '{}', now(), now());

insert into public.tasks(id, user_id, title, kind)
values
  ('28000000-0000-4000-8000-000000000001', '18000000-0000-4000-8000-000000000001', 'Reminder A', 'task'),
  ('28000000-0000-4000-8000-000000000002', '18000000-0000-4000-8000-000000000002', 'Reminder B', 'task');

insert into public.task_occurrences(id, task_id, user_id, scheduled_for, reminder_at, status)
values
  ('48000000-0000-4000-8000-000000000001', '28000000-0000-4000-8000-000000000001', '18000000-0000-4000-8000-000000000001', now() - interval '1 hour', now() - interval '5 minutes', 'pending'),
  ('48000000-0000-4000-8000-000000000002', '28000000-0000-4000-8000-000000000002', '18000000-0000-4000-8000-000000000002', now() - interval '1 hour', now() - interval '5 minutes', 'pending');

insert into public.device_tokens(id, user_id, token, platform, enabled)
values
  ('58000000-0000-4000-8000-000000000001', '18000000-0000-4000-8000-000000000001', 'reminder-token-a-0000000000000001', 'android', true),
  ('58000000-0000-4000-8000-000000000002', '18000000-0000-4000-8000-000000000002', 'reminder-token-b-0000000000000002', 'android', true);

create temporary table claimed_reminders (user_id uuid, occurrence_id uuid);
grant select, insert, delete on claimed_reminders to service_role;

select ok(
  has_function_privilege('authenticated', 'public.system_set_task_reminders_enabled(text,boolean)', 'EXECUTE'),
  'signed-in users can update their task reminder preference'
);
select ok(
  not has_function_privilege('anon', 'public.system_set_task_reminders_enabled(text,boolean)', 'EXECUTE'),
  'anonymous users cannot update task reminder preferences'
);
select ok(
  has_column('public', 'device_tokens', 'task_reminders_enabled'),
  'device tokens store a per-device task reminder preference'
);

select set_config('request.jwt.claim.sub', '', true);
set local role authenticated;
select throws_ok(
  $$select public.system_set_task_reminders_enabled('reminder-token-a-0000000000000001', false)$$,
  '28000', null, 'unauthenticated callers are rejected'
);

select set_config('request.jwt.claim.sub', '18000000-0000-4000-8000-000000000001', true);
select public.system_set_task_reminders_enabled('reminder-token-a-0000000000000001', false);
select public.system_set_task_reminders_enabled('reminder-token-b-0000000000000002', false);
reset role;
select is(
  (select task_reminders_enabled from public.device_tokens where id = '58000000-0000-4000-8000-000000000001'),
  false,
  'the owner can disable reminders on their device'
);
select is(
  (select task_reminders_enabled from public.device_tokens where id = '58000000-0000-4000-8000-000000000002'),
  true,
  'changing one owner preference leaves another device unchanged'
);

set local role service_role;
insert into claimed_reminders
select user_id, occurrence_id from public.system_claim_due_reminders();
reset role;
select is(
  (select count(*)::integer from claimed_reminders),
  1,
  'the reminder worker claims only devices with reminders enabled'
);
select is(
  (select user_id from claimed_reminders limit 1),
  '18000000-0000-4000-8000-000000000002'::uuid,
  'the enabled owner receives the due reminder'
);
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '18000000-0000-4000-8000-000000000002', true);
select public.system_set_task_reminders_enabled('reminder-token-b-0000000000000002', false);
reset role;
set local role service_role;
delete from claimed_reminders;
insert into claimed_reminders
select user_id, occurrence_id from public.system_claim_due_reminders();
reset role;
select is(
  (select count(*)::integer from claimed_reminders),
  0,
  'the worker claims no reminders when all owners have disabled them'
);
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '18000000-0000-4000-8000-000000000001', true);
select public.system_set_task_reminders_enabled('reminder-token-a-0000000000000001', true);
reset role;
set local role service_role;
delete from claimed_reminders;
insert into claimed_reminders
select user_id, occurrence_id from public.system_claim_due_reminders();
reset role;
select is(
  (select count(*)::integer from claimed_reminders),
  1,
  're-enabling reminders makes unclaimed due reminders available again'
);
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '18000000-0000-4000-8000-000000000001', true);
select throws_ok(
  $$select public.system_set_task_reminders_enabled('reminder-token-a-0000000000000001', null)$$,
  '22023', null, 'a null preference is rejected'
);

reset role;
select * from finish();
rollback;
