import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import {
  createMcpHandler,
  McpServer,
} from "npm:@modelcontextprotocol/server@2.0.0";
import {
  withOAuthProtectedResource,
  withSupabase,
} from "npm:@supabase/server@1.8.0";
import { z } from "npm:zod@4.3.6";
import { admin } from "../_shared/admin.ts";
import { materializeScheduleForOwner } from "../_shared/materialize.ts";
import { pageBounds } from "../_shared/mcp_pagination.ts";
import {
  completeTaskInputSchema,
  createTaskInputSchema,
  schedulingPatchFields,
} from "../_shared/mcp_validation.ts";
import { normalizeTaskReminder } from "../_shared/reminders.ts";
import {
  localDateTimeToUtc,
  nextLocalDate,
  todayInZone,
  validateRRule,
} from "../_shared/recurrence.ts";

const TIME_ZONE = "Asia/Kolkata";
const OCCURRENCE_SELECT =
  "id,task_id,schedule_id,scheduled_for,all_day,duration_minutes,reminder_at,status,completed_at,schedule_type,priority,deadline_at,earliest_start,latest_end,preferred_start,task:tasks!inner(id,title,notes,kind,archived_at,default_schedule_type,default_priority)";

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
    timeZone: TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date(value));
  const values = Object.fromEntries(
    parts.map((part) => [part.type, part.value]),
  );
  return values.year + "-" + values.month + "-" + values.day;
}

function localClockFromInstant(value: string): string {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: TIME_ZONE,
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(new Date(value));
  const values = Object.fromEntries(
    parts.map((part) => [part.type, part.value]),
  );
  return values.hour + ":" + values.minute;
}

function registerTools(server: McpServer, supabase: any) {
  server.registerTool(
    "get_context",
    {
      description:
        "Get the current date and time zone before interpreting relative dates such as today, tomorrow, or Friday.",
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
      description:
        "Create a one-time task. Supply a fresh operation_id UUID and reuse it only when retrying this exact request. Use a local YYYY-MM-DD due_date and optional HH:mm due_time in Asia/Kolkata. Set duration_minutes to the task length from 5 through 1440 minutes when known. schedule_type may be HARD, SOFT, or FLEXIBLE; priority may be LOW, NORMAL, or HIGH. deadline_at is an independent RFC 3339 instant and never sets the start time. earliest_start, latest_end, and preferred_start are optional RFC 3339 instants.",
      inputSchema: createTaskInputSchema,
    },
    async (
      {
        operation_id,
        title,
        notes,
        due_date,
        due_time,
        reminder_at,
        reminder_time,
        duration_minutes,
        schedule_type,
        priority,
        deadline_at,
        earliest_start,
        latest_end,
        preferred_start,
      },
    ) => {
      const scheduledFor = due_date ? localInstant(due_date, due_time) : null;
      const reminder = normalizeTaskReminder(
        due_date,
        due_time ? scheduledFor ?? undefined : undefined,
        reminder_at,
        reminder_time,
      );
      const commonArguments = {
        p_operation_id: operation_id,
        p_title: title.trim(),
        p_notes: notes ?? null,
        p_scheduled_for: scheduledFor,
        p_all_day: !!due_date && !due_time,
        p_reminder_at: reminder,
        p_duration_minutes: duration_minutes ?? null,
      };
      const hasScheduling = [
        schedule_type,
        priority,
        deadline_at,
        earliest_start,
        latest_end,
        preferred_start,
      ]
        .some((value) => value !== undefined);
      const { data, error } = hasScheduling
        ? await supabase.rpc("mcp_create_task_with_scheduling", {
          ...commonArguments,
          p_schedule_type: schedule_type ?? null,
          p_priority: priority ?? null,
          p_deadline_at: deadline_at ?? null,
          p_earliest_start: earliest_start ?? null,
          p_latest_end: latest_end ?? null,
          p_preferred_start: preferred_start ?? null,
        })
        : await supabase.rpc("mcp_create_task", commonArguments);
      throwIfError(error);
      return toolResult(data);
    },
  );

  server.registerTool(
    "list_tasks",
    {
      description:
        "Find the signed-in user's open tasks and dated habit occurrences. Set include_completed to true to include completed tasks in the results. Use offset with limit to fetch another page; start at zero and add limit for each next page. Use the returned task_id or occurrence_id for updates and completion. If several results could match, ask which one the user means before changing or completing anything.",
      inputSchema: z.object({
        from_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
        through_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
        query: z.string().trim().max(200).optional(),
        include_completed: z.boolean().default(false),
        limit: z.number().int().min(1).max(100).default(50),
        offset: z.number().int().min(0).max(10000).default(0),
      }),
      annotations: { readOnlyHint: true },
    },
    async (
      { from_date, through_date, query, include_completed, limit, offset },
    ) => {
      let request = supabase.from("task_occurrences").select(OCCURRENCE_SELECT)
        .is("task.archived_at", null)
        .order("scheduled_for", { ascending: true, nullsFirst: false })
        .order("id", { ascending: true })
        .range(...pageBounds(offset, limit));
      if (!include_completed) request = request.eq("status", "pending");
      if (from_date) {
        request = request.gte("scheduled_for", localInstant(from_date));
      }
      if (through_date) {
        request = request.lt(
          "scheduled_for",
          localInstant(nextLocalDate(through_date)),
        );
      }
      if (query) {
        request = request.ilike(
          "task.title",
          "%" + query.replace(/[%_]/g, "") + "%",
        );
      }
      const { data, error } = await request;
      throwIfError(error);
      return toolResult(data);
    },
  );

  server.registerTool(
    "update_task",
    {
      description:
        "Update a one-time task's title, notes, due date, duration, schedule type, priority, deadline, planning window, preferred start, or archive state. Supply a fresh operation_id UUID and reuse it only for a retry of this exact request. Set duration_minutes from 5 through 1440 to change the duration, or null to clear it. Use update_habit_schedule for a recurring habit. Deadline and planning-window instants are RFC 3339 and do not change the chosen start.",
      inputSchema: z.object({
        operation_id: z.string().uuid(),
        task_id: z.string().uuid(),
        title: z.string().trim().min(1).max(200).optional(),
        notes: z.string().max(4000).nullable().optional(),
        due_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
        due_time: z.string().regex(/^\d{2}:\d{2}$/).optional(),
        reminder_at: z.string().max(64).nullable().optional(),
        reminder_time: z.string().regex(/^\d{2}:\d{2}$/).optional(),
        duration_minutes: z.number().int().min(5).max(1440).nullable()
          .optional(),
        ...schedulingPatchFields,
        archived: z.boolean().optional(),
      }).refine(
        (input) => !input.due_time || !!input.due_date,
        "due_time requires due_date",
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
            input.due_date === undefined || input.schedule_type !== "FLEXIBLE",
          "schedule_type must match whether a scheduled start is provided",
        ),
    },
    async (input) => {
      if (
        Object.keys(input).every((key) =>
          key === "task_id" || key === "operation_id"
        )
      ) throw new Error("Provide at least one task change.");
      let scheduledFor: string | null = null;
      let reminder: string | null = null;
      let updateReminder = false;
      if (input.due_date) {
        scheduledFor = localInstant(input.due_date, input.due_time);
        reminder = input.reminder_at === null ? null : normalizeTaskReminder(
          input.due_date,
          input.due_time ? scheduledFor : undefined,
          input.reminder_at,
          input.reminder_time,
        );
        updateReminder = true;
      } else if (
        input.reminder_at !== undefined || input.reminder_time !== undefined
      ) {
        const { data: occurrence, error } = await supabase.from(
          "task_occurrences",
        )
          .select("id,scheduled_for,all_day").eq("task_id", input.task_id).is(
            "schedule_id",
            null,
          ).maybeSingle();
        throwIfError(error);
        if (!occurrence) throw new Error("Task occurrence not found.");
        const date = occurrence.scheduled_for
          ? dateFromInstant(occurrence.scheduled_for)
          : undefined;
        if (input.reminder_time && !date) {
          throw new Error("Add a due date before setting a reminder time.");
        }
        reminder = input.reminder_at === null ? null : normalizeTaskReminder(
          date,
          occurrence.all_day
            ? undefined
            : occurrence.scheduled_for ?? undefined,
          input.reminder_at ?? undefined,
          input.reminder_time,
        );
        updateReminder = true;
      }
      const commonArguments = {
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
        p_duration_minutes: input.duration_minutes ?? null,
        p_update_duration: input.duration_minutes !== undefined,
      };
      const schedulingKeys = [
        "schedule_type",
        "priority",
        "deadline_at",
        "earliest_start",
        "latest_end",
        "preferred_start",
      ] as const;
      const scheduling = Object.fromEntries(
        schedulingKeys
          .filter((key) => input[key] !== undefined)
          .map((key) => [key, input[key]]),
      );
      const { data, error } = Object.keys(scheduling).length > 0
        ? await supabase.rpc("mcp_update_task_with_scheduling", {
          ...commonArguments,
          p_scheduling: scheduling,
        })
        : await supabase.rpc("mcp_update_task", commonArguments);
      throwIfError(error);
      if (!data) throw new Error("Task was not changed.");
      return toolResult(data);
    },
  );

  server.registerTool(
    "complete_task",
    {
      description:
        "Mark a specific task or habit occurrence complete. Supply a fresh operation_id UUID and reuse it only when retrying this exact request. First list tasks if the matching occurrence ID is not already known. If the user's wording matches multiple occurrences, ask which one they mean instead of choosing arbitrarily.",
      inputSchema: completeTaskInputSchema,
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
      description:
        "Create a recurring habit using an RFC 5545 RRULE, optional inclusion/exclusion dates, and Asia/Kolkata local start date/time. Set default_duration_minutes from 5 through 1440 to carry that duration into generated occurrences. Recurrence intervals under 15 minutes are rejected. Supply a fresh operation_id UUID and reuse it only when retrying this exact request.",
      inputSchema: z.object({
        operation_id: z.string().uuid(),
        title: z.string().trim().min(1).max(200),
        notes: z.string().max(4000).optional(),
        start_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
        start_time: z.string().regex(/^\d{2}:\d{2}$/).optional(),
        rrule: z.string().min(1).max(2048),
        rdates: z.array(z.string().regex(/^\d{4}-\d{2}-\d{2}$/)).max(500)
          .default([]),
        exdates: z.array(z.string().regex(/^\d{4}-\d{2}-\d{2}$/)).max(500)
          .default([]),
        reminder_time: z.string().regex(/^\d{2}:\d{2}$/).optional(),
        default_duration_minutes: z.number().int().min(5).max(1440).nullable()
          .optional(),
      }),
    },
    async (
      {
        operation_id,
        title,
        notes,
        start_date,
        start_time,
        rrule,
        rdates,
        exdates,
        reminder_time,
        default_duration_minutes,
      },
    ) => {
      const normalizedRule = validateRRule(rrule);
      const startsAt = localInstant(start_date, start_time);
      const { data, error: scheduleError } = await supabase.rpc(
        "mcp_create_habit",
        {
          p_operation_id: operation_id,
          p_title: title.trim(),
          p_notes: notes ?? null,
          p_dtstart: startsAt,
          p_rrule: normalizedRule,
          p_rdates: rdates.map((date) =>
            localInstant(date, start_time ?? "00:00")
          ),
          p_exdates: exdates.map((date) =>
            localInstant(date, start_time ?? "00:00")
          ),
          p_reminder_time: reminder_time ?? null,
          p_default_duration_minutes: default_duration_minutes ?? null,
        },
      );
      throwIfError(scheduleError);
      const schedule = data?.schedule;
      if (!schedule) throw new Error("The habit schedule was not created.");
      const { data: authData, error: authError } = await supabase.auth
        .getUser();
      throwIfError(authError);
      if (!authData.user?.id) throw new Error("Authentication required.");
      const count = await materializeScheduleForOwner(
        admin,
        authData.user.id,
        schedule.id,
      );
      return toolResult({ ...data, materialized_occurrences: count });
    },
  );

  server.registerTool(
    "update_habit_schedule",
    {
      description:
        "Change a habit's recurrence from a future date. Completed history is preserved; pending occurrences from that date onward are replaced by the new schedule. Supply a fresh operation_id UUID and reuse it only when retrying this exact request.",
      inputSchema: z.object({
        operation_id: z.string().uuid(),
        task_id: z.string().uuid(),
        effective_from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
        start_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
        start_time: z.string().regex(/^\d{2}:\d{2}$/).optional(),
        rrule: z.string().min(1).max(2048),
        rdates: z.array(z.string().regex(/^\d{4}-\d{2}-\d{2}$/)).max(500)
          .optional(),
        exdates: z.array(z.string().regex(/^\d{4}-\d{2}-\d{2}$/)).max(500)
          .optional(),
        reminder_time: z.string().regex(/^\d{2}:\d{2}$/).nullable().optional(),
      }),
    },
    async (input) => {
      if (
        Object.keys(input).every((key) =>
          key === "task_id" || key === "operation_id"
        )
      ) throw new Error("Provide a schedule change.");
      const newRule = validateRRule(input.rrule);
      const effectiveFrom = localInstant(input.effective_from);
      const { data: current, error: currentError } = await supabase.from(
        "habit_schedules",
      )
        .select(
          "id,task_id,user_id,dtstart,timezone,rrule,rdates,exdates,effective_from,effective_until,reminder_time",
        )
        .eq("task_id", input.task_id).is("effective_until", null).maybeSingle();
      throwIfError(currentError);
      if (!current) throw new Error("Active habit schedule not found.");
      const startDate = input.start_date ?? dateFromInstant(current.dtstart);
      const startTime = input.start_time ??
        localClockFromInstant(current.dtstart);
      const newStart = localInstant(startDate, startTime);
      const { data, error: scheduleError } = await supabase.rpc(
        "mcp_update_habit_schedule",
        {
          p_operation_id: input.operation_id,
          p_task_id: current.task_id,
          p_effective_from: effectiveFrom,
          p_dtstart: newStart,
          p_rrule: newRule,
          p_rdates: (input.rdates ?? current.rdates).map((date: string) =>
            dateString(date) ? localInstant(date, startTime) : date
          ),
          p_exdates: (input.exdates ?? current.exdates).map((date: string) =>
            dateString(date) ? localInstant(date, startTime) : date
          ),
          p_reminder_time: input.reminder_time === undefined
            ? current.reminder_time
            : input.reminder_time,
        },
      );
      throwIfError(scheduleError);
      const schedule = data?.schedule;
      if (!schedule) throw new Error("The habit schedule was not changed.");
      const { data: authData, error: authError } = await supabase.auth
        .getUser();
      throwIfError(authError);
      if (!authData.user?.id) throw new Error("Authentication required.");
      const count = await materializeScheduleForOwner(
        admin,
        authData.user.id,
        schedule.id,
      );
      return toolResult({ ...data, materialized_occurrences: count });
    },
  );

  server.registerTool(
    "skip_habit_occurrence",
    {
      description:
        "Skip one pending occurrence of a recurring habit while preserving its future schedule. Supply a fresh operation_id UUID and reuse it only when retrying this exact request.",
      inputSchema: z.object({
        operation_id: z.string().uuid(),
        occurrence_id: z.string().uuid(),
      }),
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
      description:
        "Create a goal that can be linked to one-time tasks or recurring habits. Goal progress is derived from completed occurrences. Supply a fresh operation_id UUID and reuse it only when retrying this exact request.",
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
      description:
        "Link a task to a goal. Set target_count to the number of habit completions required; one-time tasks normally use 1. Supply a fresh operation_id UUID and reuse it only when retrying this exact request.",
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
      description:
        "List the signed-in user's goals with completed and target occurrence counts.",
      inputSchema: z.object({}),
      annotations: { readOnlyHint: true },
    },
    async () => {
      const { data, error } = await supabase.from("goal_progress")
        .select(
          "goal_id,title,description,completed_count,target_count,completed",
        )
        .order("title");
      throwIfError(error);
      return toolResult(data);
    },
  );

  server.registerTool(
    "update_goal",
    {
      description:
        "Update a goal title or description. Supply a fresh operation_id UUID and reuse it only when retrying this exact request.",
      inputSchema: z.object({
        operation_id: z.string().uuid(),
        goal_id: z.string().uuid(),
        title: z.string().trim().min(1).max(200).optional(),
        description: z.string().max(4000).nullable().optional(),
      }),
    },
    async ({ operation_id, goal_id, title, description }) => {
      if (title === undefined && description === undefined) {
        throw new Error("Provide a title or description to change.");
      }
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
        name: "vanta-task-command-center",
        version: "1.0.0",
      });
      registerTools(server, supabase);
      return server;
    });
    return mcpHandler.fetch(request);
  }),
);

Deno.serve(handler);
