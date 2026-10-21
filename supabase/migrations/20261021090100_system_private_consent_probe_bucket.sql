-- The anonymous upload probe is already absent; keep its bucket private.
update storage.buckets
set public = false
where id = 'system-consent';
