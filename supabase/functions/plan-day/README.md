# `plan-day` Edge Function

This optional endpoint creates a structured day-plan proposal. It never writes
tasks or schedule rows. The Android client must display the validated proposal
and wait for an explicit user action before applying placements through the
existing local-first mutation path.

## Runtime configuration

Configure `OPENAI_API_KEY` and `OPENAI_MODEL` as Supabase Edge Function secrets
before deploying this function. The function returns HTTP 503 with a generic
unavailable message when either is absent, so calendar sync, Today rendering,
and ordinary schedule edits continue to work.

Supabase's `withSupabase({ auth: "user" })` validates the caller's Supabase
session. The endpoint uses that user-scoped client to reload every requested
occurrence and confirm its owner, pending status, flexible type, duration,
deadline, and scheduling bounds. It does not use the service role.

## Data sent to the model

The model receives only the date/time zone, the planning window, compact busy
intervals (title/start/end), and canonical flexible-task fields (occurrence
ID/title/duration/priority/deadline/window/preferences). It does not receive
task notes, external event IDs, attendee lists, locations, conference data, or
completed-task IDs. Requests use `store: false`.

The output has a strict JSON schema. The function independently validates
ownership, task status/type, time bounds, exact duration, deadlines, busy-block
conflicts, duplicate IDs, and overlap among proposed placements. Rejected items
become unscheduled explanations. The response is still only a proposal and is
never applied by the function.
