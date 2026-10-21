begin;
create extension if not exists pgtap with schema extensions;
select plan(20);

insert into auth.users(id, instance_id, aud, role, email, encrypted_password, email_confirmed_at,
                       raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
values
  ('16000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'vanta-reschedule-a@example.invalid', '', now(), '{}', '{}', now(), now()),
  ('16000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'vanta-reschedule-b@example.invalid', '', now(), '{}', '{}', now(), now());

insert into public.tasks(id, user_id, title, notes, kind, default_duration_minutes)
values ('26000000-0000-4000-8000-000000000001', '16000000-0000-4000-8000-000000000001', 'Daily study', 'Keep the schedule', 'habit', 60);
insert into public.habit_schedules(id, task_id, user_id, dtstart, timezone, rrule, effective_from)
values ('36000000-0000-4000-8000-000000000001', '26000000-0000-4000-8000-000000000001', '16000000-0000-4000-8000-000000000001',
        '2026-10-21 09:00:00+05:30', 'Asia/Kolkata', 'FREQ=DAILY;COUNT=2', '2026-10-21 09:00:00+05:30');
insert into public.task_occurrences(id, task_id, schedule_id, user_id, scheduled_for, duration_minutes, status)
values
  ('46000000-0000-4000-8000-000000000001', '26000000-0000-4000-8000-000000000001', '36000000-0000-4000-8000-000000000001', '16000000-0000-4000-8000-000000000001', '2026-10-21 09:00:00+05:30', 60, 'pending'),
  ('46000000-0000-4000-8000-000000000002', '26000000-0000-4000-8000-000000000001', '36000000-0000-4000-8000-000000000001', '16000000-0000-4000-8000-000000000001', '2026-10-22 09:00:00+05:30', 60, 'pending');

create temporary table first_reschedule_response(result jsonb);
grant select, insert on first_reschedule_response to authenticated;

select ok(
  has_function_privilege('authenticated', 'public.system_reschedule_occurrence(uuid,timestamptz,uuid)', 'EXECUTE'),
  'authenticated users can call the owner-checked reschedule RPC'
);
select ok(
  not has_function_privilege('anon', 'public.system_reschedule_occurrence(uuid,timestamptz,uuid)', 'EXECUTE'),
  'anonymous users cannot call the reschedule RPC'
);

select set_config('request.jwt.claim.sub', '', true);
set local role authenticated;
select throws_ok(
  $$select public.system_reschedule_occurrence(
    '46000000-0000-4000-8000-000000000001', '2026-10-21 15:00:00+05:30', '56000000-0000-4000-8000-000000000001'
  )$$,
  '28000', null, 'unauthenticated callers are rejected'
);

select set_config('request.jwt.claim.sub', '16000000-0000-4000-8000-000000000001', true);
select lives_ok(
  $$insert into first_reschedule_response(result)
    values (public.system_reschedule_occurrence(
      '46000000-0000-4000-8000-000000000001', '2026-10-21 15:00:00+05:30', '56000000-0000-4000-8000-000000000001'
    ))$$,
  'the owner can reschedule one pending recurrence occurrence'
);
select is(
  ((select result from first_reschedule_response)->'occurrence'->>'scheduled_for')::timestamptz,
  '2026-10-21 15:00:00+05:30'::timestamptz,
  'the response contains the canonical new time'
);
select is(
  ((select result from first_reschedule_response)->'occurrence'->>'duration_minutes')::integer,
  60,
  'rescheduling preserves the occurrence duration'
);
select is(
  (select result->'occurrence'->>'title' from first_reschedule_response),
  'Daily study',
  'the response includes the canonical task projection'
);
select ok(
  (select (result->>'revision')::bigint > 0 from first_reschedule_response),
  'the response includes its synchronization revision'
);
select is(
  (select scheduled_for from public.task_occurrences where id = '46000000-0000-4000-8000-000000000001'),
  '2026-10-21 15:00:00+05:30'::timestamptz,
  'the selected occurrence is updated on the server'
);
select is(
  (select scheduled_for from public.task_occurrences where id = '46000000-0000-4000-8000-000000000002'),
  '2026-10-22 09:00:00+05:30'::timestamptz,
  'a sibling recurrence occurrence is unchanged'
);
select is(
  (select rrule from public.habit_schedules where id = '36000000-0000-4000-8000-000000000001'),
  'FREQ=DAILY;COUNT=2',
  'rescheduling does not mutate the recurring schedule'
);
reset role;
select is(
  (select revision from private.system_sync_state where user_id = '16000000-0000-4000-8000-000000000001'),
  ((select result->>'revision' from first_reschedule_response)::bigint),
  'the response revision matches the per-user checkpoint'
);
select is(
  (select count(*)::integer from private.system_occurrence_changes
   where user_id = '16000000-0000-4000-8000-000000000001'
     and occurrence_id = '46000000-0000-4000-8000-000000000001'
     and operation = 'upsert'
     and scheduled_for = '2026-10-21 15:00:00+05:30'::timestamptz
     and duration_minutes = 60),
  1,
  'the row trigger writes one full-projection delta event'
);
set local role authenticated;
select is(
  public.system_reschedule_occurrence(
    '46000000-0000-4000-8000-000000000001', '2026-10-21 15:00:00+05:30', '56000000-0000-4000-8000-000000000001'
  ),
  (select result from first_reschedule_response),
  'retrying the same operation returns the original canonical response'
);
select throws_ok(
  $$select public.system_reschedule_occurrence(
    '46000000-0000-4000-8000-000000000001', '2026-10-21 16:00:00+05:30', '56000000-0000-4000-8000-000000000001'
  )$$,
  '22023', null, 'an idempotency key cannot be reused with a different time'
);
select throws_ok(
  $$select public.system_reschedule_occurrence(
    '46000000-0000-4000-8000-000000000002', '2026-10-22 09:00:00+05:30', '56000000-0000-4000-8000-000000000001'
  )$$,
  '22023', null, 'an idempotency key cannot be reused for another occurrence'
);

select set_config('request.jwt.claim.sub', '16000000-0000-4000-8000-000000000002', true);
select throws_ok(
  $$select public.system_reschedule_occurrence(
    '46000000-0000-4000-8000-000000000001', '2026-10-21 16:00:00+05:30', '56000000-0000-4000-8000-000000000002'
  )$$,
  'P0002', null, 'a different owner cannot reschedule this occurrence'
);
reset role;
select is(
  (select scheduled_for from public.task_occurrences where id = '46000000-0000-4000-8000-000000000001'),
  '2026-10-21 15:00:00+05:30'::timestamptz,
  'a rejected cross-owner mutation leaves the schedule unchanged'
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '16000000-0000-4000-8000-000000000001', true);
select public.system_complete_task_occurrence(
  '46000000-0000-4000-8000-000000000001', '66000000-0000-4000-8000-000000000001'
);
select is(
  public.system_reschedule_occurrence(
    '46000000-0000-4000-8000-000000000001', '2026-10-21 15:00:00+05:30', '56000000-0000-4000-8000-000000000001'
  ),
  (select result from first_reschedule_response),
  'an already-applied operation replays after later completion'
);
select throws_ok(
  $$select public.system_reschedule_occurrence(
    '46000000-0000-4000-8000-000000000001', '2026-10-21 17:00:00+05:30', '56000000-0000-4000-8000-000000000003'
  )$$,
  'P0002', null, 'a new operation cannot reschedule a completed occurrence'
);

reset role;
select * from finish();
rollback;
