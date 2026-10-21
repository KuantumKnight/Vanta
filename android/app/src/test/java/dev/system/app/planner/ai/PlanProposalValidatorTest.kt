package dev.system.app.planner.ai

import dev.system.app.planner.FlexibleTask
import dev.system.app.planner.PlanPlacement
import dev.system.app.planner.PlanProposal
import dev.system.app.planner.PlanningContext
import dev.system.app.planner.ScheduleBlock
import dev.system.app.planner.SchedulePriority
import dev.system.app.planner.ScheduleSource
import dev.system.app.planner.ScheduleType
import dev.system.app.planner.UnscheduledTask
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanProposalValidatorTest {
    private val taskId = "a4d0cf4e-40d0-4c29-a31f-4ade74904134"
    private val otherTaskId = "6dccd433-d291-48be-8586-a11394f27e7b"
    private val start = Instant.parse("2026-10-21T10:00:00Z")

    private fun task(
        id: String = taskId,
        duration: Int = 60,
        deadline: Instant? = null,
    ) = FlexibleTask(
        occurrenceId = id,
        title = if (id == taskId) "TOC Revision" else "Project Work",
        durationMinutes = duration,
        priority = SchedulePriority.NORMAL,
        deadlineAt = deadline,
    )

    private fun context(tasks: List<FlexibleTask> = listOf(task()), blocks: List<ScheduleBlock> = emptyList()) =
        PlanningContext(
            date = LocalDate.parse("2026-10-21"),
            zoneId = ZoneId.of("Asia/Kolkata"),
            planningStart = Instant.parse("2026-10-21T08:00:00Z"),
            planningEnd = Instant.parse("2026-10-21T15:00:00Z"),
            hardBlocks = blocks.filter { it.scheduleType == ScheduleType.HARD },
            calendarCommitments = blocks.filter { it.source == ScheduleSource.GOOGLE_CALENDAR },
            softBlocks = blocks.filter { it.scheduleType == ScheduleType.SOFT && it.source != ScheduleSource.GOOGLE_CALENDAR },
            flexibleTasks = tasks,
            completedOccurrenceIds = emptySet(),
        )

    private fun placement(id: String = taskId, from: Instant = start, to: Instant = from.plusSeconds(3600)) =
        PlanPlacement(id, from, to, "Fits a free hour")

    @Test
    fun `accepts feasible placement`() {
        val result = validatePlanProposal(context(), PlanProposal(listOf(placement()), emptyList()))
        assertEquals(listOf(placement()), result.proposal.placements)
        assertTrue(result.proposal.unscheduled.isEmpty())
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `rejects placement overlapping hard block`() {
        val hard = ScheduleBlock(
            id = "class",
            source = ScheduleSource.VANTA_TASK,
            title = "Operating Systems Class",
            start = start.plusSeconds(1800),
            end = start.plusSeconds(5400),
            scheduleType = ScheduleType.HARD,
            editable = false,
        )
        val result = validatePlanProposal(context(blocks = listOf(hard)), PlanProposal(listOf(placement()), emptyList()))
        assertTrue(result.proposal.placements.isEmpty())
        assertEquals("The proposed time overlaps a scheduled commitment.", result.proposal.unscheduled.single().reason)
    }

    @Test
    fun `rejects placement after task deadline`() {
        val deadline = start.plusSeconds(1800)
        val result = validatePlanProposal(context(tasks = listOf(task(deadline = deadline))), PlanProposal(listOf(placement()), emptyList()))
        assertTrue(result.proposal.placements.isEmpty())
        assertTrue(result.proposal.unscheduled.single().reason.contains("deadline"))
    }

    @Test
    fun `rejects duration mismatch including fractional minute`() {
        val fractional = placement(to = start.plusSeconds(3630))
        val result = validatePlanProposal(context(), PlanProposal(listOf(fractional), emptyList()))
        assertTrue(result.proposal.placements.isEmpty())
        assertTrue(result.proposal.unscheduled.single().reason.contains("duration"))
    }

    @Test
    fun `drops unknown occurrence and accepts a valid sibling`() {
        val unknown = "89064b49-3b19-43e8-ae4f-4b57a1e2a657"
        val result = validatePlanProposal(
            context(tasks = listOf(task(), task(otherTaskId))),
            PlanProposal(listOf(placement(unknown), placement(taskId), placement(otherTaskId, start.plusSeconds(7200))), emptyList()),
        )
        assertEquals(setOf(taskId, otherTaskId), result.proposal.placements.map { it.occurrenceId }.toSet())
        assertTrue(result.issues.any { it.occurrenceId == unknown })
    }

    @Test
    fun `rejects every duplicate occurrence placement`() {
        val result = validatePlanProposal(context(), PlanProposal(listOf(placement(), placement(from = start.plusSeconds(7200))), emptyList()))
        assertTrue(result.proposal.placements.isEmpty())
        assertTrue(result.proposal.unscheduled.single().reason.contains("more than once"))
    }

    @Test
    fun `partial failure keeps valid item and explains the rejected one`() {
        val second = task(otherTaskId, duration = 90, deadline = start.plusSeconds(3600))
        val result = validatePlanProposal(
            context(tasks = listOf(task(), second)),
            PlanProposal(
                placements = listOf(placement(taskId), placement(otherTaskId, start.plusSeconds(3600), start.plusSeconds(9000))),
                unscheduled = emptyList(),
            ),
        )
        assertEquals(listOf(taskId), result.proposal.placements.map { it.occurrenceId })
        assertEquals(otherTaskId, result.proposal.unscheduled.single().occurrenceId)
        assertTrue(result.proposal.unscheduled.single().reason.contains("deadline"))
        assertTrue(result.isApplicable)
    }

    @Test
    fun `empty day returns empty safe proposal`() {
        val result = validatePlanProposal(context(tasks = emptyList()), PlanProposal(emptyList(), emptyList()))
        assertTrue(result.proposal.placements.isEmpty())
        assertTrue(result.proposal.unscheduled.isEmpty())
        assertFalse(result.isApplicable)
    }

    @Test(expected = Exception::class)
    fun `malformed structured response is rejected`() {
        decodePlanProposal("""{"placements":[{"occurrence_id":"$taskId","start":"not-a-time","end":"$start","reason":"x"}],"unscheduled":[]}""")
    }

    @Test
    fun `completed work is never placed`() {
        val completedContext = context().copy(completedOccurrenceIds = setOf(taskId))
        val result = validatePlanProposal(completedContext, PlanProposal(listOf(placement()), emptyList()))
        assertTrue(result.proposal.placements.isEmpty())
        assertTrue(result.proposal.unscheduled.single().reason.contains("Completed"))
    }
}
