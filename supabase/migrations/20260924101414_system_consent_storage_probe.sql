insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('system-consent', 'system-consent', true, 32768, array['text/html']::text[]);

create policy system_consent_probe_upload
on storage.objects
for all to anon
using (bucket_id = 'system-consent' and name = 'probe.html')
with check (bucket_id = 'system-consent' and name = 'probe.html');
