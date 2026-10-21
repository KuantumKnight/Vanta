import { z } from "npm:zod@4.3.6";

const timestamp = z.string().min(20).max(40).refine(
  isTimestamp,
  "Expected an ISO 8601 instant with a zone offset.",
);

function isDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return parsed.getUTCFullYear() === year &&
    parsed.getUTCMonth() === month - 1 && parsed.getUTCDate() === day;
}

function isTimestamp(value: string): boolean {
  const match =
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|([+-])(\d{2}):(\d{2}))$/
      .exec(value);
  if (!match || !isDate(`${match[1]}-${match[2]}-${match[3]}`)) return false;
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  const offsetHour = match[8] ? Number(match[9]) : 0;
  const offsetMinute = match[8] ? Number(match[10]) : 0;
  return hour <= 23 && minute <= 59 && second <= 59 &&
    offsetHour <= 23 && offsetMinute <= 59 &&
    Number.isFinite(Date.parse(value));
}

function isValidTimeZone(value: string): boolean {
  try {
    new Intl.DateTimeFormat("en", { timeZone: value });
    return true;
  } catch {
    return false;
  }
}

export const dayPlanRequestSchema = z.object({
  date: z.string().refine(isDate, "Expected a valid ISO calendar date."),
  timezone: z.string().min(1).max(64).refine(
    isValidTimeZone,
    "Unknown time zone.",
  ),
  planning_start: timestamp,
  planning_end: timestamp,
  busy_blocks: z.array(
    z.object({
      title: z.string().trim().min(1).max(160),
      start: timestamp,
      end: timestamp,
    }).strict(),
  ).max(120),
  flexible_tasks: z.array(
    z.object({
      occurrence_id: z.string().uuid(),
      title: z.string().trim().min(1).max(200),
      duration_minutes: z.number().int().min(5).max(1440),
      priority: z.enum(["LOW", "NORMAL", "HIGH"]),
      deadline_at: timestamp.nullable(),
      earliest_start: timestamp.nullable(),
      latest_end: timestamp.nullable(),
      preferred_start: timestamp.nullable(),
    }).strict(),
  ).max(40),
  completed_occurrence_ids: z.array(z.string().uuid()).max(200),
}).strict().superRefine((input, ctx) => {
  if (Date.parse(input.planning_end) <= Date.parse(input.planning_start)) {
    ctx.addIssue({
      code: "custom",
      path: ["planning_end"],
      message: "Planning end must follow planning start.",
    });
  }
  const taskIds = input.flexible_tasks.map((task) => task.occurrence_id);
  if (new Set(taskIds).size !== taskIds.length) {
    ctx.addIssue({
      code: "custom",
      path: ["flexible_tasks"],
      message: "Task IDs must be unique.",
    });
  }
  const completed = new Set(input.completed_occurrence_ids);
  if (taskIds.some((id) => completed.has(id))) {
    ctx.addIssue({
      code: "custom",
      path: ["flexible_tasks"],
      message: "Completed tasks cannot be planned.",
    });
  }
  for (const [index, block] of input.busy_blocks.entries()) {
    if (Date.parse(block.end) <= Date.parse(block.start)) {
      ctx.addIssue({
        code: "custom",
        path: ["busy_blocks", index, "end"],
        message: "Busy block end must follow start.",
      });
    }
  }
});

export type DayPlanRequest = z.infer<typeof dayPlanRequestSchema>;
export type PlanPriority = "LOW" | "NORMAL" | "HIGH";

export interface AuthoritativeFlexibleTask {
  occurrence_id: string;
  title: string;
  duration_minutes: number;
  priority: PlanPriority;
  deadline_at: string | null;
  earliest_start: string | null;
  latest_end: string | null;
  preferred_start: string | null;
}

export interface ProposedPlacement {
  occurrence_id: string;
  start: string;
  end: string;
  reason: string;
}

export interface ProposedUnscheduled {
  occurrence_id: string;
  reason: string;
}

export interface PlanProposal {
  placements: ProposedPlacement[];
  unscheduled: ProposedUnscheduled[];
}

const planProposalSchema = z.object({
  placements: z.array(
    z.object({
      occurrence_id: z.string().uuid(),
      start: timestamp,
      end: timestamp,
      reason: z.string().trim().min(1).max(240),
    }).strict(),
  ).max(40),
  unscheduled: z.array(
    z.object({
      occurrence_id: z.string().uuid(),
      reason: z.string().trim().min(1).max(240),
    }).strict(),
  ).max(40),
}).strict();

export const planProposalJsonSchema = {
  type: "object",
  additionalProperties: false,
  properties: {
    placements: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        properties: {
          occurrence_id: { type: "string" },
          start: { type: "string" },
          end: { type: "string" },
          reason: { type: "string" },
        },
        required: ["occurrence_id", "start", "end", "reason"],
      },
    },
    unscheduled: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        properties: {
          occurrence_id: { type: "string" },
          reason: { type: "string" },
        },
        required: ["occurrence_id", "reason"],
      },
    },
  },
  required: ["placements", "unscheduled"],
} as const;

export interface ValidatedPlanResult {
  placements: ProposedPlacement[];
  unscheduled: ProposedUnscheduled[];
  rejectedCount: number;
}

function overlaps(
  aStart: number,
  aEnd: number,
  bStart: number,
  bEnd: number,
): boolean {
  return aStart < bEnd && bStart < aEnd;
}

function cleanReason(value: string): string {
  return value.replace(/[\u0000-\u001f\u007f]/g, " ").replace(/\s+/g, " ")
    .trim().slice(0, 240);
}

/**
 * Validates model output against owner-scoped task rows and the bounded plan context.
 * Invalid placements are converted to unscheduled explanations; no write is performed.
 */
export function validateDayPlan(
  request: DayPlanRequest,
  tasks: AuthoritativeFlexibleTask[],
  rawProposal: unknown,
): ValidatedPlanResult {
  const proposal = planProposalSchema.parse(rawProposal);
  const taskById = new Map(tasks.map((task) => [task.occurrence_id, task]));
  const counts = new Map<string, number>();
  for (const item of [...proposal.placements, ...proposal.unscheduled]) {
    counts.set(item.occurrence_id, (counts.get(item.occurrence_id) ?? 0) + 1);
  }

  const rejected = new Map<string, string>();
  if (
    [...proposal.placements, ...proposal.unscheduled].some((item) =>
      !taskById.has(item.occurrence_id)
    )
  ) {
    for (const task of tasks) {
      rejected.set(
        task.occurrence_id,
        "The assistant returned an item outside today's task list.",
      );
    }
  }
  for (const task of tasks) {
    if (counts.get(task.occurrence_id) !== 1) {
      rejected.set(
        task.occurrence_id,
        counts.has(task.occurrence_id)
          ? "The assistant returned this task more than once."
          : "The assistant did not return a placement or an explanation for this task.",
      );
    }
  }

  const requestTaskIds = new Set(
    request.flexible_tasks.map((task) => task.occurrence_id),
  );
  if (
    tasks.some((task) => !requestTaskIds.has(task.occurrence_id)) ||
    request.flexible_tasks.some((task) => !taskById.has(task.occurrence_id))
  ) {
    throw new Error("The owner-scoped task list changed while planning.");
  }

  const pendingPlacements: ProposedPlacement[] = [];
  for (const placement of proposal.placements) {
    const task = taskById.get(placement.occurrence_id);
    if (!task) continue;
    if (rejected.has(task.occurrence_id)) continue;

    const start = Date.parse(placement.start);
    const end = Date.parse(placement.end);
    const duration = (end - start) / 60_000;
    const planningStart = Date.parse(request.planning_start);
    const planningEnd = Date.parse(request.planning_end);
    const earliest = task.earliest_start
      ? Date.parse(task.earliest_start)
      : planningStart;
    const latest = task.latest_end ? Date.parse(task.latest_end) : planningEnd;
    const deadline = task.deadline_at
      ? Date.parse(task.deadline_at)
      : Number.POSITIVE_INFINITY;

    let reason: string | null = null;
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
      reason = "The proposed time is invalid.";
    } else if (Math.abs(duration - task.duration_minutes) > 0.001) {
      reason = "The proposed duration does not match the task.";
    } else if (start < planningStart || end > planningEnd) {
      reason = "The proposed time falls outside planning hours.";
    } else if (start < earliest) {
      reason = "The proposed time is earlier than this task allows.";
    } else if (end > latest) {
      reason = "The proposed time ends later than this task allows.";
    } else if (end > deadline) {
      reason = "The proposed time is after this task's deadline.";
    } else if (
      request.busy_blocks.some((block) =>
        overlaps(start, end, Date.parse(block.start), Date.parse(block.end))
      )
    ) {
      reason = "The proposed time overlaps a scheduled commitment.";
    }
    if (reason) rejected.set(task.occurrence_id, reason);
    else {pendingPlacements.push({
        ...placement,
        reason: cleanReason(placement.reason),
      });}
  }

  const overlappingIds = new Set<string>();
  for (let i = 0; i < pendingPlacements.length; i++) {
    const a = pendingPlacements[i];
    const aStart = Date.parse(a.start);
    const aEnd = Date.parse(a.end);
    for (let j = i + 1; j < pendingPlacements.length; j++) {
      const b = pendingPlacements[j];
      if (overlaps(aStart, aEnd, Date.parse(b.start), Date.parse(b.end))) {
        overlappingIds.add(a.occurrence_id);
        overlappingIds.add(b.occurrence_id);
      }
    }
  }
  for (const id of overlappingIds) {
    rejected.set(id, "The proposal overlaps another task in this plan.");
  }

  const explicitUnscheduled = new Map<string, string>();
  for (const item of proposal.unscheduled) {
    if (taskById.has(item.occurrence_id) && !rejected.has(item.occurrence_id)) {
      explicitUnscheduled.set(item.occurrence_id, cleanReason(item.reason));
    }
  }

  const placements = pendingPlacements
    .filter((item) => !rejected.has(item.occurrence_id))
    .sort((a, b) =>
      Date.parse(a.start) - Date.parse(b.start) ||
      a.occurrence_id.localeCompare(b.occurrence_id)
    );
  const unscheduled = tasks
    .filter((task) =>
      rejected.has(task.occurrence_id) ||
      explicitUnscheduled.has(task.occurrence_id)
    )
    .map((task) => ({
      occurrence_id: task.occurrence_id,
      reason: rejected.get(task.occurrence_id) ??
        explicitUnscheduled.get(task.occurrence_id)!,
    }));

  return { placements, unscheduled, rejectedCount: rejected.size };
}

export function toModelContext(
  request: DayPlanRequest,
  tasks: AuthoritativeFlexibleTask[],
): Record<string, unknown> {
  return {
    date: request.date,
    timezone: request.timezone,
    planning_window: {
      start: request.planning_start,
      end: request.planning_end,
    },
    busy_blocks: request.busy_blocks.map(({ title, start, end }) => ({
      title,
      start,
      end,
    })),
    flexible_tasks: tasks.map((task) => ({
      occurrence_id: task.occurrence_id,
      title: task.title,
      duration_minutes: task.duration_minutes,
      priority: task.priority,
      deadline_at: task.deadline_at,
      earliest_start: task.earliest_start,
      latest_end: task.latest_end,
      preferred_start: task.preferred_start,
    })),
  };
}
