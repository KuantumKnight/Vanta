import { admin, json, requireInternal } from "../_shared/admin.ts";
import { getOccurrences, localDateTimeToUtc, type ScheduleForExpansion } from "../_shared/recurrence.ts";

const BATCH_SIZE = 500;
const SCHEDULE_PAGE_SIZE = 1000;
const LOOKBACK_DAYS = 30;
const LOOKAHEAD_DAYS = 90;

function dayInZone(date: Date): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata", year: "numeric", month: "2-digit", day: "2-digit",
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return values.year + "-" + values.month + "-" + values.day;
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response(null, { status: 204 });
  const denied = requireInternal(request);
  if (denied) return denied;
  const now = new Date();
  const from = new Date(now.getTime() - LOOKBACK_DAYS * 86400000);
  const to = new Date(now.getTime() + LOOKAHEAD_DAYS * 86400000);
  const schedules: ScheduleForExpansion[] = [];
  for (let offset = 0; ; offset += SCHEDULE_PAGE_SIZE) {
    const { data, error } = await admin.from("habit_schedules")
      .select("id,task_id,user_id,dtstart,timezone,rrule,rdates,exdates,effective_from,effective_until,reminder_time")
      .is("effective_until", null)
      .order("id", { ascending: true })
      .range(offset, offset + SCHEDULE_PAGE_SIZE - 1);
    if (error) return json({ error: "Could not load schedules." }, 500);
    schedules.push(...((data ?? []) as ScheduleForExpansion[]));
    if ((data ?? []).length < SCHEDULE_PAGE_SIZE) break;
  }

  let materialized = 0;
  let skipped = 0;
  for (const schedule of schedules) {
    try {
      const occurrences = getOccurrences(schedule, from, to);
      const rows = occurrences.map((scheduled) => {
        const reminderTime = schedule.reminder_time?.slice(0, 5);
        const reminderAt = reminderTime ? localDateTimeToUtc(dayInZone(scheduled), reminderTime) : null;
        return {
          task_id: schedule.task_id,
          schedule_id: schedule.id,
          user_id: schedule.user_id,
          scheduled_for: scheduled.toISOString(),
          all_day: false,
          reminder_at: reminderAt && new Date(reminderAt) > now ? reminderAt : null,
        };
      });
      for (let offset = 0; offset < rows.length; offset += BATCH_SIZE) {
        const { error: insertError } = await admin.from("task_occurrences")
          .upsert(rows.slice(offset, offset + BATCH_SIZE), {
            onConflict: "schedule_id,scheduled_for",
            ignoreDuplicates: true,
          });
        if (insertError) throw insertError;
      }
      materialized += rows.length;
    } catch (error) {
      skipped++;
      console.error("Schedule expansion failed", schedule.id, error);
    }
  }
  return json({ ok: true, materialized, failedSchedules: skipped });
});
