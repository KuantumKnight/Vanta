import { createClient } from "npm:@supabase/supabase-js@2.117.0";

export const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
const secretKeys = (() => {
  try {
    return JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}") as Record<string, unknown>;
  } catch {
    return {};
  }
})();
export const serviceKey = (typeof secretKeys.default === "string" ? secretKeys.default : undefined)
  ?? Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")
  ?? "";
if (!supabaseUrl || !serviceKey) throw new Error("Supabase server credentials are not configured.");
export const internalSecret = Deno.env.get("INTERNAL_FUNCTION_SECRET")!;

export const admin = createClient(supabaseUrl, serviceKey, {
  auth: { persistSession: false, autoRefreshToken: false },
});

export function requireInternal(request: Request): Response | null {
  const provided = request.headers.get("x-system-internal-secret") ?? "";
  if (!internalSecret || provided.length !== internalSecret.length) {
    return new Response("Unauthorized", { status: 401 });
  }
  let different = 0;
  for (let i = 0; i < provided.length; i++) different |= provided.charCodeAt(i) ^ internalSecret.charCodeAt(i);
  return different === 0 ? null : new Response("Unauthorized", { status: 401 });
}

export function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}
