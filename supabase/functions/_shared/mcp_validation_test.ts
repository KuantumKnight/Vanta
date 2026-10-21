import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import {
  completeTaskInputSchema,
  createTaskInputSchema,
} from "./mcp_validation.ts";
import { normalizeTaskReminder } from "./reminders.ts";

const operationId = "00000000-0000-4000-8000-000000000001";

Deno.test("create_task rejects reminder_time without a due date", async () => {
  await assertRejects(
    async () =>
      createTaskInputSchema.parse({
        operation_id: operationId,
        title: "Call",
        reminder_time: "09:00",
      }),
    Error,
    "reminder_time requires due_date",
  );
});

Deno.test("create_task accepts reminder_time with a due date", () => {
  const parsed = createTaskInputSchema.parse({
    operation_id: operationId,
    title: "Call",
    due_date: "2026-10-22",
    reminder_time: "08:15",
  });
  assertEquals(parsed.reminder_time, "08:15");
  assertEquals(
    normalizeTaskReminder(
      parsed.due_date,
      undefined,
      undefined,
      parsed.reminder_time,
    ),
    "2026-10-22T02:45:00.000Z",
  );
});

Deno.test("create_task keeps duration optional for existing clients", () => {
  const parsed = createTaskInputSchema.parse({
    operation_id: operationId,
    title: "Study TOC",
    due_date: "2026-10-22",
    due_time: "18:00",
  });
  assertEquals(parsed.duration_minutes, undefined);
});

Deno.test("create_task accepts duration boundaries and explicit duration", () => {
  const minimum = createTaskInputSchema.parse({
    operation_id: operationId,
    title: "Quick review",
    duration_minutes: 5,
  });
  const maximum = createTaskInputSchema.parse({
    operation_id: operationId,
    title: "Long task",
    duration_minutes: 1440,
  });
  const timed = createTaskInputSchema.parse({
    operation_id: operationId,
    title: "Study TOC",
    due_date: "2026-10-22",
    due_time: "18:00",
    duration_minutes: 90,
  });
  assertEquals(minimum.duration_minutes, 5);
  assertEquals(maximum.duration_minutes, 1440);
  assertEquals(timed.duration_minutes, 90);
});

Deno.test("create_task accepts hard, soft, flexible and priority scheduling fields", () => {
  const parsed = createTaskInputSchema.parse({
    operation_id: operationId,
    title: "Study for exam",
    schedule_type: "FLEXIBLE",
    priority: "HIGH",
    deadline_at: "2026-10-23T18:00:00+05:30",
    earliest_start: "2026-10-22T09:00:00+05:30",
    latest_end: "2026-10-23T17:00:00+05:30",
    preferred_start: "2026-10-22T14:00:00+05:30",
  });
  assertEquals(parsed.schedule_type, "FLEXIBLE");
  assertEquals(parsed.priority, "HIGH");
  assertEquals(parsed.deadline_at, "2026-10-23T18:00:00+05:30");
});

Deno.test("create_task rejects invalid schedule semantics and inverted bounds", async () => {
  for (
    const invalid of [
      { schedule_type: "FLOATING" },
      { priority: "URGENT" },
      {
        earliest_start: "2026-10-23T10:00:00Z",
        latest_end: "2026-10-22T10:00:00Z",
      },
      {
        earliest_start: "2026-10-23T10:00:00Z",
        deadline_at: "2026-10-22T10:00:00Z",
      },
      { schedule_type: "FLEXIBLE", due_date: "2026-10-22" },
      { schedule_type: "HARD" },
    ]
  ) {
    await assertRejects(
      async () =>
        createTaskInputSchema.parse({
          operation_id: operationId,
          title: "Study",
          ...invalid,
        }),
      Error,
    );
  }
});

Deno.test("create_task can carry a deadline without a due date or start time", () => {
  const parsed = createTaskInputSchema.parse({
    operation_id: operationId,
    title: "Submit the report",
    deadline_at: "2026-10-22T18:00:00Z",
  });
  assertEquals(parsed.due_date, undefined);
  assertEquals(parsed.due_time, undefined);
  assertEquals(parsed.deadline_at, "2026-10-22T18:00:00Z");
});

Deno.test("create_task rejects durations outside the supported range", async () => {
  for (const duration_minutes of [4, 1441, 5.5]) {
    await assertRejects(
      async () =>
        createTaskInputSchema.parse({
          operation_id: operationId,
          title: "Study",
          duration_minutes,
        }),
      Error,
    );
  }
});

Deno.test("explicit reminder_at is valid without a due date", () => {
  const parsed = createTaskInputSchema.parse({
    operation_id: operationId,
    title: "Call",
    reminder_at: "2026-10-22T08:15:00+05:30",
  });
  assertEquals(
    normalizeTaskReminder(
      parsed.due_date,
      undefined,
      parsed.reminder_at,
      undefined,
    ),
    "2026-10-22T02:45:00.000Z",
  );
});

Deno.test("complete_task requires one exact occurrence ID instead of a text match", async () => {
  await assertRejects(
    async () => completeTaskInputSchema.parse({ operation_id: operationId }),
    Error,
    "occurrence_id",
  );
});
