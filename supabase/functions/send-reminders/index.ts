import { admin, json, requireInternal } from "../_shared/admin.ts";
import { sendFcm } from "../_shared/fcm.ts";

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response(null, { status: 204 });
  const denied = requireInternal(request);
  if (denied) return denied;

  const { data: due, error } = await admin.rpc("system_claim_due_reminders");
  if (error) return json({ error: "Could not claim due reminders." }, 500);

  let delivered = 0;
  let failed = 0;
  for (const reminder of due ?? []) {
    let result: { ok: boolean; permanent: boolean; error?: string };
    try {
      result = await sendFcm({
        token: reminder.token,
        data: { type: "reminder", occurrence_id: reminder.occurrence_id },
        notification: {
          title: "SYSTEM reminder",
          body: reminder.task_title + (reminder.scheduled_for ? " is due soon." : " is due."),
        },
        priority: "HIGH",
        channelId: "system_reminders",
      });
    } catch {
      result = { ok: false, permanent: false, error: "FCM request failed." };
    }

    if (result.ok) {
      delivered++;
      await admin.rpc("system_finish_reminder", {
        p_occurrence_id: reminder.occurrence_id,
        p_device_id: reminder.device_id,
        p_error: null,
      });
    } else {
      failed++;
      if (result.permanent) {
        await admin.from("device_tokens").update({ enabled: false }).eq("id", reminder.device_id);
      }
      await admin.rpc("system_finish_reminder", {
        p_occurrence_id: reminder.occurrence_id,
        p_device_id: reminder.device_id,
        p_error: result.error ?? "FCM request failed.",
      });
    }
  }
  return json({ ok: true, delivered, failed });
});
