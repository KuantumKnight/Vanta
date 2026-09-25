import LoginForm from "./login-form";

function safeRedirect(value: string | undefined): string {
  return value && value.startsWith("/") && !value.startsWith("//") && !value.includes("\\") ? value : "/";
}

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ redirect?: string; error?: string }>;
}) {
  const params = await searchParams;
  const initialError = params.error === "auth_callback"
    ? "That sign-in link expired or could not be verified. Request a fresh email and use its newest link."
    : undefined;
  return <LoginForm redirectTo={safeRedirect(params.redirect)} initialError={initialError} />;
}
