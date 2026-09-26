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

  async function requestSignInCode() {
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
      setCode("");
      setSent(true);
    } catch {
      setError("Could not send the sign-in email. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  function sendCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void requestSignInCode();
  }

  async function resendCode() {
    await requestSignInCode();
  }

  async function verifyCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const supabase = createClient();
      const { error: verifyError } = await supabase.auth.verifyOtp({
        email,
        token: code.trim(),
        type: "email",
      });
      if (verifyError) {
        setError(verifyError.message);
        return;
      }
      window.location.assign(redirectTo);
    } catch {
      setError("Could not verify the code. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page-shell">
      <section className="panel">
        <div className="brand"><span className="brand-mark">S</span><span>SYSTEM</span></div>
        <p className="eyebrow">PERSONAL COMMAND CENTER</p>
        <h1>Sign in to continue</h1>
        <p className="muted">Enter your email and we’ll send a one-time code. Enter the code here to finish signing in.</p>
        {!sent ? (
          <form className="form" onSubmit={sendCode}>
            <label htmlFor="email">Email address</label>
            <input id="email" type="email" autoComplete="email" required value={email}
              onChange={(event) => setEmail(event.target.value)} placeholder="you@example.com" />
            <button type="submit" disabled={busy}>{busy ? "Sending code…" : "Email me a sign-in code"}</button>
          </form>
        ) : (
          <div className="form">
            <p className="success-message" role="status">A sign-in code was sent to <strong>{email}</strong>. Enter the six-digit code below.</p>
            <p className="muted">If your email contains a sign-in link instead, open it in this same browser to continue.</p>
            <form className="form code-form" onSubmit={verifyCode}>
              <label htmlFor="code">One-time code</label>
              <input id="code" type="text" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required value={code}
                onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))} placeholder="000000" />
              <button type="submit" disabled={busy || !code.trim()}>{busy ? "Verifying…" : "Verify code"}</button>
            </form>
            <button className="secondary-button" type="button" disabled={busy} onClick={resendCode}>
              {busy ? "Sending…" : "Resend code"}
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
