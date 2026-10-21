begin;
create extension if not exists pgtap with schema extensions;
select plan(3);

with objects(name, is_view) as (
  values
    ('public.tasks', false),
    ('public.habit_schedules', false),
    ('public.task_occurrences', false),
    ('public.completion_requests', false),
    ('public.goals', false),
    ('public.goal_links', false),
    ('public.device_tokens', false),
    ('public.reminder_deliveries', false),
    ('public.goal_progress', true),
    ('public.daily_reviews', false)
), privileges(name) as (
  values ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE')
)
select ok(
  not exists (
    select 1
    from objects cross join privileges
    where has_table_privilege('anon', objects.name, privileges.name)
  ),
  'anonymous role has no direct privileges on public Vanta API objects'
);

with objects(name, is_view) as (
  values
    ('public.tasks', false),
    ('public.habit_schedules', false),
    ('public.task_occurrences', false),
    ('public.completion_requests', false),
    ('public.goals', false),
    ('public.goal_links', false),
    ('public.device_tokens', false),
    ('public.reminder_deliveries', false),
    ('public.goal_progress', true),
    ('public.daily_reviews', false)
), privileges(name) as (
  values ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE')
), expected as (
  select objects.name, privileges.name as privilege,
    case
      when objects.name in (
        'public.tasks', 'public.habit_schedules', 'public.task_occurrences',
        'public.goals', 'public.goal_links', 'public.goal_progress',
        'public.daily_reviews'
      ) then privileges.name = 'SELECT'
      when objects.name = 'public.device_tokens'
        then privileges.name in ('SELECT', 'UPDATE')
      else false
    end as allowed
  from objects cross join privileges
)
select ok(
  not exists (
    select 1 from expected
    where has_table_privilege('authenticated', expected.name, expected.privilege)
      is distinct from expected.allowed
  ),
  'authenticated role has only the intended read and device-token update privileges'
);

with objects(name, is_view) as (
  values
    ('public.tasks', false),
    ('public.habit_schedules', false),
    ('public.task_occurrences', false),
    ('public.completion_requests', false),
    ('public.goals', false),
    ('public.goal_links', false),
    ('public.device_tokens', false),
    ('public.reminder_deliveries', false),
    ('public.goal_progress', true),
    ('public.daily_reviews', false)
), privileges(name) as (
  values ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE')
), expected as (
  select objects.name, privileges.name as privilege,
    case
      when objects.is_view then privileges.name = 'SELECT'
      else true
    end as allowed
  from objects cross join privileges
)
select ok(
  not exists (
    select 1 from expected
    where has_table_privilege('service_role', expected.name, expected.privilege)
      is distinct from expected.allowed
  ),
  'service_role has explicit full table access and read-only access to goal_progress'
);

select * from finish();
rollback;
