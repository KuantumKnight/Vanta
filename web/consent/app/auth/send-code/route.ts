import { NextRequest, NextResponse } from "next/server";
import { createClient } from "@/lib/supabase/server";

function validSameOrigin(request: NextRequest): boolean {
  return request.headers.get("origin") === request.nextUrl.origin;
}

function safeNextPath(value: unknown): string {
  if (typeof value !== "string" || !value.startsWith("/") || value.startsWith("//") || value.includes("\\")) {
    return "/";
  }
  return value;
}

export async function POST(request: NextRequest) {
  if (!validSameOrigin(request)) {
    return NextResponse.json({ error: "Request rejected." }, { status: 403 });
  }

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "Enter a valid email address." }, { status: 400 });
  }

  const values = typeof body === "object" && body !== null ? body as Record<string, unknown> : {};
  const email = typeof values.email === "string" ? values.email.trim() : "";
  if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    return NextResponse.json({ error: "Enter a valid email address." }, { status: 400 });
  }

  const callbackUrl = new URL("/auth/callback", request.nextUrl.origin);
  callbackUrl.searchParams.set("next", safeNextPath(values.next));
  const supabase = await createClient();
  const { error } = await supabase.auth.signInWithOtp({
    email,
    options: { shouldCreateUser: true, emailRedirectTo: callbackUrl.toString() },
  });
  if (error) {
    return NextResponse.json({ error: "Could not send a sign-in code. Try again shortly." }, { status: 429 });
  }

  return NextResponse.json({ sent: true });
}
