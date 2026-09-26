import { redirect } from "next/navigation";
import { createClient } from "@/lib/supabase/server";

export default async function Home() {
  const supabase = await createClient();
  const { data } = await supabase.auth.getClaims();

  if (!data?.claims) {
    redirect("/login");
  }

  return (
    <main className="page-shell">
      <section className="panel">
        <div className="brand"><span className="brand-mark">S</span><span>SYSTEM</span></div>
        <p className="eyebrow">ACCOUNT READY</p>
        <h1>You’re signed in</h1>
        <p className="muted">Your SYSTEM account is ready. You can close this tab and return to ChatGPT.</p>
      </section>
    </main>
  );
}
