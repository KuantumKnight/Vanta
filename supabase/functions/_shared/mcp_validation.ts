import { z } from "npm:zod@4.3.6";

export const schedulingPatchFields = {
  schedule_type: z.enum(["HARD", "SOFT", "FLEXIBLE"]).optional(),
  priority: z.enum(["LOW", "NORMAL", "HIGH"]).optional(),
  deadline_at: z.string().datetime({ offset: true }).nullable().optional(),
  earliest_start: z.string().datetime({ offset: true }).nullable().optional(),
  latest_end: z.string().datetime({ offset: true }).nullable().optional(),
  preferred_start: z.string().datetime({ offset: true }).nullable().optional(),
};

export const createTaskInputSchema = z.object({
  operation_id: z.string().uuid(),
  title: z.string().trim().min(1).max(200),
  notes: z.string().max(4000).optional(),
  due_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  due_time: z.string().regex(/^\d{2}:\d{2}$/).optional(),
  reminder_at: z.string().max(64).optional(),
  reminder_time: z.string().regex(/^\d{2}:\d{2}$/).optional(),
  duration_minutes: z.number().int().min(5).max(1440).nullable().optional(),
  ...schedulingPatchFields,
})
  .refine(
    (input) => !input.due_time || !!input.due_date,
    "due_time requires due_date",
  )
  .refine(
    (input) => !input.reminder_time || !!input.due_date,
    "reminder_time requires due_date",
  )
  .refine(
    (input) =>
      !input.earliest_start || !input.latest_end ||
      Date.parse(input.latest_end) >= Date.parse(input.earliest_start),
    "latest_end must be at or after earliest_start",
  )
  .refine(
    (input) =>
      !input.deadline_at || !input.earliest_start ||
      Date.parse(input.deadline_at) >= Date.parse(input.earliest_start),
    "deadline_at must not be before earliest_start",
  )
  .refine(
    (input) =>
      input.schedule_type === undefined ||
      (input.schedule_type === "FLEXIBLE"
        ? input.due_date === undefined
        : input.due_date !== undefined),
    "schedule_type must match whether a scheduled start is provided",
  );

export const schedulingPatchSchema = z.object(schedulingPatchFields)
  .refine(
    (input) =>
      !input.earliest_start || !input.latest_end ||
      Date.parse(input.latest_end) >= Date.parse(input.earliest_start),
    "latest_end must be at or after earliest_start",
  );

export const completeTaskInputSchema = z.object({
  operation_id: z.string().uuid(),
  occurrence_id: z.string().uuid(),
});
