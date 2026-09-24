import { NextResponse, type NextRequest } from "next/server";
import { createClient } from "@/lib/supabase/server";

export async function POST(request: NextRequest) {
  const origin = request.headers.get("origin");
  if (origin !== new URL(request.url).origin) {
    return NextResponse.json({ error: "Invalid request origin." }, { status: 403 });
  }

  const form = await request.formData();
  const authorizationId = form.get("authorization_id");
  const decision = form.get("decision");
  if (typeof authorizationId !== "string" || authorizationId.length > 512 ||
      (decision !== "approve" && decision !== "deny")) {
    return NextResponse.json({ error: "Invalid authorization decision." }, { status: 400 });
  }

  const supabase = await createClient();
  const { data: claimsData, error: claimsError } = await supabase.auth.getClaims();
  if (claimsError || !claimsData?.claims) {
    const continueTo = "/oauth/consent?authorization_id=" + encodeURIComponent(authorizationId);
    return NextResponse.redirect(new URL("/login?redirect=" + encodeURIComponent(continueTo), request.url), 303);
  }

  const result = decision === "approve"
    ? await supabase.auth.oauth.approveAuthorization(authorizationId)
    : await supabase.auth.oauth.denyAuthorization(authorizationId);
  if (result.error || !result.data?.redirect_url) {
    return NextResponse.json({ error: result.error?.message ?? "Authorization failed." }, { status: 400 });
  }
  return NextResponse.redirect(result.data.redirect_url, 303);
}
