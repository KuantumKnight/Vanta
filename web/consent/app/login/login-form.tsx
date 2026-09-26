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
  const [code, setCode] = useState("");
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(initialError ?? "");

  async function requestLink() {
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
      setError("Could not send the sign-in email. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  function sendCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void requestLink();
  }

  async function sendAnotherLink() {
    await requestLink();
  }

  async function verifyCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    const supabase = createClient();
    const { error: verifyError } = await supabase.auth.verifyOtp({
      email,
      token: code.trim(),
      type: "email",
    });
    setBusy(false);
    if (verifyError) {
      setError(verifyError.message);
      return;
    }
    window.location.assign(redirectTo);
  }

  return (
    <main className="page-shell">
      <section className="panel">
        <div className="brand"><span className="brand-mark">S</span><span>SYSTEM</span></div>
        <p className="eyebrow">PERSONAL COMMAND CENTER</p>
        <h1>Sign in to continue</h1>
        <p className="muted">Enter your email and we’ll send a secure sign-in link. Open it to finish signing in.</p>
        {!sent ? (
          <form className="form" onSubmit={sendCode}>
            <label htmlFor="email">Email address</label>
            <input id="email" type="email" autoComplete="email" required value={email}
              onChange={(event) => setEmail(event.target.value)} placeholder="you@example.com" />
            <button type="submit" disabled={busy}>{busy ? "Sending sign-in link…" : "Email me a sign-in link"}</button>
          </form>
        ) : (
          <div className="form">
            <p className="success-message" role="status">Sign-in link sent to <strong>{email}</strong>. Open it to continue. If it opens in another browser, copy the link into this browser.</p>
            <form className="form code-form" onSubmit={verifyCode}>
              <label htmlFor="code">Have a one-time code instead?</label>
              <input id="code" inputMode="numeric" autoComplete="one-time-code" value={code}
                onChange={(event) => setCode(event.target.value)} placeholder="Enter your email code" />
              <button type="submit" disabled={busy || !code.trim()}>{busy ? "Verifying…" : "Verify code"}</button>
            </form>
            <button className="secondary-button" type="button" disabled={busy} onClick={sendAnotherLink}>
              {busy ? "Sending…" : "Resend sign-in link"}
            </button>
            <button className="text-button" type="button" onClick={() => { setSent(false); setCode(""); setError(""); }}>Use a different email</button>
          </div>
        )}
        {error && <p className="error" role="alert">{error}</p>}
        <p className="privacy">Your task data stays in your private SYSTEM account.</p>
      </section>
    </main>
  );
}
