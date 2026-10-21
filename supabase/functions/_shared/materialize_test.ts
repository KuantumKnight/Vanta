import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import { materializeScheduleForOwner } from "./materialize.ts";

const ownerId = "00000000-0000-4000-8000-000000000001";
const otherUserId = "00000000-0000-4000-8000-000000000002";
const scheduleId = "00000000-0000-4000-8000-000000000003";
const fixedNow = new Date("2026-01-01T00:00:00.000Z");
const schedule = {
  id: scheduleId,
  task_id: "00000000-0000-4000-8000-000000000004",
  user_id: ownerId,
  dtstart: "2026-01-01T03:30:00.000Z",
  timezone: "Asia/Kolkata",
  rrule: "FREQ=DAILY;COUNT=3",
  rdates: [],
  exdates: [],
  effective_from: "2026-01-01T03:30:00.000Z",
  effective_until: null,
  reminder_time: null,
  task: {
    default_duration_minutes: 90 as number | null,
    default_schedule_type: "SOFT" as "SOFT" | "FLEXIBLE" | "HARD" | null,
    default_priority: "HIGH" as "HIGH" | "LOW" | "NORMAL" | null,
  },
};

function fakeAdmin(canonicalSchedule: typeof schedule | null) {
  const inserted: Array<Record<string, unknown>> = [];
  const admin = {
    from(table: string) {
      return {
        select() {
          return this;
        },
        eq() {
          return this;
        },
        maybeSingle: async () => ({
          data: table === "habit_schedules" ? canonicalSchedule : null,
          error: null,
        }),
        upsert: async (
          rows: Array<Record<string, unknown>>,
          options: unknown,
        ) => {
          assertEquals(table, "task_occurrences");
          assertEquals(options, {
            onConflict: "schedule_id,scheduled_for",
            ignoreDuplicates: true,
          });
          inserted.push(...rows);
          return { error: null };
        },
      };
    },
  };
  return { admin, inserted };
}

Deno.test("owner-checked service materialization creates legitimate schedule occurrences", async () => {
  const { admin, inserted } = fakeAdmin(schedule);
  const count = await materializeScheduleForOwner(
    admin,
    ownerId,
    scheduleId,
    fixedNow,
  );
  assertEquals(count, 3);
  assertEquals(inserted.length, 3);
  assertEquals(inserted.map((row) => row.user_id), [ownerId, ownerId, ownerId]);
  assertEquals(inserted.map((row) => row.duration_minutes), [90, 90, 90]);
  assertEquals(inserted.map((row) => row.schedule_type), [
    "SOFT",
    "SOFT",
    "SOFT",
  ]);
  assertEquals(inserted.map((row) => row.priority), ["HIGH", "HIGH", "HIGH"]);
});

Deno.test("service materialization keeps duration null when the task has no default", async () => {
  const { admin, inserted } = fakeAdmin({
    ...schedule,
    task: {
      ...schedule.task,
      default_duration_minutes: null,
      default_schedule_type: "FLEXIBLE",
      default_priority: "LOW",
    },
  });
  const count = await materializeScheduleForOwner(
    admin,
    ownerId,
    scheduleId,
    fixedNow,
  );
  assertEquals(count, 3);
  assertEquals(inserted.map((row) => row.duration_minutes), [null, null, null]);
  assertEquals(inserted.map((row) => row.schedule_type), [
    "FLEXIBLE",
    "FLEXIBLE",
    "FLEXIBLE",
  ]);
  assertEquals(inserted.map((row) => row.priority), ["LOW", "LOW", "LOW"]);
});

Deno.test("service materialization rejects a schedule owned by another user", async () => {
  const { admin, inserted } = fakeAdmin({ ...schedule, user_id: otherUserId });
  await assertRejects(
    () => materializeScheduleForOwner(admin, ownerId, scheduleId, fixedNow),
    Error,
    "not owned",
  );
  assertEquals(inserted.length, 0);
});

Deno.test("service materialization rejects missing cross-user schedules", async () => {
  const { admin, inserted } = fakeAdmin(null);
  await assertRejects(
    () => materializeScheduleForOwner(admin, ownerId, scheduleId, fixedNow),
    Error,
    "not owned",
  );
  assertEquals(inserted.length, 0);
});
