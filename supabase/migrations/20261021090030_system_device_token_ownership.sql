-- Registration must be able to move the app's one FCM token when this device
-- signs into a different account. Match the later deployed lifecycle behavior
-- here because this older version is still pending and may be applied after
-- 20261021100000 on production.
revoke insert on public.device_tokens from public, anon, authenticated;

create or replace function public.system_register_device_token(p_token text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_device_id uuid;
begin
  if v_user_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;
  if p_token is null or length(p_token) not between 20 and 4096 then
    raise exception 'Invalid device token' using errcode = '22023';
  end if;

  insert into public.device_tokens as current_device(user_id, token, platform, enabled)
  values (v_user_id, p_token, 'android', true)
  on conflict (token) do update
    set user_id = excluded.user_id,
        platform = excluded.platform,
        enabled = true,
        updated_at = now()
    where current_device.user_id = v_user_id or not current_device.enabled
  returning id into v_device_id;

  if v_device_id is null then
    raise exception 'Device token is registered to another active account' using errcode = '42501';
  end if;
  return v_device_id;
end;
$$;
revoke all on function public.system_register_device_token(text) from public, anon;
grant execute on function public.system_register_device_token(text) to authenticated;
