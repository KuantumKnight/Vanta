import {
  defaultReminderForDate,
  localDateTimeToUtc,
  parseIsoInstant,
} from "./recurrence.ts";

export function normalizeTaskReminder(
  dueDate: string | undefined,
  dueAt: string | undefined,
  reminderAt: string | undefined,
  reminderTime: string | undefined,
): string | null {
  if (reminderAt !== undefined) return parseIsoInstant(reminderAt);
  if (!dueDate) return null;
  if (reminderTime) return localDateTimeToUtc(dueDate, reminderTime);
  if (dueAt) return null;
  const dueInstant = new Date(localDateTimeToUtc(dueDate));
  return defaultReminderForDate(dueDate, dueInstant);
}
