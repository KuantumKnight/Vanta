import { assertEquals, assertThrows } from "jsr:@std/assert@1";
import {
  defaultReminderForDate,
  getOccurrences,
  localDateTimeToUtc,
  nextLocalDate,
  parseIsoInstant,
  type ScheduleForExpansion,
  todayInZone,
  validateRRule,
} from "./recurrence.ts";

Deno.test("Asia/Kolkata dates reject impossible calendar values", () => {
  assertEquals(
    localDateTimeToUtc("2026-09-24", "09:15"),
    "2026-09-24T03:45:00.000Z",
  );
  assertThrows(() => localDateTimeToUtc("2026-02-30", "09:00"), Error);
  assertThrows(() => localDateTimeToUtc("2026-09-24", "24:00"), Error);
  assertEquals(todayInZone(new Date("2026-09-23T20:00:00.000Z")), "2026-09-24");
});

Deno.test("date range ends validate calendar dates and explicit reminder instants", () => {
  assertEquals(nextLocalDate("2026-09-24"), "2026-09-25");
  assertThrows(() => nextLocalDate("2026-02-30"), Error);
  assertEquals(
    parseIsoInstant("2026-09-24T09:15:00+05:30"),
    "2026-09-24T03:45:00.000Z",
  );
  assertThrows(() => parseIsoInstant("2026-09-24"), Error);
  assertThrows(() => parseIsoInstant("2026-02-30T09:15:00Z"), Error);
});

Deno.test("date-only reminders use yesterday at 9 AM and catch up once", () => {
  const due = new Date(localDateTimeToUtc("2026-09-25"));
  const initial = new Date("2026-09-23T00:00:00.000Z");
  assertEquals(
    defaultReminderForDate("2026-09-25", due, initial),
    "2026-09-24T03:30:00.000Z",
  );
  const lateNow = new Date("2026-09-24T05:00:00.000Z");
  assertEquals(
    defaultReminderForDate("2026-09-25", due, lateNow),
    lateNow.toISOString(),
  );
  assertEquals(
    defaultReminderForDate(
      "2026-09-23",
      new Date("2026-09-22T18:30:00.000Z"),
      initial,
    ),
    null,
  );
});

Deno.test("recurrence intervals and BY parts cannot exceed one occurrence per 15 minutes", () => {
  assertEquals(
    validateRRule("FREQ=MINUTELY;INTERVAL=15"),
    "FREQ=MINUTELY;INTERVAL=15",
  );
  assertEquals(
    validateRRule("FREQ=HOURLY;BYMINUTE=0,15,30,45"),
    "FREQ=HOURLY;BYMINUTE=0,15,30,45",
  );
  assertEquals(
    validateRRule("FREQ=DAILY;BYHOUR=8,20;BYMINUTE=0"),
    "FREQ=DAILY;BYHOUR=8,20;BYMINUTE=0",
  );
  assertThrows(() => validateRRule("FREQ=SECONDLY"), Error);
  assertThrows(() => validateRRule("FREQ=MINUTELY;INTERVAL=14"), Error);
  assertThrows(() => validateRRule("FREQ=HOURLY;BYMINUTE=0,10"), Error);
  assertThrows(() => validateRRule("FREQ=DAILY;BYSECOND=0,30"), Error);
  assertThrows(() => validateRRule("FREQ=DAILY;BYHOUR=8,8;BYMINUTE=0"), Error);
  assertThrows(
    () => validateRRule("FREQ=DAILY;BYMINUTE=0,10;BYSECOND=0"),
    Error,
  );
  assertThrows(() => validateRRule("FREQ=WEEKLY;BYDAY=1MO"), Error);
});

Deno.test("the minimum supported cadence fits the longest MCP expansion window", () => {
  const start = localDateTimeToUtc("2026-01-01", "00:00");
  const schedule: ScheduleForExpansion = {
    id: "dense-schedule",
    task_id: "dense-task",
    user_id: "user-1",
    dtstart: start,
    timezone: "Asia/Kolkata",
    rrule: "FREQ=MINUTELY;INTERVAL=15",
    rdates: [],
    exdates: [],
    effective_from: start,
    effective_until: null,
  };
  const from = new Date(start);
  const to = new Date(from.getTime() + 121 * 86400000);
  assertEquals(getOccurrences(schedule, from, to).length, 11617);
});

Deno.test("schedule expansion applies timezone, inclusion dates, and exceptions idempotently", () => {
  const schedule: ScheduleForExpansion = {
    id: "schedule-1",
    task_id: "task-1",
    user_id: "user-1",
    dtstart: localDateTimeToUtc("2026-09-23", "08:00"),
    timezone: "Asia/Kolkata",
    rrule: "FREQ=DAILY;COUNT=3",
    rdates: [localDateTimeToUtc("2026-09-26", "08:00")],
    exdates: [localDateTimeToUtc("2026-09-24", "08:00")],
    effective_from: localDateTimeToUtc("2026-09-23"),
    effective_until: null,
  };
  const from = new Date(localDateTimeToUtc("2026-09-23"));
  const to = new Date(localDateTimeToUtc("2026-09-28"));
  const expanded = getOccurrences(schedule, from, to).map((date) =>
    date.toISOString()
  );
  assertEquals(expanded, [
    localDateTimeToUtc("2026-09-23", "08:00"),
    localDateTimeToUtc("2026-09-25", "08:00"),
    localDateTimeToUtc("2026-09-26", "08:00"),
  ]);
  assertEquals(
    getOccurrences(schedule, from, to).map((date) => date.toISOString()),
    expanded,
  );
});
