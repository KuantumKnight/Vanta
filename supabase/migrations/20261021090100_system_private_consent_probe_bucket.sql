-- The anonymous upload probe was removed, so its bucket must not remain public.
delete from storage.objects
where bucket_id = 'system-consent' and name = 'probe.html';

update storage.buckets
set public = false
where id = 'system-consent';
