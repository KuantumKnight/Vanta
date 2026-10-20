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

The original hosted migrations and four Edge Functions are deployed. The previously applied refresh migration `20261017045929_system_push_refresh_best_effort.sql` and device-token lifecycle migration `20261021100000_system_device_token_lifecycle.sql` are tracked in this repository and match the hosted migration history. The four audit migrations dated `20261021090000` through `20261021090200` remain a separate rollout; apply them in order and run the acceptance checks in [VERIFICATION.md](VERIFICATION.md) before relying on those changes. Do not run local `supabase start` for this hosted setup.

Set the server-only Edge Function secrets in the Supabase dashboard or with the CLI. Supabase supplies `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` to functions automatically.

```powershell
npx supabase secrets set INTERNAL_FUNCTION_SECRET=<random-long-secret>
npx supabase secrets set FCM_PROJECT_ID=<firebase-project-id> FCM_CLIENT_EMAIL=<service-account-email> FCM_PRIVATE_KEY="<private-key-with-escaped-newlines>"
```

Use a fresh random value for `INTERNAL_FUNCTION_SECRET`; the same value is stored in Supabase Vault by the server-jobs migration so database triggers and scheduled jobs can call the Edge Functions. Never put this value, a service-role key, or an FCM private key in Android, Vercel, or source control.

The migrations install statement-level, best-effort database triggers for refresh signals and schedule recurrence expansion and reminders. A refresh failure does not roll back task writes, and bulk occurrence inserts send one refresh signal per owner and statement. The historical anonymous consent-storage probe bucket is made private by a follow-up migration. Check the Supabase cron run history after setup. `sync-push`, `expand-schedules`, and `send-reminders` use the internal secret for calls from Postgres; the MCP function uses Supabase OAuth bearer tokens and runs with the signed-in user's identity under RLS.

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

The repository includes a manual GitHub Actions deployment fallback at **Actions → Deploy SYSTEM consent to Vercel → Run workflow**. The deployment job only runs from `main`. The Vercel project and team IDs are stored as GitHub Actions repository variables. Add a Vercel token with access to the `system-consent` project as the repository Actions secret `VERCEL_TOKEN`; the secret is read only by that workflow and is not stored in the repository. The current local Vercel CLI session and connected Vercel integration cannot access this linked project, so the workflow is the deployment path until access is restored.

The consent site and Android use passwordless email sign-in. The Android app supports the native callback at `dev.system.app://auth` for older links and can also verify an email code on any device. The consent site's `/auth/callback` remains available for OAuth and older confirmation links.

The selected hosted project uses 8-digit email tokens (`auth.email.otp_length = 8`), but its free-tier project with the default email provider rejects hosted template edits. The Management API returned: “Email template modification is not available for free tier projects using the default email provider.” Configure custom SMTP or upgrade the project before switching the shared email to a code. Then open **Supabase Dashboard → Authentication → Email Templates**. For both **Magic Link** and **Confirm signup**, set the subject to `Your SYSTEM sign-in code` and replace the message body with:

```html
<h2>Your SYSTEM sign-in code</h2>
<p>Enter this 8-digit code in SYSTEM:</p>
<p style="font-size: 28px; font-weight: bold; letter-spacing: 0.2em">{{ .Token }}</p>
<p>If you did not request this code, you can ignore this email.</p>
```

Save both templates and remove `{{ .ConfirmationURL }}` from those two bodies. Then request a fresh email from the updated Android build or consent site; older emails still contain one-use links. Hosted email templates are edited in the dashboard or through the Supabase Management API; `supabase config push` does not manage those hosted template bodies.

The Android and consent builds accept an 8-digit code and retain the magic-link callback as a fallback. Until custom SMTP is configured or the project is upgraded and the templates are changed, the default provider continues sending links. A real inbox sign-in still needs verification.

The consent page shows the requesting OAuth app and requested scopes, then calls Supabase's approve or deny method. It never handles or stores a Supabase secret key.

## Android local build and sign-in

Create `android/local.properties` (this file is git-ignored):

```properties
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_PUBLISHABLE_KEY=<publishable-key>
```

Install JDK 17 and Android SDK 37.0, then build:

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

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. Install it on a Google Play services device or emulator, request a sign-in link, and open the newest link on the same Android phone to return to the app. For a single-owner account, allow the first sign-up, verify access, then turn off public sign-ups in Supabase Auth.

To enable push refresh, add the Firebase Android app for the application ID in `android/app/build.gradle.kts`, put its `google-services.json` at `android/app/google-services.json` (git-ignored), enable Firebase Cloud Messaging, and set the FCM service-account values above. Never commit the service-account key. Push delivery is best effort; connected-network WorkManager sync is a recovery path and can run later than its 15-minute minimum interval.

For the current SYSTEM setup, Firebase project `gdg-web-dev` has Android app `dev.system.app` registered. Its `google-services.json` is downloaded locally and ignored by Git. GitHub Actions decodes the same file from repository secret `SYSTEM_GOOGLE_SERVICES_JSON_B64` so the CI APK initializes Firebase; never commit the config file or a service-account key. A dedicated sender account, `system-fcm-sender@gdg-web-dev.iam.gserviceaccount.com`, has the project custom role `systemFcmSender`, limited to `cloudmessaging.messages.create`. The Firebase Cloud Messaging API is enabled. `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, and `FCM_PRIVATE_KEY` are configured as Supabase Edge Function secrets. Token registration uses an authenticated RPC. A token can be reused by its current owner or reassigned after it has been disabled; an active token owned by another account is rejected. Finish by signing in on the Android device, confirming its token appears under the owner in `device_tokens`, and testing refresh delivery with the app closed.

On pushes to `main`, GitHub Actions uses the repository secrets `SYSTEM_GOOGLE_SERVICES_JSON_B64`, `SYSTEM_SUPABASE_URL`, and `SYSTEM_SUPABASE_PUBLISHABLE_KEY`. Pull-request builds use generated non-secret Firebase and Supabase placeholders. CI runs consent lint/build, Deno recurrence tests and Edge Function checks, plus Android build, lint, and API 36 instrumentation tests; credentials are not stored in the repository.

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
