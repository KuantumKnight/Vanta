import {
  getOccurrences,
  localDateTimeToUtc,
  type ScheduleForExpansion,
} from "./recurrence.ts";

function dayInZone(date: Date): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date);
  const values = Object.fromEntries(
    parts.map((part) => [part.type, part.value]),
  );
  return values.year + "-" + values.month + "-" + values.day;
}

/** Materialize only a canonical schedule owned by the authenticated caller.
 * The caller supplies an owner identity obtained from Supabase Auth; the
 * schedule and generated rows are read/written using the server-only client.
 */
export async function materializeScheduleForOwner(
  admin: any,
  ownerUserId: string,
  scheduleId: string,
  now = new Date(),
): Promise<number> {
  if (!ownerUserId || !scheduleId) {
    throw new Error("An authenticated owner and schedule are required.");
  }
  const { data: schedule, error: loadError } = await admin.from(
    "habit_schedules",
  )
    .select(
      "id,task_id,user_id,dtstart,timezone,rrule,rdates,exdates,effective_from,effective_until,reminder_time,task:tasks!inner(default_duration_minutes,default_schedule_type,default_priority)",
    )
    .eq("id", scheduleId).eq("user_id", ownerUserId).maybeSingle();
  if (loadError) throw loadError;
  if (!schedule || schedule.user_id !== ownerUserId) {
    throw new Error("The habit schedule is not owned by this account.");
  }

  const from = new Date(now.getTime() - 86400000);
  const to = new Date(now.getTime() + 120 * 86400000);
  const ownedSchedule = schedule as ScheduleForExpansion & {
    task?: {
      default_duration_minutes?: number | null;
      default_schedule_type?: "HARD" | "SOFT" | "FLEXIBLE" | null;
      default_priority?: "LOW" | "NORMAL" | "HIGH" | null;
    } | null;
  };
  const generated = getOccurrences(ownedSchedule, from, to);
  const durationMinutes = ownedSchedule.task?.default_duration_minutes ?? null;
  const scheduleType = ownedSchedule.task?.default_schedule_type ?? "SOFT";
  const priority = ownedSchedule.task?.default_priority ?? "NORMAL";
  const reminderTime = schedule.reminder_time?.slice(0, 5);
  const rows = generated.map((scheduled) => {
    const reminderAt = reminderTime
      ? localDateTimeToUtc(dayInZone(scheduled), reminderTime)
      : null;
    return {
      task_id: schedule.task_id,
      schedule_id: schedule.id,
      user_id: ownerUserId,
      scheduled_for: scheduled.toISOString(),
      all_day: false,
      reminder_at: reminderAt && new Date(reminderAt) > now ? reminderAt : null,
      duration_minutes: durationMinutes,
      schedule_type: scheduleType,
      priority,
    };
  });

  for (let offset = 0; offset < rows.length; offset += 500) {
    const { error } = await admin.from("task_occurrences").upsert(
      rows.slice(offset, offset + 500),
      { onConflict: "schedule_id,scheduled_for", ignoreDuplicates: true },
    );
    if (error) throw error;
  }
  return rows.length;
}
