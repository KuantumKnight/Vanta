import rrule from "npm:rrule@2.8.1";

const { RRule, RRuleSet } = rrule;

export const TIME_ZONE = "Asia/Kolkata";
const TIME_ZONE_OFFSET_MS = 5.5 * 60 * 60 * 1000;
// 120 days at the supported 15-minute minimum is 11,520 occurrences; leave
// room for up to 500 explicit inclusion dates in the same expansion window.
export const MAX_OCCURRENCES = 20000;

// rrule.js treats Date fields as floating local wall-clock time. Move stored
// Asia/Kolkata instants onto that floating timeline explicitly so expansion is
// stable when the Edge Function runs on a UTC host.
function asFloatingLocalTime(instant: Date): Date {
  return new Date(instant.getTime() + TIME_ZONE_OFFSET_MS);
}

function asUtcInstant(floatingLocalTime: Date): Date {
  return new Date(floatingLocalTime.getTime() - TIME_ZONE_OFFSET_MS);
}

export function localDateTimeToUtc(date: string, time = "00:00"): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error("Date must use YYYY-MM-DD.");
  if (!/^\d{2}:\d{2}$/.test(time)) throw new Error("Time must use HH:mm.");
  const parsed = new Date(date + "T" + time + ":00+05:30");
  if (Number.isNaN(parsed.valueOf())) throw new Error("Invalid date or time.");
  const localValue = new Date(parsed.valueOf() + 19800000).toISOString().slice(0, 16);
  if (localValue !== date + "T" + time) throw new Error("Invalid date or time.");
  return parsed.toISOString();
}

export function todayInZone(now = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit",
  }).formatToParts(now);
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return values.year + "-" + values.month + "-" + values.day;
}

export function localTimeForInstant(date: Date): string {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: TIME_ZONE, hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return values.hour + ":" + values.minute;
}

export function validateRRule(input: string): string {
  const normalized = input.trim().replace(/^RRULE:/i, "").toUpperCase();
  if (normalized.length > 2048 || /[\r\n]/.test(normalized)) throw new Error("Recurrence rule is too long or contains line breaks.");
  const fields = new Map<string, string>();
  const allowedFields = new Set([
    "FREQ", "UNTIL", "COUNT", "INTERVAL", "BYSECOND", "BYMINUTE", "BYHOUR", "BYDAY",
    "BYMONTHDAY", "BYYEARDAY", "BYWEEKNO", "BYMONTH", "BYSETPOS", "WKST",
  ]);
  for (const part of normalized.split(";")) {
    const split = part.indexOf("=");
    if (split < 1) throw new Error("Each recurrence rule field must use KEY=VALUE.");
    const key = part.slice(0, split);
    if (!allowedFields.has(key)) throw new Error("Unsupported recurrence field: " + key);
    if (fields.has(key)) throw new Error("Duplicate recurrence field: " + key);
    fields.set(key, part.slice(split + 1));
  }
  const freq = fields.get("FREQ");
  if (!freq || !["SECONDLY", "MINUTELY", "HOURLY", "DAILY", "WEEKLY", "MONTHLY", "YEARLY"].includes(freq)) {
    throw new Error("Use a valid RFC 5545 FREQ value.");
  }
  const rawInterval = fields.get("INTERVAL") ?? "1";
  const interval = Number(rawInterval);
  if (!/^\d+$/.test(rawInterval) || !Number.isInteger(interval) || interval < 1) throw new Error("INTERVAL must be a positive integer.");
  if (fields.has("COUNT") && (!/^\d+$/.test(fields.get("COUNT")!) || Number(fields.get("COUNT")) < 1)) {
    throw new Error("COUNT must be a positive integer.");
  }
  if (fields.has("COUNT") && fields.has("UNTIL")) throw new Error("Use COUNT or UNTIL, not both.");
  const numericRanges: Record<string, [number, number, boolean]> = {
    BYSECOND: [0, 60, false],
    BYMINUTE: [0, 59, false],
    BYHOUR: [0, 23, false],
    BYMONTHDAY: [-31, 31, true],
    BYYEARDAY: [-366, 366, true],
    BYWEEKNO: [-53, 53, true],
    BYMONTH: [1, 12, false],
    BYSETPOS: [-366, 366, true],
  };
  const numberLists = new Map<string, number[]>();
  for (const [key, [minimum, maximum, disallowZero]] of Object.entries(numericRanges)) {
    const raw = fields.get(key);
    if (raw === undefined) continue;
    const values = raw.split(",").map((value) => {
      if (!/^-?\d+$/.test(value)) throw new Error(`${key} values must be integers.`);
      return Number(value);
    });
    if (new Set(values).size !== values.length || values.some((value) => value < minimum || value > maximum || (disallowZero && value === 0))) {
      throw new Error(`${key} contains a duplicate or out-of-range value.`);
    }
    numberLists.set(key, values);
  }
  const byDay = fields.get("BYDAY");
  if (byDay) {
    const days = byDay.split(",");
    if (new Set(days).size !== days.length || days.some((day) => !/^(?:[+-]?(?:[1-9]|[1-4]\d|5[0-3]))?(?:MO|TU|WE|TH|FR|SA|SU)$/.test(day))) {
      throw new Error("BYDAY contains an invalid or duplicate weekday.");
    }
    const hasOrdinal = days.some((day) => /^[-+]?\d/.test(day));
    if (hasOrdinal && !["MONTHLY", "YEARLY"].includes(freq)) throw new Error("BYDAY ordinals are only valid for monthly or yearly rules.");
    if (hasOrdinal && freq === "YEARLY" && fields.has("BYWEEKNO")) throw new Error("BYDAY ordinals cannot be combined with BYWEEKNO.");
  }
  if (fields.has("BYWEEKNO") && freq !== "YEARLY") throw new Error("BYWEEKNO is only valid for yearly rules.");
  if (fields.has("BYYEARDAY") && ["DAILY", "WEEKLY", "MONTHLY"].includes(freq)) throw new Error("BYYEARDAY is not valid for this frequency.");
  if (fields.has("BYMONTHDAY") && freq === "WEEKLY") throw new Error("BYMONTHDAY is not valid for weekly rules.");
  if (fields.has("BYSETPOS") && !["BYSECOND", "BYMINUTE", "BYHOUR", "BYDAY", "BYMONTHDAY", "BYYEARDAY", "BYWEEKNO", "BYMONTH"].some((key) => fields.has(key))) {
    throw new Error("BYSETPOS must be used with another BY field.");
  }
  const weekStart = fields.get("WKST");
  if (weekStart && !/^(MO|TU|WE|TH|FR|SA|SU)$/.test(weekStart)) throw new Error("WKST must be a weekday.");
  const secondsPerOccurrence = freq === "SECONDLY" ? interval
    : freq === "MINUTELY" ? interval * 60
    : freq === "HOURLY" ? interval * 3600
    : Number.POSITIVE_INFINITY;
  if (secondsPerOccurrence < 900) throw new Error("Recurrence intervals shorter than 15 minutes are not supported.");
  if (freq === "SECONDLY" || freq === "MINUTELY") {
    if ((numberLists.get("BYSECOND")?.length ?? 1) > 1) {
      throw new Error("BYSECOND cannot create occurrences more often than once every 15 minutes.");
    }
  } else {
    const byHours = freq === "HOURLY" ? [0] : numberLists.get("BYHOUR") ?? [0];
    const byMinutes = numberLists.get("BYMINUTE") ?? [0];
    const bySeconds = numberLists.get("BYSECOND") ?? [0];
    const partsPerPeriod = freq === "HOURLY" ? byMinutes.length * bySeconds.length : byHours.length * byMinutes.length * bySeconds.length;
    if (partsPerPeriod > 10000) throw new Error("The recurrence has too many times in a day.");
    const times = (freq === "HOURLY"
      ? byMinutes.flatMap((minute) => bySeconds.map((second) => minute * 60 + second))
      : byHours.flatMap((hour) => byMinutes.flatMap((minute) => bySeconds.map((second) => hour * 3600 + minute * 60 + second))))
      .sort((a, b) => a - b);
    const periodSeconds = freq === "HOURLY" ? 3600 : 86400;
    for (let index = 1; index < times.length; index++) {
      if (times[index] - times[index - 1] < 900) {
        throw new Error("BYHOUR, BYMINUTE, and BYSECOND cannot create occurrences more often than once every 15 minutes.");
      }
    }
    if (times.length > 1 && times[0] + periodSeconds - times[times.length - 1] < 900) {
      throw new Error("BYHOUR, BYMINUTE, and BYSECOND cannot create occurrences more often than once every 15 minutes.");
    }
  }
  try {
    const options = RRule.parseString(normalized);
    if (!options.freq) throw new Error("Invalid recurrence rule.");
    new RRule(options);
  } catch {
    throw new Error("The recurrence rule is not valid RFC 5545 syntax.");
  }
  return normalized;
}

export interface ScheduleForExpansion {
  id: string;
  task_id: string;
  user_id: string;
  dtstart: string;
  timezone: string;
  rrule: string;
  rdates: string[];
  exdates: string[];
  effective_from: string;
  effective_until: string | null;
  reminder_time?: string | null;
}

export function getOccurrences(schedule: ScheduleForExpansion, from: Date, to: Date): Date[] {
  if (schedule.timezone !== TIME_ZONE) throw new Error("Only Asia/Kolkata schedules are supported.");
  const validatedRule = validateRRule(schedule.rrule);
  const parsedOptions = RRule.parseString(validatedRule);
  const options = {
    ...parsedOptions,
    dtstart: asFloatingLocalTime(new Date(schedule.dtstart)),
    ...(parsedOptions.until ? { until: asFloatingLocalTime(parsedOptions.until) } : {}),
  };
  const set = new RRuleSet();
  set.rrule(new RRule(options));
  for (const date of schedule.rdates ?? []) set.rdate(asFloatingLocalTime(new Date(date)));
  for (const date of schedule.exdates ?? []) set.exdate(asFloatingLocalTime(new Date(date)));
  const floatingFrom = asFloatingLocalTime(from);
  const floatingTo = asFloatingLocalTime(to);
  const effectiveFrom = asFloatingLocalTime(new Date(schedule.effective_from));
  const effectiveUntil = schedule.effective_until ? asFloatingLocalTime(new Date(schedule.effective_until)) : floatingTo;
  const lowerBound = floatingFrom < effectiveFrom ? effectiveFrom : floatingFrom;
  const upperBound = floatingTo > effectiveUntil ? effectiveUntil : floatingTo;
  if (upperBound < lowerBound) return [];
  const occurrences: Date[] = [];
  set.between(lowerBound, upperBound, true, (date) => {
    occurrences.push(asUtcInstant(date));
    return occurrences.length < MAX_OCCURRENCES;
  });
  if (occurrences.length >= MAX_OCCURRENCES) throw new Error("This recurrence creates too many occurrences for the requested window.");
  occurrences.sort((left, right) => left.getTime() - right.getTime());
  return occurrences;
}

export function defaultReminderForDate(date: string, dueAt: Date, now = new Date()): string | null {
  if (dueAt <= now) return null;
  const previousDay = new Date(date + "T00:00:00Z");
  previousDay.setUTCDate(previousDay.getUTCDate() - 1);
  const reminder = new Date(localDateTimeToUtc(previousDay.toISOString().slice(0, 10), "09:00"));
  if (reminder >= dueAt) return null;
  return (reminder < now ? now : reminder).toISOString();
}
