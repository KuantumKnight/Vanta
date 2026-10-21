package dev.system.app.planner

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Whether an item is immovable, preferred at a time, or only constrained by a window. */
enum class ScheduleType {
    HARD,
    SOFT,
    FLEXIBLE,
}

enum class ScheduleSource {
    VANTA_TASK,
    VANTA_HABIT,
    GOOGLE_CALENDAR,
    FOCUS_SESSION,
}

enum class SchedulePriority {
    LOW,
    NORMAL,
    HIGH,
}

/** A timed display block. All-day source events are expanded to the planning zone's day bounds. */
data class ScheduleBlock(
    val id: String,
    val source: ScheduleSource,
    val title: String,
    val start: Instant,
    val end: Instant,
    val scheduleType: ScheduleType,
    val editable: Boolean,
    val allDay: Boolean = false,
    val priority: SchedulePriority = SchedulePriority.NORMAL,
    val deadlineAt: Instant? = null,
    val earliestStart: Instant? = null,
    val latestEnd: Instant? = null,
    val preferredStart: Instant? = null,
)

data class FlexibleTask(
    val occurrenceId: String,
    val title: String,
    val durationMinutes: Int,
    val priority: SchedulePriority = SchedulePriority.NORMAL,
    val deadlineAt: Instant? = null,
    val earliestStart: Instant? = null,
    val latestEnd: Instant? = null,
    val preferredStart: Instant? = null,
)

data class DaySchedule(
    val date: LocalDate,
    val zoneId: ZoneId,
    val planningStart: Instant,
    val planningEnd: Instant,
    val blocks: List<ScheduleBlock>,
    val flexibleTasks: List<FlexibleTask>,
    val completedOccurrenceIds: Set<String> = emptySet(),
)

data class FreeInterval(
    val start: Instant,
    val end: Instant,
) {
    val durationMinutes: Long
        get() = java.time.Duration.between(start, end).toMinutes()
}

enum class ConflictType {
    HARD_HARD,
    HARD_SOFT,
    SOFT_SOFT,
    DEADLINE_RISK,
    INSUFFICIENT_SPACE,
    OVERRUN,
}

data class ScheduleConflict(
    val type: ConflictType,
    val blockIds: List<String>,
    val start: Instant,
    val end: Instant,
    val message: String,
)

data class CandidateSlot(
    val occurrenceId: String,
    val start: Instant,
    val end: Instant,
    val score: Int,
    val scoreReasons: List<String>,
)

data class PlanningContext(
    val date: LocalDate,
    val zoneId: ZoneId,
    val planningStart: Instant,
    val planningEnd: Instant,
    val hardBlocks: List<ScheduleBlock>,
    val calendarCommitments: List<ScheduleBlock>,
    val softBlocks: List<ScheduleBlock>,
    val flexibleTasks: List<FlexibleTask>,
    val completedOccurrenceIds: Set<String>,
)

data class PlanPlacement(
    val occurrenceId: String,
    val start: Instant,
    val end: Instant,
    val reason: String,
)

data class UnscheduledTask(
    val occurrenceId: String,
    val reason: String,
)

/** Untrusted proposal until a separate validator has accepted every placement. */
data class PlanProposal(
    val placements: List<PlanPlacement>,
    val unscheduled: List<UnscheduledTask>,
)
