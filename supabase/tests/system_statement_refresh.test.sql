begin;
create extension if not exists pgtap with schema extensions;
select plan(12);
insert into auth.users(id, instance_id, aud, role, email, encrypted_password, email_confirmed_at,
                       raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
values
  ('11000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'vanta-refresh-a@example.invalid', '', now(), '{}', '{}', now(), now()),
  ('11000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'vanta-refresh-b@example.invalid', '', now(), '{}', '{}', now(), now());

delete from vault.secrets where name in ('system_project_url', 'system_internal_secret');
select lives_ok(
  $$insert into public.tasks(user_id, title, kind)
    values ('11000000-0000-4000-8000-000000000001', 'missing vault secret task', 'task')$$,
  'missing Vault values do not roll back the authoritative task mutation'
);
select is(
  (select count(*) from public.tasks where title = 'missing vault secret task'),
  1::bigint,
  'task remains committed in the test transaction after best-effort push failure'
);
set local role service_role;
select lives_ok(
  $$insert into public.task_occurrences(task_id, user_id, scheduled_for, all_day)
    values ('21000000-0000-4000-8000-000000000001', '11000000-0000-4000-8000-000000000001', now(), false)$$,
  'missing Vault values do not roll back the statement-level occurrence trigger write'
);
select is(
  (select count(*) from public.task_occurrences where task_id = '21000000-0000-4000-8000-000000000001'),
  1::bigint,
  'the occurrence remains stored when refresh credentials are missing'
);
delete from public.task_occurrences where task_id = '21000000-0000-4000-8000-000000000001';
reset role;

create temporary table refresh_signal_counter(
  owner_id uuid not null,
  table_name text not null,
  operation text not null
);
create or replace function private.system_invoke_edge_function(p_function text, p_body jsonb default '{}'::jsonb)
returns bigint
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
begin
  insert into refresh_signal_counter(owner_id, table_name, operation)
  values (
    (p_body #>> '{record,user_id}')::uuid,
    p_body ->> 'table',
    p_body ->> 'operation'
  );
  return 1;
end;
$$;

insert into public.tasks(id, user_id, title, kind) values
  ('21000000-0000-4000-8000-000000000001', '11000000-0000-4000-8000-000000000001', 'owner a one', 'task'),
  ('21000000-0000-4000-8000-000000000002', '11000000-0000-4000-8000-000000000001', 'owner a two', 'task'),
  ('21000000-0000-4000-8000-000000000003', '11000000-0000-4000-8000-000000000001', 'owner a three', 'task'),
  ('22000000-0000-4000-8000-000000000001', '11000000-0000-4000-8000-000000000002', 'owner b one', 'task'),
  ('22000000-0000-4000-8000-000000000002', '11000000-0000-4000-8000-000000000002', 'owner b two', 'task');
delete from refresh_signal_counter;

insert into public.task_occurrences(task_id, user_id, scheduled_for, all_day) values
  ('21000000-0000-4000-8000-000000000001', '11000000-0000-4000-8000-000000000001', now(), false),
  ('21000000-0000-4000-8000-000000000002', '11000000-0000-4000-8000-000000000001', now() + interval '1 hour', false),
  ('21000000-0000-4000-8000-000000000003', '11000000-0000-4000-8000-000000000001', now() + interval '2 hours', false),
  ('22000000-0000-4000-8000-000000000001', '11000000-0000-4000-8000-000000000002', now(), false),
  ('22000000-0000-4000-8000-000000000002', '11000000-0000-4000-8000-000000000002', now() + interval '1 hour', false);
select is((select count(*) from refresh_signal_counter where operation = 'insert'), 2::bigint,
  'multi-row INSERT sends one refresh to each affected owner');
select is((select count(distinct owner_id) from refresh_signal_counter where operation = 'insert'), 2::bigint,
  'multi-owner INSERT signals both affected owners');
select is((select max(owner_count) from (select count(*) owner_count from refresh_signal_counter where operation = 'insert' group by owner_id) per_owner), 1::bigint,
  'INSERT never sends more than one refresh for an owner');

delete from refresh_signal_counter;
update public.task_occurrences set reminder_at = now() + interval '1 day';
select is((select count(*) from refresh_signal_counter where operation = 'update'), 2::bigint,
  'multi-row UPDATE sends one refresh to each affected owner');
select is((select max(owner_count) from (select count(*) owner_count from refresh_signal_counter where operation = 'update' group by owner_id) per_owner), 1::bigint,
  'UPDATE never sends more than one refresh for an owner');

delete from refresh_signal_counter;
delete from public.task_occurrences;
select is((select count(*) from refresh_signal_counter where operation = 'delete'), 2::bigint,
  'multi-row DELETE sends one refresh to each affected owner');
select is((select max(owner_count) from (select count(*) owner_count from refresh_signal_counter where operation = 'delete' group by owner_id) per_owner), 1::bigint,
  'DELETE never sends more than one refresh for an owner');
select is((select count(*) from public.task_occurrences), 0::bigint, 'all seeded occurrences were deleted');

select * from finish();
rollback;
