import { json, requireInternal } from "../_shared/admin.ts";
import { pushRefreshToUser } from "../_shared/fcm.ts";

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response(null, { status: 204 });
  const denied = requireInternal(request);
  if (denied) return denied;
  try {
    const payload = await request.json();
    const userId = payload?.record?.user_id ?? payload?.old_record?.user_id;
    if (typeof userId !== "string") return json({ error: "Webhook payload has no owner." }, 400);
    await pushRefreshToUser(userId);
    return json({ ok: true });
  } catch {
    return json({ error: "Push refresh failed." }, 500);
  }
});
