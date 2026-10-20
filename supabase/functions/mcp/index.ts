import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createMcpHandler, McpServer } from "npm:@modelcontextprotocol/server@2.0.0";
import { withOAuthProtectedResource, withSupabase } from "npm:@supabase/server@1.8.0";
import { z } from "npm:zod@4.3.6";
import {
  defaultReminderForDate,
  getOccurrences,
  localDateTimeToUtc,
  nextLocalDate,
  parseIsoInstant,
  todayInZone,
  validateRRule,
  type ScheduleForExpansion,
} from "../_shared/recurrence.ts";

const TIME_ZONE = "Asia/Kolkata";
const OCCURRENCE_SELECT =
  "id,task_id,schedule_id,scheduled_for,all_day,reminder_at,status,completed_at,task:tasks!inner(id,title,notes,kind,archived_at)";

function toolResult(value: unknown) {
  return { content: [{ type: "text" as const, text: JSON.stringify(value) }] };
}

function throwIfError(error: { message: string } | null): void {
  if (error) throw new Error(error.message);
}

function dateString(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(value);
}

function localInstant(date: string, time?: string): string {
  return localDateTimeToUtc(date, time ?? "00:00");
}

function dateFromInstant(value: string): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit",
  }).formatToParts(new Date(value));
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return values.year + "-" + values.month + "-" + values.day;
}

function localClockFromInstant(value: string): string {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: TIME_ZONE, hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  }).formatToParts(new Date(value));
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return values.hour + ":" + values.minute;
}

function normalizeReminder(
  dueDate: string | undefined,
  dueAt: string | undefined,
  reminderAt: string | undefined,
  reminderTime: string | undefined,
): string | null {
  if (reminderAt !== undefined) return parseIsoInstant(reminderAt);
  if (!dueDate) return null;
  if (reminderTime) return localInstant(dueDate, reminderTime);
  if (dueAt) return null;
  const dueInstant = new Date(localInstant(dueDate));
  return defaultReminderForDate(dueDate, dueInstant);
}

async function materializeSchedule(supabase: any, schedule: ScheduleForExpansion) {
  const now = new Date();
  const from = new Date(now.getTime() - 86400000);
  const to = new Date(now.getTime() + 120 * 86400000);
  const generated = getOccurrences(schedule, from, to);
  const reminderTime = schedule.reminder_time?.slice(0, 5);
  const rows = generated.map((scheduled) => {
    const reminderAt = reminderTime
      ? localDateTimeToUtc(dateFromInstant(scheduled.toISOString()), reminderTime)
      : null;
    return {
      task_id: schedule.task_id,
      schedule_id: schedule.id,
      user_id: schedule.user_id,
      scheduled_for: scheduled.toISOString(),
      all_day: false,
      reminder_at: reminderAt && new Date(reminderAt) > now ? reminderAt : null,
    };
  });
  for (let offset = 0; offset < rows.length; offset += 500) {
    const { error } = await supabase.from("task_occurrences").upsert(
      rows.slice(offset, offset + 500),
      { onConflict: "schedule_id,scheduled_for", ignoreDuplicates: true },
    );
    throwIfError(error);
  }
  return rows.length;
}

function registerTools(server: McpServer, supabase: any) {
  server.registerTool(
    "get_context",
    {
      description: "Get the current date and time zone before interpreting relative dates such as today, tomorrow, or Friday.",
      inputSchema: z.object({}),
      annotations: { readOnlyHint: true },
    },
    async () => {
      const { error } = await supabase.auth.getUser();
      throwIfError(error);
      return toolResult({
        now: new Date().toISOString(),
        local_date: todayInZone(),
        timezone: TIME_ZONE,
      });
    },
  );

  server.registerTool(
    "create_task",
    {
      description: "Create a one-time task. Supply a fresh operation_id UUID and reuse it only when retrying this exact request. Use a local YYYY-MM-DD due_date and optional HH:mm due_time in Asia/Kolkata. Date-only deadlines default to 9 AM the previous day; use reminder_at for an explicit reminder instant or reminder_time for a reminder on the due date.",
      inputSchema: z.object({
        operation_id: z.string().uuid(),
        title: z.string().trim().min(1).max(200),
        notes: z.string().max(4000).optional(),
        due_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
        due_time: z.string().regex(/^\d{2}:\d{2}$/).optional(),
        reminder_at: z.string().max(64).optional(),
        reminder_time: z.string().regex(/^\d{2}:\d{2}$/).optional(),
      })
        .refine((input) => !input.due_time || !!input.due_date, "due_time requires due_date")
        .refine((input) => !input.reminder_time || !!input.due_date, "reminder_time requires due_date"),
    },
    async ({ operation_id, title, notes, due_date, due_time, reminder_at, reminder_time }) => {
      const scheduledFor = due_date ? localInstant(due_date, due_time) : null;
      const reminder = normalizeReminder(due_date, due_time ? scheduledFor ?? undefined : undefined, reminder_at, reminder_time);
      const { data, error } = await supabase.rpc("mcp_create_task", {
        p_operation_id: operation_id,
        p_title: title.trim(),
        p_notes: notes ?? null,
        p_scheduled_for: scheduledFor,
        p_all_day: !!due_date && !due_time,
        p_reminder_at: reminder,
      });
      throwIfError(error);
      return toolResult(data);
    },
  );

  server.registerTool(
    "list_tasks",
    {
      description: "Find the signed-in user's open tasks and dated habit occurrences. Set include_completed to true to include completed tasks; skipped habit occurrences are never returned. Use limit and offset to page through large result sets. Use the returned task_id or occurrence_id for updates and completion. If several results could match, ask which one the user means before changing or completing anything.",
      inputSchema: z.object({
        from_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
        through_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
        query: z.string().trim().max(200).optional(),
        include_completed: z.boolean().default(false),
        limit: z.number().int().min(1).max(100).default(50),
        offset: z.number().int().min(0).max(100000).default(0),
      }),
      annotations: { readOnlyHint: true },
    },
    async ({ from_date, through_date, query, include_completed, limit, offset }) => {
      let request = supabase.from("task_occurrences").select(OCCURRENCE_SELECT)
        .is("task.archived_at", null)
        .order("scheduled_for", { ascending: true, nullsFirst: false })
        .order("id", { ascending: true });
      if (!include_completed) request = request.eq("status", "pending");
      else request = request.neq("status", "skipped");
      if (from_date) request = request.gte("scheduled_for", localInstant(from_date));
      if (through_date) request = request.lt("scheduled_for", localInstant(nextLocalDate(through_date)));
      if (query) request = request.ilike("task.title", "%" + query.replace(/[%_]/g, "") + "%");
      const { data, error } = await request.range(offset, offset + limit - 1);
      throwIfError(error);
      return toolResult(data);
    },
  );

  server.registerTool(
    "update_task",
    {
      description: "Update a one-time task's title, notes, due date, or archive state. Supply a fresh operation_id UUID and reuse it only for a retry of this exact request. Use update_habit_schedule for a recurring habit.",
      inputSchema: z.object({
        operation_id: z.string().uuid(),
        task_id: z.string().uuid(),
        title: z.string().trim().min(1).max(200).optional(),
        notes: z.string().max(4000).nullable().optional(),
        due_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
        due_time: z.string().regex(/^\d{2}:\d{2}$/).optional(),
        reminder_at: z.string().max(64).nullable().optional(),
        reminder_time: z.string().regex(/^\d{2}:\d{2}$/).optional(),
        archived: z.boolean().optional(),
      }).refine((input) => !input.due_time || !!input.due_date, "due_time requires due_date"),
    },
    async (input) => {
      if (Object.keys(input).every((key) => key === "task_id" || key === "operation_id")) throw new Error("Provide at least one task change.");
      let scheduledFor: string | null = null;
      let reminder: string | null = null;
      let updateReminder = false;
      if (input.due_date) {
        scheduledFor = localInstant(input.due_date, input.due_time);
        reminder = input.reminder_at === null
          ? null
          : normalizeReminder(input.due_date, input.due_time ? scheduledFor : undefined, input.reminder_at, input.reminder_time);
        updateReminder = true;
      } else if (input.reminder_at !== undefined || input.reminder_time !== undefined) {
        const { data: occurrence, error } = await supabase.from("task_occurrences")
          .select("id,scheduled_for,all_day").eq("task_id", input.task_id).is("schedule_id", null).maybeSingle();
        throwIfError(error);
        if (!occurrence) throw new Error("Task occurrence not found.");
        const date = occurrence.scheduled_for ? dateFromInstant(occurrence.scheduled_for) : undefined;
        if (input.reminder_time && !date) throw new Error("Add a due date before setting a reminder time.");
        reminder = input.reminder_at === null
          ? null
          : normalizeReminder(date, occurrence.all_day ? undefined : occurrence.scheduled_for ?? undefined, input.reminder_at ?? undefined, input.reminder_time);
        updateReminder = true;
      }
      const { data, error } = await supabase.rpc("mcp_update_task", {
        p_operation_id: input.operation_id,
        p_task_id: input.task_id,
        p_title: input.title?.trim() ?? null,
        p_update_title: input.title !== undefined,
        p_notes: input.notes ?? null,
        p_update_notes: input.notes !== undefined,
        p_scheduled_for: scheduledFor,
        p_update_schedule: input.due_date !== undefined,
        p_all_day: input.due_date !== undefined && !input.due_time,
        p_reminder_at: reminder,
        p_update_reminder: updateReminder,
        p_archived: input.archived ?? false,
        p_update_archived: input.archived !== undefined,
      });
      throwIfError(error);
      if (!data) throw new Error("Task was not changed.");
      return toolResult(data);
    },
  );

  server.registerTool(
    "complete_task",
    {
      description: "Mark a specific task or habit occurrence complete. Supply a fresh operation_id UUID and reuse it only when retrying this exact request. First list tasks if the matching occurrence ID is not already known. If the user's wording matches multiple occurrences, ask which one they mean instead of choosing arbitrarily.",
      inputSchema: z.object({ operation_id: z.string().uuid(), occurrence_id: z.string().uuid() }),
    },
    async ({ operation_id, occurrence_id }) => {
      const { data, error } = await supabase.rpc("mcp_complete_task", {
        p_operation_id: operation_id,
        p_occurrence_id: occurrence_id,
      });
      throwIfError(error);
      return toolResult(data);
    },
  );

  server.registerTool(
    "create_habit",
    {
      description: "Create a recurring habit using an RFC 5545 RRULE, optional inclusion/exclusion dates, and Asia/Kolkata local start date/time. Recurrence intervals under 15 minutes are rejected. Supply a fresh operation_id UUID and reuse it only when retrying this exact request.",
      inputSchema: z.object({
        operation_id: z.string().uuid(),
        title: z.string().trim().min(1).max(200),
        notes: z.string().max(4000).optional(),
        start_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
        start_time: z.string().regex(/^\d{2}:\d{2}$/).optional(),
        rrule: z.string().min(1).max(2048),
        rdates: z.array(z.string().regex(/^\d{4}-\d{2}-\d{2}$/)).max(500).default([]),
        exdates: z.array(z.string().regex(/^\d{4}-\d{2}-\d{2}$/)).max(500).default([]),
        reminder_time: z.string().regex(/^\d{2}:\d{2}$/).optional(),
      }),
    },
    async ({ operation_id, title, notes, start_date, start_time, rrule, rdates, exdates, reminder_time }) => {
      const normalizedRule = validateRRule(rrule);
      const startsAt = localInstant(start_date, start_time);
      const { data, error: scheduleError } = await supabase.rpc("mcp_create_habit", {
        p_operation_id: operation_id,
        p_title: title.trim(),
        p_notes: notes ?? null,
        p_dtstart: startsAt,
        p_rrule: normalizedRule,
        p_rdates: rdates.map((date) => localInstant(date, start_time ?? "00:00")),
        p_exdates: exdates.map((date) => localInstant(date, start_time ?? "00:00")),
        p_reminder_time: reminder_time ?? null,
      });
      throwIfError(scheduleError);
      const schedule = data?.schedule;
      if (!schedule) throw new Error("The habit schedule was not created.");
      const count = await materializeSchedule(supabase, schedule as ScheduleForExpansion);
      return toolResult({ ...data, materialized_occurrences: count });
    },
  );

  server.registerTool(
    "update_habit_schedule",
    {
      description: "Change a habit's recurrence from a future date. Completed history is preserved; pending occurrences from that date onward are replaced by the new schedule. Supply a fresh operation_id UUID and reuse it only when retrying this exact request.",
      inputSchema: z.object({
        operation_id: z.string().uuid(),
        task_id: z.string().uuid(),
        effective_from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
        start_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
        start_time: z.string().regex(/^\d{2}:\d{2}$/).optional(),
        rrule: z.string().min(1).max(2048),
        rdates: z.array(z.string().regex(/^\d{4}-\d{2}-\d{2}$/)).max(500).optional(),
        exdates: z.array(z.string().regex(/^\d{4}-\d{2}-\d{2}$/)).max(500).optional(),
        reminder_time: z.string().regex(/^\d{2}:\d{2}$/).nullable().optional(),
      }),
    },
    async (input) => {
      if (Object.keys(input).every((key) => key === "task_id" || key === "operation_id")) throw new Error("Provide a schedule change.");
      const newRule = validateRRule(input.rrule);
      const effectiveFrom = localInstant(input.effective_from);
      const { data: current, error: currentError } = await supabase.from("habit_schedules")
        .select("id,task_id,user_id,dtstart,timezone,rrule,rdates,exdates,effective_from,effective_until,reminder_time")
        .eq("task_id", input.task_id).is("effective_until", null).maybeSingle();
      throwIfError(currentError);
      if (!current) throw new Error("Active habit schedule not found.");
      const startDate = input.start_date ?? dateFromInstant(current.dtstart);
      const startTime = input.start_time ?? localClockFromInstant(current.dtstart);
      const newStart = localInstant(startDate, startTime);
      const { data, error: scheduleError } = await supabase.rpc("mcp_update_habit_schedule", {
        p_operation_id: input.operation_id,
        p_task_id: current.task_id,
        p_effective_from: effectiveFrom,
        p_dtstart: newStart,
        p_rrule: newRule,
        p_rdates: (input.rdates ?? current.rdates).map((date: string) => dateString(date) ? localInstant(date, startTime) : date),
        p_exdates: (input.exdates ?? current.exdates).map((date: string) => dateString(date) ? localInstant(date, startTime) : date),
        p_reminder_time: input.reminder_time === undefined ? current.reminder_time : input.reminder_time,
      });
      throwIfError(scheduleError);
      const schedule = data?.schedule;
      if (!schedule) throw new Error("The habit schedule was not changed.");
      const count = await materializeSchedule(supabase, schedule as ScheduleForExpansion);
      return toolResult({ ...data, materialized_occurrences: count });
    },
  );

  server.registerTool(
    "skip_habit_occurrence",
    {
      description: "Skip one pending occurrence of a recurring habit while preserving its future schedule. Supply a fresh operation_id UUID and reuse it only when retrying this exact request.",
      inputSchema: z.object({ operation_id: z.string().uuid(), occurrence_id: z.string().uuid() }),
    },
    async ({ operation_id, occurrence_id }) => {
      const { data, error } = await supabase.rpc("mcp_skip_habit_occurrence", {
        p_operation_id: operation_id,
        p_occurrence_id: occurrence_id,
      });
      throwIfError(error);
      return toolResult(data);
    },
  );

  server.registerTool(
    "create_goal",
    {
      description: "Create a goal that can be linked to one-time tasks or recurring habits. Goal progress is derived from completed occurrences. Supply a fresh operation_id UUID and reuse it only when retrying this exact request.",
      inputSchema: z.object({
        operation_id: z.string().uuid(),
        title: z.string().trim().min(1).max(200),
        description: z.string().max(4000).optional(),
      }),
    },
    async ({ operation_id, title, description }) => {
      const { data, error } = await supabase.rpc("mcp_create_goal", {
        p_operation_id: operation_id,
        p_title: title.trim(),
        p_description: description ?? null,
      });
      throwIfError(error);
      return toolResult(data);
    },
  );

  server.registerTool(
    "link_goal_task",
    {
      description: "Link a task to a goal. Set target_count to the number of habit completions required; one-time tasks normally use 1. Supply a fresh operation_id UUID and reuse it only when retrying this exact request.",
      inputSchema: z.object({
        operation_id: z.string().uuid(),
        goal_id: z.string().uuid(),
        task_id: z.string().uuid(),
        target_count: z.number().int().min(1).max(1000000).default(1),
      }),
    },
    async ({ operation_id, goal_id, task_id, target_count }) => {
      const { data, error } = await supabase.rpc("mcp_link_goal_task", {
        p_operation_id: operation_id,
        p_goal_id: goal_id,
        p_task_id: task_id,
        p_target_count: target_count,
      });
      throwIfError(error);
      return toolResult(data);
    },
  );

  server.registerTool(
    "list_goals",
    {
      description: "List the signed-in user's goals with completed and target occurrence counts.",
      inputSchema: z.object({}),
      annotations: { readOnlyHint: true },
    },
    async () => {
      const { data, error } = await supabase.from("goal_progress")
        .select("goal_id,title,description,completed_count,target_count,completed")
        .order("title");
      throwIfError(error);
      return toolResult(data);
    },
  );

  server.registerTool(
    "update_goal",
    {
      description: "Update a goal title or description. Supply a fresh operation_id UUID and reuse it only when retrying this exact request.",
      inputSchema: z.object({
        operation_id: z.string().uuid(),
        goal_id: z.string().uuid(),
        title: z.string().trim().min(1).max(200).optional(),
        description: z.string().max(4000).nullable().optional(),
      }),
    },
    async ({ operation_id, goal_id, title, description }) => {
      if (title === undefined && description === undefined) throw new Error("Provide a title or description to change.");
      const { data, error } = await supabase.rpc("mcp_update_goal", {
        p_operation_id: operation_id,
        p_goal_id: goal_id,
        p_title: title?.trim() ?? null,
        p_description: description ?? null,
        p_update_title: title !== undefined,
        p_update_description: description !== undefined,
      });
      throwIfError(error);
      if (!data) throw new Error("Goal not found.");
      return toolResult(data);
    },
  );
}

const handler = withOAuthProtectedResource(
  withSupabase({ auth: "user" }, async (request, { supabase }) => {
    const mcpHandler = createMcpHandler(() => {
      const server = new McpServer({
        name: "system-task-command-center",
        version: "1.0.0",
      });
      registerTools(server, supabase);
      return server;
    });
    return mcpHandler.fetch(request);
  }),
);

Deno.serve(handler);
