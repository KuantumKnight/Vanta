package dev.system.app.planner.ai

import dev.system.app.planner.DaySchedule
import dev.system.app.planner.FlexibleTask
import dev.system.app.planner.PlanPlacement
import dev.system.app.planner.PlanProposal
import dev.system.app.planner.PlanningContext
import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.UnscheduledTask
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal data class PlanIssue(
    val occurrenceId: String?,
    val reason: String,
)

internal data class PlanValidationResult(
    val proposal: PlanProposal,
    val issues: List<PlanIssue>,
) {
    val isApplicable: Boolean get() = proposal.placements.isNotEmpty()
}

@Serializable
private data class PlanProposalDto(
    val placements: List<PlanPlacementDto>,
    val unscheduled: List<UnscheduledTaskDto>,
)

@Serializable
private data class PlanPlacementDto(
    @SerialName("occurrence_id") val occurrenceId: String,
    val start: String,
    val end: String,
    val reason: String,
)

@Serializable
private data class UnscheduledTaskDto(
    @SerialName("occurrence_id") val occurrenceId: String,
    val reason: String,
)

private val proposalJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
}

/** Decode strict JSON into typed instants. Malformed or extra fields reject the entire response. */
internal fun decodePlanProposal(raw: String): PlanProposal {
    val dto = proposalJson.decodeFromString<PlanProposalDto>(raw)
    return PlanProposal(
        placements = dto.placements.map {
            PlanPlacement(it.occurrenceId, Instant.parse(it.start), Instant.parse(it.end), it.reason.trim())
        },
        unscheduled = dto.unscheduled.map { UnscheduledTask(it.occurrenceId, it.reason.trim()) },
    )
}

/**
 * Rechecks a remote plan against the current local source of truth. The result contains only
 * safe placements; rejected items remain visible in the unscheduled list and issues list.
 */
internal fun validatePlanProposal(context: PlanningContext, proposal: PlanProposal): PlanValidationResult {
    val tasksById = context.flexibleTasks.associateBy(FlexibleTask::occurrenceId)
    val occurrenceCounts = (proposal.placements.map(PlanPlacement::occurrenceId) +
        proposal.unscheduled.map(UnscheduledTask::occurrenceId)).groupingBy { it }.eachCount()
    val rejected = linkedMapOf<String, String>()
    val issues = mutableListOf<PlanIssue>()

    for (id in occurrenceCounts.keys - tasksById.keys) {
        issues += PlanIssue(id, "The assistant returned a task outside this plan.")
    }
    for ((id, count) in occurrenceCounts) {
        if (id in tasksById && count != 1) {
            rejected[id] = "The assistant returned this task more than once."
        }
    }
    for (task in context.flexibleTasks) {
        if (task.occurrenceId in context.completedOccurrenceIds) {
            rejected[task.occurrenceId] = "Completed tasks cannot be rescheduled."
        } else if ((occurrenceCounts[task.occurrenceId] ?: 0) == 0) {
            rejected[task.occurrenceId] = "The assistant did not return a placement or explanation for this task."
        }
    }

    val busyBlocks = context.hardBlocks + context.calendarCommitments + context.softBlocks
    val provisional = mutableListOf<PlanPlacement>()
    for (placement in proposal.placements) {
        val task = tasksById[placement.occurrenceId] ?: continue
        if (task.occurrenceId in rejected) continue
        val issue = validatePlacement(context, task, placement, busyBlocks)
        if (issue == null) provisional += placement
        else rejected[task.occurrenceId] = issue
    }

    val overlapping = mutableSetOf<String>()
    for (i in provisional.indices) {
        val left = provisional[i]
        for (j in i + 1 until provisional.size) {
            val right = provisional[j]
            if (overlaps(left.start, left.end, right.start, right.end)) {
                overlapping += left.occurrenceId
                overlapping += right.occurrenceId
            }
        }
    }
    overlapping.forEach { rejected[it] = "The proposal overlaps another task in this plan." }

    val explicitUnscheduled = proposal.unscheduled
        .filter { it.occurrenceId in tasksById && it.occurrenceId !in rejected }
        .associate { it.occurrenceId to cleanReason(it.reason) }
    val safePlacements = provisional
        .filter { it.occurrenceId !in rejected }
        .sortedWith(compareBy(PlanPlacement::start, PlanPlacement::occurrenceId))
    val safeUnscheduled = context.flexibleTasks
        .filter { it.occurrenceId in rejected || it.occurrenceId in explicitUnscheduled }
        .map { task ->
            UnscheduledTask(
                task.occurrenceId,
                rejected[task.occurrenceId] ?: explicitUnscheduled.getValue(task.occurrenceId),
            )
        }

    rejected.forEach { (id, reason) -> issues += PlanIssue(id, reason) }
    return PlanValidationResult(PlanProposal(safePlacements, safeUnscheduled), issues)
}

private fun validatePlacement(
    context: PlanningContext,
    task: FlexibleTask,
    placement: PlanPlacement,
    busyBlocks: List<ScheduleBlock>,
): String? {
    if (placement.end <= placement.start) return "The proposed time is invalid."
    val duration = Duration.between(placement.start, placement.end)
    if (duration != Duration.ofMinutes(task.durationMinutes.toLong())) {
        return "The proposed duration does not match the task."
    }
    if (placement.start < context.planningStart || placement.end > context.planningEnd) {
        return "The proposed time falls outside planning hours."
    }
    if (task.earliestStart?.let { placement.start < it } == true) {
        return "The proposed time is earlier than this task allows."
    }
    if (task.latestEnd?.let { placement.end > it } == true) {
        return "The proposed time ends later than this task allows."
    }
    if (task.deadlineAt?.let { placement.end > it } == true) {
        return "The proposed time is after this task's deadline."
    }
    if (busyBlocks.any { overlaps(placement.start, placement.end, it.start, it.end) }) {
        return "The proposed time overlaps a scheduled commitment."
    }
    return null
}

private fun overlaps(leftStart: Instant, leftEnd: Instant, rightStart: Instant, rightEnd: Instant): Boolean =
    leftStart < rightEnd && rightStart < leftEnd

private fun cleanReason(value: String): String =
    value.filterNot { it.isISOControl() }.replace(Regex("\\s+"), " ").trim().take(240)
        .ifBlank { "No feasible slot was available." }

/** Convenience overload for the current day's local schedule projection. */
internal fun validatePlanProposal(schedule: DaySchedule, proposal: PlanProposal): PlanValidationResult =
    validatePlanProposal(
        PlanningContext(
            date = schedule.date,
            zoneId = schedule.zoneId,
            planningStart = schedule.planningStart,
            planningEnd = schedule.planningEnd,
            hardBlocks = schedule.blocks.filter { it.scheduleType.name == "HARD" },
            calendarCommitments = schedule.blocks.filter { it.source.name == "GOOGLE_CALENDAR" },
            softBlocks = schedule.blocks.filter { it.scheduleType.name == "SOFT" && it.source.name != "GOOGLE_CALENDAR" },
            flexibleTasks = schedule.flexibleTasks,
            completedOccurrenceIds = schedule.completedOccurrenceIds,
        ),
        proposal,
    )
