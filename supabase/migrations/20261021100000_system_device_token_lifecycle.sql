-- FCM tokens are globally unique. Only the authenticated device owner may
-- refresh an active registration; an inactive token can be reassigned after
-- sign-out. Clients cannot bypass this rule with a table upsert.
revoke insert on public.device_tokens from public, anon, authenticated;

-- The prior repo migration declared this RPC as RETURNS void. Recreate it so
-- the result can identify the idempotently registered device row.
drop function if exists public.system_register_device_token(text);

create function public.system_register_device_token(p_token text)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  current_user_id uuid := auth.uid();
  registered_device_id uuid;
begin
  if current_user_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;
  if p_token is null or length(p_token) not between 20 and 4096 then
    raise exception 'Invalid device token' using errcode = '22023';
  end if;

  insert into public.device_tokens as current_device(user_id, token, platform, enabled)
  values (current_user_id, p_token, 'android', true)
  on conflict (token) do update
    set user_id = excluded.user_id,
        platform = excluded.platform,
        enabled = true,
        updated_at = now()
    where current_device.user_id = current_user_id or not current_device.enabled
  returning id into registered_device_id;

  if registered_device_id is null then
    raise exception 'Device token is registered to another active account' using errcode = '42501';
  end if;
  return registered_device_id;
end;
$$;

revoke all on function public.system_register_device_token(text) from public, anon;
grant execute on function public.system_register_device_token(text) to authenticated;
