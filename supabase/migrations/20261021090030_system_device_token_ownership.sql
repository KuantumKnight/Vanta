-- Registration must be able to move the app's one FCM token when this device
-- signs into a different account. Do that through an owner-checked RPC instead
-- of an RLS-blocked upsert against the globally unique token.
revoke insert on public.device_tokens from public, anon, authenticated;

create or replace function public.system_register_device_token(p_token text)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  current_user_id uuid := auth.uid();
begin
  if current_user_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;
  if p_token is null or length(p_token) < 20 or length(p_token) > 4096 then
    raise exception 'Invalid device token' using errcode = '22023';
  end if;

  insert into public.device_tokens(user_id, token, platform, enabled, updated_at)
  values (current_user_id, p_token, 'android', true, now())
  on conflict (token) do update
    set user_id = excluded.user_id,
        enabled = true,
        updated_at = now();
end;
$$;
revoke all on function public.system_register_device_token(text) from public, anon;
grant execute on function public.system_register_device_token(text) to authenticated;
