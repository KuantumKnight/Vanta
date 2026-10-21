import { assertEquals, assertThrows } from "jsr:@std/assert@1.0.14";
import {
  type AuthoritativeFlexibleTask,
  type DayPlanRequest,
  dayPlanRequestSchema,
  toModelContext,
  validateDayPlan,
} from "./day_plan.ts";

const taskId = "a4d0cf4e-40d0-4c29-a31f-4ade74904134";
const secondTaskId = "6dccd433-d291-48be-8586-a11394f27e7b";
const unknownId = "89064b49-3b19-43e8-ae4f-4b57a1e2a657";
const start = "2026-10-21T10:00:00Z";

function request(
  tasks: DayPlanRequest["flexible_tasks"] = [flexibleTask(taskId)],
): DayPlanRequest {
  return dayPlanRequestSchema.parse({
    date: "2026-10-21",
    timezone: "Asia/Kolkata",
    planning_start: "2026-10-21T08:00:00Z",
    planning_end: "2026-10-21T15:00:00Z",
    busy_blocks: [],
    flexible_tasks: tasks,
    completed_occurrence_ids: [],
  });
}

function flexibleTask(
  id: string,
  duration = 60,
  deadline: string | null = null,
) {
  return {
    occurrence_id: id,
    title: id === taskId ? "TOC Revision" : "Project Work",
    duration_minutes: duration,
    priority: "NORMAL" as const,
    deadline_at: deadline,
    earliest_start: null,
    latest_end: null,
    preferred_start: null,
  };
}

function canonicalTask(
  id: string,
  duration = 60,
  deadline: string | null = null,
): AuthoritativeFlexibleTask {
  return { ...flexibleTask(id, duration, deadline) };
}

function placement(id = taskId, begin = start, end = "2026-10-21T11:00:00Z") {
  return { occurrence_id: id, start: begin, end, reason: "Fits a free hour" };
}

Deno.test("day plan accepts a feasible placement", () => {
  const input = request();
  const result = validateDayPlan(input, [canonicalTask(taskId)], {
    placements: [placement()],
    unscheduled: [],
  });
  assertEquals(result.placements, [placement()]);
  assertEquals(result.unscheduled, []);
});

Deno.test("day plan rejects placement overlapping a hard or calendar busy block", () => {
  const input = dayPlanRequestSchema.parse({
    ...request(),
    busy_blocks: [{
      title: "Operating Systems Class",
      start: "2026-10-21T10:30:00Z",
      end: "2026-10-21T11:30:00Z",
    }],
  });
  const result = validateDayPlan(input, [canonicalTask(taskId)], {
    placements: [placement()],
    unscheduled: [],
  });
  assertEquals(result.placements, []);
  assertEquals(
    result.unscheduled[0].reason,
    "The proposed time overlaps a scheduled commitment.",
  );
});

Deno.test("day plan rejects deadline and duration violations", () => {
  const input = request([flexibleTask(taskId, 90, "2026-10-21T10:30:00Z")]);
  const result = validateDayPlan(input, [
    canonicalTask(taskId, 90, "2026-10-21T10:30:00Z"),
  ], {
    placements: [placement()],
    unscheduled: [],
  });
  assertEquals(result.placements, []);
  assertEquals(
    result.unscheduled[0].reason,
    "The proposed duration does not match the task.",
  );
});

Deno.test("day plan rejects duplicate task outputs", () => {
  const result = validateDayPlan(request(), [canonicalTask(taskId)], {
    placements: [
      placement(),
      placement(taskId, "2026-10-21T12:00:00Z", "2026-10-21T13:00:00Z"),
    ],
    unscheduled: [],
  });
  assertEquals(result.placements, []);
  assertEquals(
    result.unscheduled[0].reason,
    "The assistant returned this task more than once.",
  );
});

Deno.test("day plan rejects the proposal when it contains an unknown occurrence ID", () => {
  const input = request([flexibleTask(taskId), flexibleTask(secondTaskId)]);
  const result = validateDayPlan(input, [
    canonicalTask(taskId),
    canonicalTask(secondTaskId),
  ], {
    placements: [
      placement(unknownId),
      placement(taskId),
      placement(secondTaskId, "2026-10-21T12:00:00Z", "2026-10-21T13:00:00Z"),
    ],
    unscheduled: [],
  });
  assertEquals(result.unscheduled.map((item) => item.occurrence_id), [
    taskId,
    secondTaskId,
  ]);
  assertEquals(result.rejectedCount, 2);
});

Deno.test("day plan rejects overlapping proposed tasks together", () => {
  const input = request([flexibleTask(taskId), flexibleTask(secondTaskId)]);
  const result = validateDayPlan(input, [
    canonicalTask(taskId),
    canonicalTask(secondTaskId),
  ], {
    placements: [
      placement(taskId),
      placement(secondTaskId, "2026-10-21T10:30:00Z", "2026-10-21T11:30:00Z"),
    ],
    unscheduled: [],
  });
  assertEquals(result.placements, []);
  assertEquals(result.unscheduled.length, 2);
  assertEquals(
    result.unscheduled[0].reason,
    "The proposal overlaps another task in this plan.",
  );
});

Deno.test("day plan keeps valid placements when a sibling fails validation", () => {
  const input = request([
    flexibleTask(taskId),
    flexibleTask(secondTaskId, 90, "2026-10-21T12:30:00Z"),
  ]);
  const result = validateDayPlan(input, [
    canonicalTask(taskId),
    canonicalTask(secondTaskId, 90, "2026-10-21T12:30:00Z"),
  ], {
    placements: [
      placement(taskId),
      placement(secondTaskId, "2026-10-21T12:00:00Z", "2026-10-21T13:30:00Z"),
    ],
    unscheduled: [],
  });
  assertEquals(result.placements.map((item) => item.occurrence_id), [taskId]);
  assertEquals(result.unscheduled.map((item) => item.occurrence_id), [
    secondTaskId,
  ]);
  assertEquals(
    result.unscheduled[0].reason,
    "The proposed time is after this task's deadline.",
  );
});

Deno.test("empty day stays empty without calling the model", () => {
  const input = request([]);
  const result = validateDayPlan(input, [], {
    placements: [],
    unscheduled: [],
  });
  assertEquals(result.placements, []);
  assertEquals(result.unscheduled, []);
});

Deno.test("no feasible slot can be explained as unscheduled", () => {
  const input = request();
  const result = validateDayPlan(input, [canonicalTask(taskId)], {
    placements: [],
    unscheduled: [{
      occurrence_id: taskId,
      reason: "No 60 minute gap before the deadline.",
    }],
  });
  assertEquals(result.placements, []);
  assertEquals(
    result.unscheduled[0].reason,
    "No 60 minute gap before the deadline.",
  );
});

Deno.test("malformed proposal schema is rejected", () => {
  const input = request();
  assertThrows(() =>
    validateDayPlan(input, [canonicalTask(taskId)], {
      placements: [{
        occurrence_id: taskId,
        start: "tomorrow morning",
        end: "2026-10-21T11:00:00Z",
        reason: "ok",
      }],
      unscheduled: [],
    })
  );
});

Deno.test("impossible calendar dates are rejected", () => {
  const input = request();
  assertThrows(() =>
    validateDayPlan(input, [canonicalTask(taskId)], {
      placements: [{
        occurrence_id: taskId,
        start: "2026-02-30T10:00:00Z",
        end: "2026-02-30T11:00:00Z",
        reason: "ok",
      }],
      unscheduled: [],
    })
  );
  assertThrows(() =>
    dayPlanRequestSchema.parse({ ...input, date: "2026-02-30" })
  );
});

Deno.test("model context excludes completed IDs and calendar metadata", () => {
  const input = dayPlanRequestSchema.parse({
    ...request(),
    completed_occurrence_ids: [secondTaskId],
    busy_blocks: [{
      title: "busy: Operating Systems",
      start: start,
      end: "2026-10-21T11:00:00Z",
    }],
  });
  const context = toModelContext(input, [canonicalTask(taskId)]);
  const serialized = JSON.stringify(context);
  assertEquals(serialized.includes(secondTaskId), false);
  assertEquals(serialized.includes("calendar_id"), false);
  assertEquals(serialized.includes("attendees"), false);
});
