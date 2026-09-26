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
    return NextResponse.json({ error: "Enter the code from your email." }, { status: 400 });
  }

  const values = typeof body === "object" && body !== null ? body as Record<string, unknown> : {};
  const email = typeof values.email === "string" ? values.email.trim() : "";
  const token = typeof values.token === "string" ? values.token.trim() : "";
  if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || !/^\d{8}$/.test(token)) {
    return NextResponse.json({ error: "Enter a valid email and 8-digit code." }, { status: 400 });
  }

  const supabase = await createClient();
  const { error } = await supabase.auth.verifyOtp({ email, token, type: "email" });
  if (error) {
    return NextResponse.json({ error: "That code is invalid or expired. Check it and try again." }, { status: 400 });
  }

  return NextResponse.json({ next: safeNextPath(values.next) });
}
