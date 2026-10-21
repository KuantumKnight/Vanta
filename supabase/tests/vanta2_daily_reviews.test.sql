begin;
create extension if not exists pgtap with schema extensions;
select plan(13);

insert into auth.users(id, instance_id, aud, role, email, encrypted_password, email_confirmed_at,
                       raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
values
  ('17000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'vanta-review-a@example.invalid', '', now(), '{}', '{}', now(), now()),
  ('17000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'vanta-review-b@example.invalid', '', now(), '{}', '{}', now(), now());

insert into public.tasks(id, user_id, title, kind)
values
  ('27000000-0000-4000-8000-000000000001', '17000000-0000-4000-8000-000000000001', 'Owner task', 'task'),
  ('27000000-0000-4000-8000-000000000002', '17000000-0000-4000-8000-000000000001', 'Owner habit', 'habit'),
  ('27000000-0000-4000-8000-000000000003', '17000000-0000-4000-8000-000000000002', 'Other owner task', 'task'),
  ('27000000-0000-4000-8000-000000000004', '17000000-0000-4000-8000-000000000001', 'Next-day task', 'task');

insert into public.task_occurrences(id, task_id, user_id, scheduled_for, status)
values
  ('47000000-0000-4000-8000-000000000001', '27000000-0000-4000-8000-000000000001', '17000000-0000-4000-8000-000000000001', '2026-10-21 09:00:00+05:30', 'pending'),
  ('47000000-0000-4000-8000-000000000002', '27000000-0000-4000-8000-000000000002', '17000000-0000-4000-8000-000000000001', '2026-10-21 10:00:00+05:30', 'pending'),
  ('47000000-0000-4000-8000-000000000003', '27000000-0000-4000-8000-000000000004', '17000000-0000-4000-8000-000000000001', '2026-10-22 09:00:00+05:30', 'pending'),
  ('47000000-0000-4000-8000-000000000004', '27000000-0000-4000-8000-000000000003', '17000000-0000-4000-8000-000000000002', '2026-10-21 11:00:00+05:30', 'pending');

select ok(
  has_function_privilege('authenticated', 'public.system_save_daily_review(date,text)', 'EXECUTE'),
  'signed-in users can save their daily review'
);
select ok(
  not has_function_privilege('anon', 'public.system_save_daily_review(date,text)', 'EXECUTE'),
  'anonymous users cannot save a daily review'
);
select ok(
  not has_table_privilege('authenticated', 'public.daily_reviews', 'INSERT'),
  'clients cannot bypass the owner-scoped review RPC'
);

select set_config('request.jwt.claim.sub', '', true);
set local role authenticated;
select throws_ok(
  $$select public.system_save_daily_review('2026-10-21', 'A note')$$,
  '28000', null, 'unauthenticated review writes are rejected'
);

select set_config('request.jwt.claim.sub', '17000000-0000-4000-8000-000000000001', true);
select public.system_save_daily_review('2026-10-21', '  A short reflection  ');
select is(
  (select note from public.daily_reviews where review_date = '2026-10-21'),
  'A short reflection',
  'review notes are trimmed before they are stored'
);
select public.system_save_daily_review('2026-10-21', 'Updated reflection');
select is(
  (select count(*)::integer from public.daily_reviews where review_date = '2026-10-21'),
  1,
  'saving the same date updates one owner-scoped row'
);
select is(
  (select note from public.daily_reviews where review_date = '2026-10-21'),
  'Updated reflection',
  'the latest review note is returned'
);
select is(
  (select count(*)::integer from public.daily_reviews),
  1,
  'an owner can read their review through row-level security'
);

select is(
  (select count(*)::integer from public.system_list_occurrence_history(
    '2026-10-21 00:00:00+05:30', '2026-10-22 00:00:00+05:30', null, 500, 0
  )),
  2,
  'history is restricted to the owner and the requested local-date range'
);
select is(
  (select count(*)::integer from public.system_list_occurrence_history(
    '2026-10-21 00:00:00+05:30', '2026-10-22 00:00:00+05:30', 'habit', 500, 0
  )),
  1,
  'history supports a habit-only filter'
);
select is(
  (select count(*)::integer from public.system_list_occurrence_history(
    '2026-10-21 00:00:00+05:30', '2026-10-22 00:00:00+05:30', 'task', 500, 0
  )),
  1,
  'history supports a task-only filter'
);

reset role;
set local role authenticated;
select set_config('request.jwt.claim.sub', '17000000-0000-4000-8000-000000000002', true);
select is(
  (select count(*)::integer from public.daily_reviews),
  0,
  'another user cannot read the first owner''s reflection'
);

select throws_ok(
  $$select public.system_save_daily_review('2026-10-21', repeat('x', 4001))$$,
  '22023', null, 'oversized review notes are rejected'
);

select * from finish();
rollback;
