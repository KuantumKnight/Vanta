"use client";

import { FormEvent, useState } from "react";

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

  async function requestSignInEmail() {
    setBusy(true);
    setError("");
    try {
      const response = await fetch("/auth/send-code", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, next: redirectTo }),
      });
      if (!response.ok) throw new Error("Could not send the sign-in email.");
      setCode("");
      setSent(true);
    } catch {
      setError("Could not send the sign-in email. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  function sendEmail(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void requestSignInEmail();
  }

  async function verifyCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const response = await fetch("/auth/verify-code", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, token: code, next: redirectTo }),
      });
      const result = await response.json() as { next?: string; error?: string };
      if (!response.ok || !result.next) {
        setError(result.error ?? "That code is invalid or expired. Check it and try again.");
        return;
      }
      window.location.assign(result.next);
    } catch {
      setError("Could not verify that code. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page-shell">
      <section className="panel">
        <div className="brand"><span className="brand-mark">S</span><span>SYSTEM</span></div>
        <p className="eyebrow">PERSONAL COMMAND CENTER</p>
        <h1>{sent ? "Check your email" : "Sign in to continue"}</h1>
        <p className="muted">{sent
          ? <>If the email has an 8-digit code, enter it below. If it has a sign-in link, open it in this browser.</>
          : "Enter your email and we’ll send a secure sign-in email. Use its code or open its link."}</p>
        {!sent ? (
          <form className="form" onSubmit={sendEmail}>
            <label htmlFor="email">Email address</label>
            <input id="email" type="email" autoComplete="email" required value={email}
              onChange={(event) => setEmail(event.target.value)} placeholder="you@example.com" />
            <button type="submit" disabled={busy}>{busy ? "Sending email…" : "Send sign-in email"}</button>
          </form>
        ) : (
          <form className="form" onSubmit={verifyCode}>
            <label htmlFor="code">Email code (if included)</label>
            <input id="code" type="text" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{8}"
              maxLength={8} required value={code}
              onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 8))}
              placeholder="12345678" />
            <button type="submit" disabled={busy || code.length !== 8}>
              {busy ? "Verifying…" : "Verify and continue"}
            </button>
            <button className="secondary-button" type="button" disabled={busy} onClick={() => void requestSignInEmail()}>
              {busy ? "Sending…" : "Resend sign-in email"}
            </button>
            <button className="text-button" type="button" disabled={busy} onClick={() => { setSent(false); setCode(""); setError(""); }}>
              Use a different email
            </button>
          </form>
        )}
        {error && <p className="error" role="alert">{error}</p>}
        <p className="privacy">Your task data stays in your private SYSTEM account.</p>
      </section>
    </main>
  );
}
