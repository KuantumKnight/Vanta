create table public.daily_reviews (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  review_date date not null,
  note text,
  completed_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (user_id, review_date),
  check (note is null or char_length(note) <= 4000)
);

alter table public.daily_reviews enable row level security;

create policy "Owners read daily reviews" on public.daily_reviews
  for select to authenticated using (user_id = (select auth.uid()));

revoke all on public.daily_reviews from anon, authenticated;
grant select on public.daily_reviews to authenticated;

create or replace function public.system_save_daily_review(p_review_date date, p_note text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  normalized_note text := nullif(btrim(p_note), '');
begin
  if current_user_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;
  if p_review_date is null then
    raise exception 'Review date is required' using errcode = '22023';
  end if;
  if normalized_note is not null and char_length(normalized_note) > 4000 then
    raise exception 'Review note is too long' using errcode = '22023';
  end if;

  insert into public.daily_reviews(user_id, review_date, note, completed_at, updated_at)
  values (current_user_id, p_review_date, normalized_note, now(), now())
  on conflict (user_id, review_date) do update
    set note = excluded.note,
        completed_at = excluded.completed_at,
        updated_at = excluded.updated_at;
end;
$$;

revoke all on function public.system_save_daily_review(date, text) from public, anon;
grant execute on function public.system_save_daily_review(date, text) to authenticated;

create or replace function public.system_list_occurrence_history(
  p_start timestamptz,
  p_end timestamptz,
  p_kind text default null,
  p_limit integer default 500,
  p_offset integer default 0
)
returns table (
  id uuid,
  task_id uuid,
  title text,
  notes text,
  scheduled_for timestamptz,
  all_day boolean,
  kind text,
  status text,
  completed_at timestamptz,
  duration_minutes integer
)
language sql
stable
security invoker
set search_path = ''
as $$
  select o.id, o.task_id, t.title, t.notes, o.scheduled_for, o.all_day,
         t.kind, o.status, o.completed_at, o.duration_minutes
  from public.task_occurrences as o
  join public.tasks as t on t.id = o.task_id and t.user_id = o.user_id
  where o.user_id = (select auth.uid())
    and t.archived_at is null
    and o.scheduled_for >= p_start
    and o.scheduled_for < p_end
    and (p_kind is null or t.kind = p_kind)
  order by o.scheduled_for asc, o.id asc
  limit least(greatest(coalesce(p_limit, 500), 1), 500)
  offset greatest(coalesce(p_offset, 0), 0);
$$;

revoke all on function public.system_list_occurrence_history(timestamptz, timestamptz, text, integer, integer) from public, anon;
grant execute on function public.system_list_occurrence_history(timestamptz, timestamptz, text, integer, integer) to authenticated;
