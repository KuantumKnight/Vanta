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

  async function sendCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
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
    setBusy(false);
    if (requestError) {
      setError(requestError.message);
      return;
    }
    setSent(true);
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
        <p className="muted">Enter your email. A one-time code creates or opens your private SYSTEM account.</p>
        {!sent ? (
          <form className="form" onSubmit={sendCode}>
            <label htmlFor="email">Email address</label>
            <input id="email" type="email" autoComplete="email" required value={email}
              onChange={(event) => setEmail(event.target.value)} placeholder="you@example.com" />
            <button type="submit" disabled={busy}>{busy ? "Sending code…" : "Send one-time code"}</button>
          </form>
        ) : (
          <form className="form" onSubmit={verifyCode}>
            <label htmlFor="code">One-time code</label>
            <input id="code" inputMode="numeric" autoComplete="one-time-code" required value={code}
              onChange={(event) => setCode(event.target.value)} placeholder="Enter the code from your email" />
            <button type="submit" disabled={busy}>{busy ? "Verifying…" : "Verify and continue"}</button>
            <button className="text-button" type="button" onClick={() => { setSent(false); setCode(""); }}>Use a different email</button>
          </form>
        )}
        {error && <p className="error" role="alert">{error}</p>}
        <p className="privacy">Your task data stays in your private SYSTEM account.</p>
      </section>
    </main>
  );
}
