import { redirect } from "next/navigation";
import { createClient } from "@/lib/supabase/server";

type PageProps = {
  searchParams: Promise<{ authorization_id?: string }>;
};

export default async function ConsentPage({ searchParams }: PageProps) {
  const { authorization_id: authorizationId } = await searchParams;
  if (!authorizationId || authorizationId.length > 512) {
    return <main className="page-shell"><section className="panel"><h1>Invalid authorization request</h1><p className="muted">Return to ChatGPT and try connecting again.</p></section></main>;
  }

  const supabase = await createClient();
  const { data: claimsData } = await supabase.auth.getClaims();
  if (!claimsData?.claims) {
    const continuation = "/oauth/consent?authorization_id=" + encodeURIComponent(authorizationId);
    redirect("/login?redirect=" + encodeURIComponent(continuation));
  }

  const { data, error } = await supabase.auth.oauth.getAuthorizationDetails(authorizationId);
  if (error || !data) {
    return <main className="page-shell"><section className="panel"><h1>Could not load request</h1><p className="error">{error?.message ?? "Try again from ChatGPT."}</p></section></main>;
  }

  const details = data as unknown as Record<string, unknown>;
  if (!("authorization_id" in details) && typeof details.redirect_url === "string") {
    redirect(details.redirect_url);
  }
  const client = typeof details.client === "object" && details.client !== null
    ? details.client as Record<string, unknown>
    : {};
  const name = typeof client.name === "string" ? client.name : "ChatGPT";
  const redirectUri = typeof details.redirect_uri === "string" ? details.redirect_uri : "Connected application";
  const scopes = typeof details.scope === "string" ? details.scope.split(" ").filter(Boolean) : [];

  return (
    <main className="page-shell">
      <section className="panel">
        <div className="brand"><span className="brand-mark">S</span><span>SYSTEM</span></div>
        <p className="eyebrow">ACCOUNT CONNECTION</p>
        <h1>Allow {name} to connect?</h1>
        <p className="muted">This connection lets ChatGPT read and manage tasks in your SYSTEM account.</p>
        <div className="request-card">
          <p className="card-label">REQUESTING APP</p>
          <p className="request-name">{name}</p>
          <p className="card-label">RETURN ADDRESS</p>
          <p className="request-uri">{redirectUri}</p>
          <p className="card-label">REQUESTED ACCOUNT DETAILS</p>
          {scopes.length ? (
            <ul>{scopes.map((scope) => <li key={scope}>{scope}</li>)}</ul>
          ) : <p className="muted small">No profile details requested. SYSTEM task access is controlled by your account policies.</p>}
        </div>
        <form className="actions" action="/api/oauth/decision" method="post">
          <input type="hidden" name="authorization_id" value={authorizationId} />
          <button type="submit" name="decision" value="approve">Approve connection</button>
          <button className="secondary-button" type="submit" name="decision" value="deny">Deny</button>
        </form>
        <p className="privacy">You can revoke this connection later from your Supabase Auth grants.</p>
      </section>
    </main>
  );
}
