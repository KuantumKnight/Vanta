"use client";

import { FormEvent, useState } from "react";
import { createClient } from "@/lib/supabase/browser";

export default function LoginPage({
  redirectTo,
  initialError,
}: {
  redirectTo: string;
  initialError?: string;
}) {
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(initialError ?? "");

  async function requestSignInLink() {
    setBusy(true);
    setError("");
    try {
      const supabase = createClient();
      const callbackUrl = new URL("/auth/callback", window.location.origin);
      callbackUrl.searchParams.set("next", redirectTo);
      const { error: requestError } = await supabase.auth.signInWithOtp({
        email,
        options: {
          shouldCreateUser: true,
          emailRedirectTo: callbackUrl.toString(),
        },
      });
      if (requestError) {
        setError(requestError.message);
        return;
      }
      setSent(true);
    } catch {
      setError("Could not send the sign-in link. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  function sendLink(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void requestSignInLink();
  }

  async function resendLink() {
    await requestSignInLink();
  }

  return (
    <main className="page-shell">
      <section className="panel">
        <div className="brand"><span className="brand-mark">S</span><span>SYSTEM</span></div>
        <p className="eyebrow">PERSONAL COMMAND CENTER</p>
        <h1>Sign in to continue</h1>
        <p className="muted">Enter your email and we’ll send a secure sign-in link. Open it on this device to continue to SYSTEM.</p>
        {!sent ? (
          <form className="form" onSubmit={sendLink}>
            <label htmlFor="email">Email address</label>
            <input id="email" type="email" autoComplete="email" required value={email}
              onChange={(event) => setEmail(event.target.value)} placeholder="you@example.com" />
            <button type="submit" disabled={busy}>{busy ? "Sending link…" : "Email me a sign-in link"}</button>
          </form>
        ) : (
          <div className="form">
            <p className="success-message" role="status">A sign-in link was sent to <strong>{email}</strong>.</p>
            <p className="muted">Open the newest email link on this device. It will verify your account and return you here automatically.</p>
            <button className="secondary-button" type="button" disabled={busy} onClick={resendLink}>
              {busy ? "Sending…" : "Resend sign-in link"}
            </button>
            <button className="text-button" type="button" onClick={() => { setSent(false); setError(""); }}>Use a different email</button>
          </div>
        )}
        {error && <p className="error" role="alert">{error}</p>}
        <p className="privacy">Your task data stays in your private SYSTEM account.</p>
      </section>
    </main>
  );
}
