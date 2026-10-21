# SYSTEM

SYSTEM is a personal task and habit dashboard for Android, with ChatGPT as the command center. Supabase Postgres is the source of truth; the Android Room database is only an offline cache.

## Project layout

- `android/` — Kotlin, Jetpack Compose, Glance widgets, Room cache, sync, and completion queue.
- `supabase/` — Postgres migrations and Edge Functions for MCP, push refresh, recurrence, and reminders.
- `web/consent/` — OAuth consent and passwordless code/link sign-in site.
- `docs/` — deployment and verification steps.

## Quick start

1. Set `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` in the ignored `android/local.properties` file.
2. Build the Android app with JDK 17 and Android SDK 37.0: `cd android; .\gradlew.bat assembleDebug`.
3. Deploy `web/consent` to Vercel and configure its two `NEXT_PUBLIC_SUPABASE_*` values.
4. Set the Supabase Auth Site URL to the consent site's HTTPS origin.
5. Connect ChatGPT Developer mode to the Supabase `/functions/v1/mcp` endpoint and complete OAuth linking.

See [docs/SETUP.md](docs/SETUP.md) for full instructions and [docs/VERIFICATION.md](docs/VERIFICATION.md) for acceptance checks.
See [docs/V1_COMPLETION_PLAN.md](docs/V1_COMPLETION_PLAN.md) for current progress, remaining work, and the V1 definition of done.

## Product defaults

- Time zone: `Asia/Kolkata`.
- The widget lists overdue and today’s unfinished tasks, then upcoming dated tasks.
- Date-only deadlines default to a 9 AM reminder on the previous day.
- Recurrences use RFC 5545 rules and exclusions, with a minimum interval of 15 minutes.
- XP and levels are deferred until the core workflow is reliable.

The repository uses the hosted Supabase project for controlled database acceptance. GitHub Actions runs consent, Edge Function, and Android checks without a local database stack.
