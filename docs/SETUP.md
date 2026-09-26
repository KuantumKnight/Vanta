# SYSTEM setup and deployment

## What runs where

- Supabase Postgres is the source of truth. Android Room is an offline cache.
- The Supabase Edge Functions provide the ChatGPT MCP endpoint, sync pushes, recurring occurrence generation, and reminders.
- `web/consent` is the HTTPS OAuth consent and passwordless email sign-in site.
- Android uses Kotlin, Compose, Room, Glance, WorkManager, and Firebase Cloud Messaging.

Docker is optional. It is only needed to run a local Supabase stack; app development and a hosted Supabase project do not require it.

## Supabase project

Use the existing hosted project or create one in the Supabase dashboard. Apply the migrations in `supabase/migrations` in filename order. When using the CLI, authenticate and link the project first:

```powershell
npx supabase login
npx supabase link --project-ref <project-ref>
npx supabase db push
```

For the already hosted project, the migrations in this repository have been applied and the four Edge Functions are deployed. Do not run local `supabase start` for this setup.

Set the server-only Edge Function secrets in the Supabase dashboard or with the CLI. Supabase supplies `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` to functions automatically.

```powershell
npx supabase secrets set INTERNAL_FUNCTION_SECRET=<random-long-secret>
npx supabase secrets set FCM_PROJECT_ID=<firebase-project-id> FCM_CLIENT_EMAIL=<service-account-email> FCM_PRIVATE_KEY="<private-key-with-escaped-newlines>"
```

Use a fresh random value for `INTERNAL_FUNCTION_SECRET`; the same value is stored in Supabase Vault by the server-jobs migration so database triggers and scheduled jobs can call the Edge Functions. Never put this value, a service-role key, or an FCM private key in Android, Vercel, or source control.

The migration installs database triggers for refresh signals and schedules recurrence expansion and reminders. Check the Supabase cron run history after setup. `sync-push`, `expand-schedules`, and `send-reminders` use the internal secret for calls from Postgres; the MCP function uses Supabase OAuth bearer tokens and runs with the signed-in user's identity under RLS.

## OAuth consent site on Vercel

From `web/consent`, create or select a Vercel project with this directory as the project root. Set these Production environment variables:

```text
NEXT_PUBLIC_SUPABASE_URL=https://<project-ref>.supabase.co
NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY=<publishable-key>
```

Both values are public client configuration; they are safe to use in browser code. Do not add Supabase secret keys here.

One CLI route is:

```powershell
cd web/consent
npx vercel login
npx vercel link
npx vercel env add NEXT_PUBLIC_SUPABASE_URL production
npx vercel env add NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY production
npx vercel --prod
```

After deployment, open the production origin and verify the SYSTEM sign-in page loads over HTTPS. For this project, set the Supabase Auth Site URL to `https://system-consent.vercel.app` and allow `https://system-consent.vercel.app/auth/callback` plus the Android callback `dev.system.app://auth`. Keep the OAuth server enabled with dynamic client registration and authorization URL path `/oauth/consent`; update the enabled flag and path together. Review `supabase config diff` before any hosted config push.

The consent site and Android use Supabase passwordless email links. The consent link returns through `/auth/callback`; the Android link opens the installed app through `dev.system.app://auth`. The app URI is in `supabase/config.toml` and has been added to the hosted Auth redirect allowlist. Open the email on the Android phone and tap the newest link; older emails still use their original redirect.

The selected Supabase project currently sends magic links rather than copyable OTP codes. The Android app handles the link directly and does not ask for the email a second time. Leave the hosted Magic Link and Confirm signup templates on their default link behavior. Supabase's free plan with the default email provider blocks hosted template edits; switching these templates to `{{ .Token }}` requires a supported SMTP setup or plan change. That is not needed for this link-based flow.

If a new sign-in email still redirects to `localhost`, check that the app requested a fresh email after the hosted redirect allowlist was updated and that the installed build includes the native callback. Do not use an older email link.

The consent page shows the requesting OAuth app and requested scopes, then calls Supabase's approve or deny method. It never handles or stores a Supabase secret key.

## Android local build and sign-in

Create `android/local.properties` (this file is git-ignored):

```properties
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_PUBLISHABLE_KEY=<publishable-key>
```

Install JDK 17 and Android SDK 37, then build:

```powershell
cd android
.\gradlew.bat assembleDebug
```

If Gradle reports `Unable to establish loopback connection` under Java 17 on Windows, use a short, writable directory for Java's local Unix-domain sockets in that PowerShell session:

```powershell
New-Item -ItemType Directory -Force ..\.gradle-tmp | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$((Resolve-Path ..\.gradle-tmp).Path)"
.\gradlew.bat assembleDebug
Remove-Item Env:JAVA_TOOL_OPTIONS
```

The temporary socket directory is ignored by Git. This is a per-shell workaround and does not change machine-wide Java settings.

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. Install it on a Google Play services device or emulator, request an email code, and complete sign-in in the app. For a single-owner account, allow the first sign-up, verify access, then turn off public sign-ups in Supabase Auth.

To enable push refresh, add the Firebase Android app for the application ID in `android/app/build.gradle.kts`, put its `google-services.json` at `android/app/google-services.json` (git-ignored), enable Firebase Cloud Messaging, and set the FCM service-account values above. Never commit the service-account key. Push delivery is best effort; periodic WorkManager sync is a recovery path and can run later than its 15-minute minimum interval.

For the current SYSTEM setup, Firebase project `gdg-web-dev` has Android app `dev.system.app` registered. Its `google-services.json` is downloaded locally and ignored by Git. GitHub Actions decodes the same file from repository secret `SYSTEM_GOOGLE_SERVICES_JSON_B64` so the CI APK initializes Firebase; never commit the config file or a service-account key. A dedicated sender account, `system-fcm-sender@gdg-web-dev.iam.gserviceaccount.com`, has the project custom role `systemFcmSender`, limited to `cloudmessaging.messages.create`. The Firebase Cloud Messaging API is enabled. The remaining push setup is to create its private key and set `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, and `FCM_PRIVATE_KEY` as Supabase Edge Function secrets; keep the key outside source control and Android.

## ChatGPT connection

In ChatGPT Developer mode, add the private MCP server at:

```text
https://<project-ref>.supabase.co/functions/v1/mcp
```

Complete Supabase OAuth account linking using the hosted consent page. Then call `get_context`, create “50 push-ups” for the next local date, and confirm it appears under Upcoming in the widget.

## Local checks

```powershell
cd web/consent
npm ci
npm run lint
npm run build
```

With Deno installed, run Edge Function checks from the repository root:

```powershell
deno test --config supabase/deno.json supabase/functions/_shared/recurrence_test.ts
deno check --config supabase/deno.json supabase/functions/mcp/index.ts supabase/functions/expand-schedules/index.ts supabase/functions/sync-push/index.ts supabase/functions/send-reminders/index.ts
```

No Docker daemon is needed for these checks. See `docs/VERIFICATION.md` for backend and device acceptance steps.
