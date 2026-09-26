import LoginForm from "./login-form";
import { createClient } from "@/lib/supabase/server";
import { redirect } from "next/navigation";

function safeRedirect(value: string | undefined): string {
  return value && value.startsWith("/") && !value.startsWith("//") && !value.includes("\\") ? value : "/";
}

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ redirect?: string; error?: string }>;
}) {
  const params = await searchParams;
  const redirectTo = safeRedirect(params.redirect);
  const supabase = await createClient();
  const { data } = await supabase.auth.getClaims();
  if (data?.claims) {
    redirect(redirectTo === "/login" ? "/" : redirectTo);
  }

  const initialError = params.error === "auth_callback"
    ? "That sign-in link expired or could not be verified. Request a fresh email and use its newest link."
    : undefined;
  return <LoginForm redirectTo={redirectTo} initialError={initialError} />;
}
