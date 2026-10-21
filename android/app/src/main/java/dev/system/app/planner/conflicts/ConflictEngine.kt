package dev.system.app.planner.conflicts

import dev.system.app.planner.CandidateSlot
import dev.system.app.planner.ConflictType
import dev.system.app.planner.DaySchedule
import dev.system.app.planner.FlexibleTask
import dev.system.app.planner.PlanPlacement
import dev.system.app.planner.PlanProposal
import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.ScheduleConflict
import dev.system.app.planner.SchedulePriority
import dev.system.app.planner.ScheduleType
import dev.system.app.planner.UnscheduledTask
import dev.system.app.planner.core.findCandidateSlots
import dev.system.app.planner.core.canPlace
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Finds pairwise timed conflicts plus deterministic feasibility issues for flexible tasks.
 * Calendar blocks are ordinary HARD blocks here; their Google source only affects display text.
 */
fun detectScheduleConflicts(
    schedule: DaySchedule,
    now: Instant = schedule.planningStart,
): List<ScheduleConflict> {
    val blocks = schedule.blocks.filter { it.end > it.start }
        .sortedWith(compareBy<ScheduleBlock> { it.start }.thenBy { it.end }.thenBy { it.id })
    val conflicts = mutableListOf<ScheduleConflict>()

    for (leftIndex in blocks.indices) {
        val left = blocks[leftIndex]
        for (rightIndex in leftIndex + 1 until blocks.size) {
            val right = blocks[rightIndex]
            if (right.start >= left.end) break
            val overlapStart = maxOf(left.start, right.start)
            val overlapEnd = minOf(left.end, right.end)
            if (overlapStart >= overlapEnd || overlapEnd <= now) continue

            val type = when {
                left.scheduleType == ScheduleType.HARD && right.scheduleType == ScheduleType.HARD -> ConflictType.HARD_HARD
                left.scheduleType == ScheduleType.HARD || right.scheduleType == ScheduleType.HARD -> ConflictType.HARD_SOFT
                left.scheduleType == ScheduleType.SOFT && right.scheduleType == ScheduleType.SOFT -> ConflictType.SOFT_SOFT
                else -> continue
            }
            conflicts += ScheduleConflict(
                type = type,
                blockIds = listOf(left.id, right.id),
                start = overlapStart,
                end = overlapEnd,
                message = "${left.title} overlaps ${right.title}.",
            )
        }
    }

    val occupied = schedule.blocks
    for (task in schedule.flexibleTasks.sortedBy(FlexibleTask::occurrenceId)) {
        if (task.occurrenceId in schedule.completedOccurrenceIds) continue
        val slots = findCandidateSlots(
            task = task,
            planningStart = schedule.planningStart,
            planningEnd = schedule.planningEnd,
            blocks = occupied,
            now = now,
        )
        if (slots.isNotEmpty()) continue

        val slotsWithoutDeadline = if (task.deadlineAt == null) emptyList() else findCandidateSlots(
            task = task.copy(deadlineAt = null),
            planningStart = schedule.planningStart,
            planningEnd = schedule.planningEnd,
            blocks = occupied,
            now = now,
        )
        if (task.deadlineAt == null || slotsWithoutDeadline.isEmpty()) {
            val reasonStart = maxOf(schedule.planningStart, task.earliestStart ?: schedule.planningStart, now)
            val clippedStart = minOf(reasonStart, schedule.planningEnd)
            conflicts += ScheduleConflict(
                type = ConflictType.INSUFFICIENT_SPACE,
                blockIds = listOf(task.occurrenceId),
                start = clippedStart,
                end = schedule.planningEnd,
                message = "No available ${task.durationMinutes}-minute slot fits ${task.title}.",
            )
        }
        if (task.deadlineAt != null && task.deadlineAt <= schedule.planningEnd) {
            val clippedDeadline = task.deadlineAt.coerceIn(schedule.planningStart, schedule.planningEnd)
            val riskStart = if (clippedDeadline > now) maxOf(schedule.planningStart, now) else clippedDeadline
            conflicts += ScheduleConflict(
                type = ConflictType.DEADLINE_RISK,
                blockIds = listOf(task.occurrenceId),
                start = riskStart,
                end = maxOf(riskStart, clippedDeadline),
                message = "${task.title} has no feasible slot before its deadline.",
            )
        }
    }
    return conflicts
}

/** Suggests alternate slots for one movable SOFT block, excluding its current placement. */
fun findConflictAlternatives(
    schedule: DaySchedule,
    blockId: String,
    now: Instant = schedule.planningStart,
    maxCandidates: Int = 3,
): List<CandidateSlot> {
    val block = schedule.blocks.firstOrNull { it.id == blockId } ?: return emptyList()
    if (block.scheduleType != ScheduleType.SOFT || !block.editable || block.start < now ||
        block.id in schedule.completedOccurrenceIds
    ) return emptyList()
    val durationMinutes = wholeDurationMinutes(block) ?: return emptyList()

    val existingTask = schedule.flexibleTasks.firstOrNull { it.occurrenceId == block.id }
    val task = existingTask?.copy(
        title = block.title,
        durationMinutes = durationMinutes,
        priority = existingTask.priority,
        deadlineAt = existingTask.deadlineAt ?: block.deadlineAt,
        earliestStart = existingTask.earliestStart ?: block.earliestStart,
        latestEnd = existingTask.latestEnd ?: block.latestEnd,
        preferredStart = existingTask.preferredStart ?: block.preferredStart ?: block.start,
    ) ?: FlexibleTask(
        occurrenceId = block.id,
        title = block.title,
        durationMinutes = durationMinutes,
        priority = block.priority,
        deadlineAt = block.deadlineAt,
        earliestStart = block.earliestStart,
        latestEnd = block.latestEnd,
        preferredStart = block.preferredStart ?: block.start,
    )
    val alternatives = findCandidateSlots(
        task = task,
        planningStart = schedule.planningStart,
        planningEnd = schedule.planningEnd,
        blocks = schedule.blocks.filterNot { it.id == block.id },
        now = now,
        maxCandidates = maxCandidates + 1,
    ).filterNot { it.start == block.start }
    return alternatives.take(maxCandidates.coerceAtLeast(0))
}

/**
 * Replans only future unfinished SOFT blocks and FLEXIBLE tasks. HARD blocks, completed rows,
 * already-started/past blocks, and read-only blocks stay fixed. The returned proposal has no writes.
 */
fun proposeRemainingDayReplan(
    schedule: DaySchedule,
    now: Instant,
    snapMinutes: Int = 15,
): PlanProposal {
    val movableSoftBlocks = schedule.blocks.filter { block ->
        block.scheduleType == ScheduleType.SOFT && block.editable && block.start >= now &&
            block.id !in schedule.completedOccurrenceIds
    }
    val movableIds = movableSoftBlocks.mapTo(mutableSetOf(), ScheduleBlock::id)
    val fixedBlocks = schedule.blocks.filterNot { it.id in movableIds }

    val invalidMovableSoftBlocks = movableSoftBlocks.filter { block ->
        wholeDurationMinutes(block) == null
    }.sortedBy(ScheduleBlock::id)
    val movableSoftTasks = movableSoftBlocks.mapNotNull { block ->
        val duration = wholeDurationMinutes(block) ?: return@mapNotNull null
        val task = schedule.flexibleTasks.firstOrNull { it.occurrenceId == block.id }
        (task ?: FlexibleTask(block.id, block.title, duration.toInt())).copy(
            title = block.title,
            durationMinutes = duration,
            priority = task?.priority ?: block.priority,
            deadlineAt = task?.deadlineAt ?: block.deadlineAt,
            earliestStart = task?.earliestStart ?: block.earliestStart,
            latestEnd = task?.latestEnd ?: block.latestEnd,
            preferredStart = task?.preferredStart ?: block.preferredStart ?: block.start,
        )
    }
    val movableFlexibleTasks = schedule.flexibleTasks.filter { task ->
        task.occurrenceId !in schedule.completedOccurrenceIds &&
            task.occurrenceId !in movableIds &&
            // A task tied to a fixed, past, completed, hard, or read-only block must stay there.
            schedule.blocks.none { it.id == task.occurrenceId }
    }
    val tasks = (movableSoftTasks + movableFlexibleTasks)
        .distinctBy(FlexibleTask::occurrenceId)
        .sortedWith(
            compareByDescending<FlexibleTask> { priorityOrder(it.priority) }
                .thenBy { it.deadlineAt ?: Instant.MAX }
                .thenBy { it.preferredStart ?: Instant.MAX }
                .thenBy { it.occurrenceId },
        )

    val placements = mutableListOf<PlanPlacement>()
    val unscheduled = invalidMovableSoftBlocks.map { block ->
        UnscheduledTask(block.id, "This task needs a valid 5–1440 minute duration before it can be replanned.")
    }.toMutableList()
    val occupied = fixedBlocks.toMutableList()
    for (task in tasks) {
        val candidate = findCandidateSlots(
            task = task,
            planningStart = schedule.planningStart,
            planningEnd = schedule.planningEnd,
            blocks = occupied,
            now = now,
            snapMinutes = snapMinutes,
            maxCandidates = 1,
        ).firstOrNull()
        if (candidate == null) {
            val reason = if (task.deadlineAt != null) {
                "No available slot fits before the deadline."
            } else {
                "No available slot fits the remaining time today."
            }
            unscheduled += UnscheduledTask(task.occurrenceId, reason)
            continue
        }
        val originalStart = movableSoftBlocks.firstOrNull { it.id == task.occurrenceId }?.start
        val reason = originalStart?.let { "Moved from ${it.atZone(schedule.zoneId).format(clockFormatter)} to fit the remaining day." }
            ?: "Placed in the best available slot for the remaining day."
        placements += PlanPlacement(task.occurrenceId, candidate.start, candidate.end, reason)
        occupied += ScheduleBlock(
            id = task.occurrenceId,
            source = movableSoftBlocks.firstOrNull { it.id == task.occurrenceId }?.source
                ?: dev.system.app.planner.ScheduleSource.VANTA_TASK,
            title = task.title,
            start = candidate.start,
            end = candidate.end,
            scheduleType = ScheduleType.SOFT,
            editable = false,
        )
    }
    return PlanProposal(placements, unscheduled)
}

/** Independently checks a reviewed local replan against the current schedule before applying it. */
fun validateDayReplanProposal(
    schedule: DaySchedule,
    proposal: PlanProposal,
    now: Instant,
): Boolean {
    val movableSoft = schedule.blocks.filter { block ->
        block.scheduleType == ScheduleType.SOFT && block.editable && block.start >= now &&
            block.id !in schedule.completedOccurrenceIds && wholeDurationMinutes(block) != null
    }
    val movableIds = movableSoft.mapTo(mutableSetOf(), ScheduleBlock::id)
    val eligible = linkedMapOf<String, FlexibleTask>()
    movableSoft.forEach { block ->
        val duration = wholeDurationMinutes(block) ?: return false
        val metadata = schedule.flexibleTasks.firstOrNull { it.occurrenceId == block.id }
        eligible[block.id] = (metadata ?: FlexibleTask(block.id, block.title, duration)).copy(
            title = block.title,
            durationMinutes = duration,
            priority = metadata?.priority ?: block.priority,
            deadlineAt = metadata?.deadlineAt ?: block.deadlineAt,
            earliestStart = metadata?.earliestStart ?: block.earliestStart,
            latestEnd = metadata?.latestEnd ?: block.latestEnd,
            preferredStart = metadata?.preferredStart ?: block.preferredStart ?: block.start,
        )
    }
    schedule.flexibleTasks.filter { task ->
        task.occurrenceId !in schedule.completedOccurrenceIds && task.occurrenceId !in movableIds &&
            schedule.blocks.none { it.id == task.occurrenceId }
    }.forEach { eligible[it.occurrenceId] = it }

    val ids = proposal.placements.map(PlanPlacement::occurrenceId) + proposal.unscheduled.map(UnscheduledTask::occurrenceId)
    if (ids.size != ids.distinct().size || ids.toSet() != eligible.keys) return false
    val occupied = schedule.blocks.filterNot { it.id in movableIds }.toMutableList()
    for (placement in proposal.placements.sortedWith(compareBy(PlanPlacement::start, PlanPlacement::occurrenceId))) {
        val task = eligible[placement.occurrenceId] ?: return false
        if (placement.end <= placement.start || placement.start < now || placement.start < schedule.planningStart ||
            placement.end > schedule.planningEnd ||
            placement.end != placement.start.plusSeconds(task.durationMinutes * 60L) ||
            task.earliestStart?.let { placement.start < it } == true ||
            task.latestEnd?.let { placement.end > it } == true ||
            task.deadlineAt?.let { placement.end > it } == true ||
            !canPlace(task, placement.start, schedule.planningStart, schedule.planningEnd, occupied)
        ) return false
        occupied += ScheduleBlock(
            id = placement.occurrenceId,
            source = movableSoft.firstOrNull { it.id == placement.occurrenceId }?.source
                ?: dev.system.app.planner.ScheduleSource.VANTA_TASK,
            title = task.title,
            start = placement.start,
            end = placement.end,
            scheduleType = ScheduleType.SOFT,
            editable = false,
        )
    }
    return true
}

/** Returns a local overrun notice and the future blocks intersected by the overrun window. */
fun detectFocusOverrun(
    schedule: DaySchedule,
    occurrenceId: String,
    plannedEnd: Instant,
    actualEnd: Instant,
): ScheduleConflict? {
    if (actualEnd <= plannedEnd) return null
    val affected = schedule.blocks.asSequence()
        .filter { it.id != occurrenceId && it.end > plannedEnd && it.start < actualEnd }
        // Show tasks that have not started yet before tasks already in progress.
        .sortedWith(compareByDescending<ScheduleBlock> { it.start >= plannedEnd }.thenBy { it.start }.thenBy { it.id })
        .map(ScheduleBlock::id)
        .toList()
    val delay = Duration.between(plannedEnd, actualEnd).toMinutes()
    val ids = listOf(occurrenceId) + affected
    return ScheduleConflict(
        type = ConflictType.OVERRUN,
        blockIds = ids,
        start = plannedEnd,
        end = actualEnd,
        message = "You finished ${delay} min later than planned; ${affected.size} later task(s) may be affected.",
    )
}

private fun priorityOrder(priority: SchedulePriority): Int = when (priority) {
    SchedulePriority.HIGH -> 3
    SchedulePriority.NORMAL -> 2
    SchedulePriority.LOW -> 1
}

private fun wholeDurationMinutes(block: ScheduleBlock): Int? {
    val duration = Duration.between(block.start, block.end)
    if (duration.nano != 0 || duration.seconds <= 0 || duration.seconds % 60 != 0L) return null
    val minutes = duration.seconds / 60
    return minutes.takeIf { it in 5L..1440L }?.toInt()
}

private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
