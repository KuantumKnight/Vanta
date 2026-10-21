package dev.system.app.planner.core

import dev.system.app.planner.CandidateSlot
import dev.system.app.planner.FlexibleTask
import dev.system.app.planner.FreeInterval
import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.SchedulePriority
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Builds a local-time day window as instants, respecting zone changes and DST. */
fun localPlanningWindow(
    date: LocalDate,
    zoneId: ZoneId,
    startTime: LocalTime,
    endTime: LocalTime,
): FreeInterval {
    require(endTime > startTime) { "Planning window end must be after its start on the same day." }
    val start = date.atTime(startTime).atZone(zoneId).toInstant()
    val end = date.atTime(endTime).atZone(zoneId).toInstant()
    require(end > start) { "Planning window must have positive duration." }
    return FreeInterval(start, end)
}

/**
 * Returns the unoccupied portions of [planningStart, planningEnd). Blocks are clipped to the
 * bounds, invalid/zero-length blocks are ignored, and overlapping or touching blocks are merged.
 */
fun findFreeIntervals(
    planningStart: Instant,
    planningEnd: Instant,
    blocks: List<ScheduleBlock>,
): List<FreeInterval> {
    if (planningEnd <= planningStart) return emptyList()

    val occupied = blocks.asSequence()
        .filter { it.end > it.start }
        .mapNotNull { block ->
            val start = maxOf(block.start, planningStart)
            val end = minOf(block.end, planningEnd)
            if (end > start) FreeInterval(start, end) else null
        }
        .sortedWith(compareBy<FreeInterval> { it.start }.thenBy { it.end })
        .toList()

    if (occupied.isEmpty()) return listOf(FreeInterval(planningStart, planningEnd))

    val merged = mutableListOf<FreeInterval>()
    var currentStart = occupied.first().start
    var currentEnd = occupied.first().end
    for (interval in occupied.drop(1)) {
        if (interval.start <= currentEnd) {
            if (interval.end > currentEnd) currentEnd = interval.end
        } else {
            merged += FreeInterval(currentStart, currentEnd)
            currentStart = interval.start
            currentEnd = interval.end
        }
    }
    merged += FreeInterval(currentStart, currentEnd)

    val free = mutableListOf<FreeInterval>()
    var cursor = planningStart
    for (interval in merged) {
        if (interval.start > cursor) free += FreeInterval(cursor, interval.start)
        if (interval.end > cursor) cursor = interval.end
    }
    if (cursor < planningEnd) free += FreeInterval(cursor, planningEnd)
    return free
}

/** Checks task duration, planning bounds, task constraints, and all supplied blocking intervals. */
fun canPlace(
    task: FlexibleTask,
    start: Instant,
    planningStart: Instant,
    planningEnd: Instant,
    blocks: List<ScheduleBlock>,
): Boolean {
    if (task.durationMinutes !in 5..(24 * 60)) return false
    if (planningEnd <= planningStart || start < planningStart) return false
    val end = runCatching { start.plus(task.durationMinutes.toLong(), ChronoUnit.MINUTES) }.getOrNull()
        ?: return false
    if (end > planningEnd) return false
    if (task.earliestStart?.let { start < it } == true) return false
    if (task.latestEnd?.let { end > it } == true) return false
    if (task.deadlineAt?.let { end > it } == true) return false
    return blocks.none { block -> block.end > block.start && start < block.end && end > block.start }
}

/**
 * Produces deterministic, duration-preserving slots. Only starts aligned to the requested
 * minute grid are generated; task constraints can further restrict the interval.
 */
fun findCandidateSlots(
    task: FlexibleTask,
    planningStart: Instant,
    planningEnd: Instant,
    blocks: List<ScheduleBlock>,
    now: Instant = planningStart,
    snapMinutes: Int = 15,
    maxCandidates: Int = 64,
): List<CandidateSlot> {
    if (snapMinutes !in 1..60 || maxCandidates <= 0 || task.durationMinutes !in 5..(24 * 60)) {
        return emptyList()
    }
    val effectiveEnd = listOfNotNull(planningEnd, task.deadlineAt, task.latestEnd).minOrNull() ?: planningEnd
    val effectiveStart = listOfNotNull(planningStart, task.earliestStart, now).maxOrNull() ?: planningStart
    if (effectiveEnd <= effectiveStart) return emptyList()

    val free = findFreeIntervals(effectiveStart, effectiveEnd, blocks)
    val slots = mutableListOf<CandidateSlot>()
    for (interval in free) {
        if (interval.durationMinutes < task.durationMinutes) continue
        var candidate = ceilToStep(interval.start, snapMinutes)
        while (candidate.plus(task.durationMinutes.toLong(), ChronoUnit.MINUTES) <= interval.end) {
            val end = candidate.plus(task.durationMinutes.toLong(), ChronoUnit.MINUTES)
            if (canPlace(task, candidate, planningStart, planningEnd, blocks)) {
                slots += scoreCandidate(task, candidate, end, interval)
            }
            candidate = candidate.plus(snapMinutes.toLong(), ChronoUnit.MINUTES)
        }
    }
    return slots.sortedWith(
        compareByDescending<CandidateSlot> { it.score }
            .thenBy { slot -> task.preferredStart?.let { abs(ChronoUnit.MINUTES.between(it, slot.start)) } ?: 0L }
            .thenBy { it.start },
    )
        .take(maxCandidates)
}

/** Transparent weighted score: priority, deadline slack, preferred-time distance, and gap fragmentation. */
fun scoreCandidate(
    task: FlexibleTask,
    start: Instant,
    end: Instant,
    freeInterval: FreeInterval,
): CandidateSlot {
    val reasons = mutableListOf<String>()
    val priorityScore = when (task.priority) {
        SchedulePriority.HIGH -> 300
        SchedulePriority.NORMAL -> 200
        SchedulePriority.LOW -> 100
    }
    reasons += when (task.priority) {
        SchedulePriority.HIGH -> "High priority (+300)"
        SchedulePriority.NORMAL -> "Normal priority (+200)"
        SchedulePriority.LOW -> "Low priority (+100)"
    }

    val slackScore = task.deadlineAt?.let { deadline ->
        val slack = ChronoUnit.MINUTES.between(end, deadline).coerceAtLeast(0)
        when {
            slack <= 60 -> 120
            slack <= 4 * 60 -> 70
            slack <= 12 * 60 -> 30
            else -> 0
        }
    } ?: 0
    if (task.deadlineAt != null) reasons += "Deadline urgency (+$slackScore)"

    val preferenceScore = task.preferredStart?.let { preferred ->
        val distanceMinutes = abs(ChronoUnit.MINUTES.between(preferred, start))
        (120 - (distanceMinutes / 15) * 5).coerceAtLeast(0).toInt()
    } ?: 0
    if (task.preferredStart != null) reasons += "Preferred-time fit (+$preferenceScore)"

    val leftGap = ChronoUnit.MINUTES.between(freeInterval.start, start).coerceAtLeast(0)
    val rightGap = ChronoUnit.MINUTES.between(end, freeInterval.end).coerceAtLeast(0)
    val splitPenalty = if (leftGap > 0 && rightGap > 0) 20 else 0
    val tinyGapPenalty = listOf(leftGap, rightGap).count { it in 1L..29L } * 30
    val fragmentationScore = (60 - splitPenalty - tinyGapPenalty).coerceAtLeast(0)
    reasons += "Gap fragmentation (+$fragmentationScore)"

    val total = priorityScore + slackScore + preferenceScore + fragmentationScore
    return CandidateSlot(task.occurrenceId, start, end, total, reasons)
}

private fun ceilToStep(instant: Instant, stepMinutes: Int): Instant {
    val minuteFloor = instant.truncatedTo(ChronoUnit.MINUTES)
    val remainderSeconds = ChronoUnit.SECONDS.between(minuteFloor, instant)
    val minuteCeiling = if (remainderSeconds > 0) minuteFloor.plus(1, ChronoUnit.MINUTES) else minuteFloor
    val epochMinutes = ChronoUnit.MINUTES.between(Instant.EPOCH, minuteCeiling)
    val remainder = Math.floorMod(epochMinutes, stepMinutes.toLong())
    return if (remainder == 0L) minuteCeiling else minuteCeiling.plus(stepMinutes - remainder, ChronoUnit.MINUTES)
}
