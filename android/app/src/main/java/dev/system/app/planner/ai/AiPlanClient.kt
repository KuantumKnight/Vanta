package dev.system.app.planner.ai

import dev.system.app.planner.FlexibleTask
import dev.system.app.planner.PlanProposal
import dev.system.app.planner.PlanningContext
import dev.system.app.planner.ScheduleBlock
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.IOException

/** A user-facing, non-sensitive failure from the optional planner service. */
internal class AiPlanningUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Calls the authenticated plan-day Edge Function. The access token is obtained for each call
 * and used only as a request header; it is never persisted or included in the payload.
 */
internal class AiPlanClient(
    supabaseUrl: String,
    private val publishableKey: String,
    private val accessToken: suspend () -> String?,
    private val httpClient: HttpClient = HttpClient(Android),
) : Closeable {
    private val endpoint = "${supabaseUrl.trimEnd('/')}/functions/v1/plan-day"

    suspend fun propose(context: PlanningContext): PlanValidationResult {
        val payload = context.toPayloadOrThrow()
        val token = accessToken()?.takeIf(String::isNotBlank)
            ?: throw AiPlanningUnavailable("Sign in again to plan your day.")
        val response = try {
            httpClient.post(endpoint) {
                header("apikey", publishableKey)
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(plannerJson.encodeToString(payload))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw AiPlanningUnavailable("Planning assistant unavailable offline. Your schedule is still fully usable.", failure)
        }

        if (response.status.value !in 200..299) {
            throw when (response.status.value) {
                401, 403 -> AiPlanningUnavailable("Sign in again to plan your day.")
                400, 409 -> AiPlanningUnavailable("Your schedule changed. Refresh and try again.")
                else -> AiPlanningUnavailable("Planning assistant unavailable. Your schedule is still usable.")
            }
        }

        val proposal = try {
            decodePlanProposal(response.bodyAsText())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw AiPlanningUnavailable("Planning assistant returned an invalid plan. Try again.", failure)
        }
        return validatePlanProposal(context, proposal)
    }

    override fun close() = httpClient.close()
}

private val plannerJson = Json {
    encodeDefaults = true
    explicitNulls = true
    isLenient = false
}

@Serializable
private data class DayPlanRequestDto(
    val date: String,
    val timezone: String,
    @SerialName("planning_start") val planningStart: String,
    @SerialName("planning_end") val planningEnd: String,
    @SerialName("busy_blocks") val busyBlocks: List<BusyBlockDto>,
    @SerialName("flexible_tasks") val flexibleTasks: List<FlexibleTaskDto>,
    @SerialName("completed_occurrence_ids") val completedOccurrenceIds: List<String>,
)

@Serializable
private data class BusyBlockDto(
    val title: String,
    val start: String,
    val end: String,
)

@Serializable
private data class FlexibleTaskDto(
    @SerialName("occurrence_id") val occurrenceId: String,
    val title: String,
    @SerialName("duration_minutes") val durationMinutes: Int,
    val priority: String,
    @SerialName("deadline_at") val deadlineAt: String?,
    @SerialName("earliest_start") val earliestStart: String?,
    @SerialName("latest_end") val latestEnd: String?,
    @SerialName("preferred_start") val preferredStart: String?,
)

private fun PlanningContext.toPayloadOrThrow(): DayPlanRequestDto {
    if (planningEnd <= planningStart) throw AiPlanningUnavailable("The planning window is invalid.")
    if (flexibleTasks.size > 40) throw AiPlanningUnavailable("Plan up to 40 tasks at a time.")
    if ((hardBlocks.size + calendarCommitments.size + softBlocks.size) > 120) {
        throw AiPlanningUnavailable("The day has too many commitments to plan at once.")
    }
    val tasks = flexibleTasks.map(FlexibleTask::toDto)
    if (tasks.any { !UUID_PATTERN.matches(it.occurrenceId) }) {
        throw AiPlanningUnavailable("Sync new tasks before asking the assistant to plan them.")
    }
    val busyBlocks = (hardBlocks + calendarCommitments + softBlocks)
        .map(ScheduleBlock::toDto)
    return DayPlanRequestDto(
        date = date.toString(),
        timezone = zoneId.id,
        planningStart = planningStart.toString(),
        planningEnd = planningEnd.toString(),
        busyBlocks = busyBlocks,
        flexibleTasks = tasks,
        completedOccurrenceIds = completedOccurrenceIds.filter(UUID_PATTERN::matches).toList(),
    )
}

private fun FlexibleTask.toDto(): FlexibleTaskDto {
    if (durationMinutes !in 5..1440) {
        throw AiPlanningUnavailable("Set a duration before planning this task.")
    }
    return FlexibleTaskDto(
        occurrenceId = occurrenceId,
        title = title.take(200),
        durationMinutes = durationMinutes,
        priority = priority.name,
        deadlineAt = deadlineAt?.toString(),
        earliestStart = earliestStart?.toString(),
        latestEnd = latestEnd?.toString(),
        preferredStart = preferredStart?.toString(),
    )
}

private fun ScheduleBlock.toDto(): BusyBlockDto = BusyBlockDto(
    title = if (source.name == "GOOGLE_CALENDAR") "busy: ${title.take(150)}" else title.take(160),
    start = start.toString(),
    end = end.toString(),
)

private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
