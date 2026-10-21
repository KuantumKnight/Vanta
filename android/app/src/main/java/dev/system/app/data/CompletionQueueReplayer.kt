package dev.system.app.data

import kotlinx.coroutines.CancellationException

data class ReplaySummary(
    val nextRetryAt: Long? = null,
    val authRequired: Boolean = false,
    val permanentFailures: Int = 0,
    val conflicts: Int = 0,
)

internal class CompletionQueueReplayer(
    private val dao: SystemDao,
    private val completeOnServer: suspend (PendingCompletion) -> ReplayResult,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun replay(userId: String): ReplaySummary {
        var earliestRetryAt: Long? = null
        var permanentFailures = 0
        var conflicts = 0

        for (item in dao.dueCompletions(userId, now())) {
            val attemptTime = now()
            val result = try {
                completeOnServer(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                classifyReplayError(error)
            }

            when (result.outcome) {
                ReplayOutcome.SUCCESS, ReplayOutcome.ALREADY_APPLIED -> {
                    dao.removePendingCompletion(userId, item.idempotencyKey)
                }
                ReplayOutcome.RETRYABLE -> {
                    val retryCount = item.retryCount + 1
                    val nextAttempt = attemptTime + retryDelayMs(retryCount)
                    dao.markCompletionRetry(
                        userId,
                        item.idempotencyKey,
                        retryCount,
                        attemptTime,
                        nextAttempt,
                        result.errorCode ?: "TEMPORARY_FAILURE",
                        result.message ?: "Could not sync completion yet.",
                    )
                    earliestRetryAt = minOf(earliestRetryAt ?: nextAttempt, nextAttempt)
                }
                ReplayOutcome.AUTH_REQUIRED -> {
                    dao.markCompletionRetry(
                        userId,
                        item.idempotencyKey,
                        item.retryCount,
                        attemptTime,
                        Long.MAX_VALUE,
                        "AUTH_REQUIRED",
                        result.message ?: "Sign in again to sync saved completions.",
                    )
                    return ReplaySummary(earliestRetryAt, authRequired = true, permanentFailures, conflicts)
                }
                ReplayOutcome.PERMANENT_FAILURE -> {
                    dao.markCompletionTerminal(
                        userId,
                        item.idempotencyKey,
                        "dead_letter",
                        attemptTime,
                        result.errorCode ?: "PERMANENT_FAILURE",
                        result.message ?: "This completion needs attention.",
                    )
                    permanentFailures++
                }
                ReplayOutcome.CONFLICT -> {
                    dao.markCompletionTerminal(
                        userId,
                        item.idempotencyKey,
                        "conflict",
                        attemptTime,
                        result.errorCode ?: "CONFLICT",
                        result.message ?: "This completion conflicts with server state.",
                    )
                    conflicts++
                }
            }
        }
        return ReplaySummary(earliestRetryAt, permanentFailures = permanentFailures, conflicts = conflicts)
    }

    private fun retryDelayMs(retryCount: Int): Long {
        val exponent = (retryCount - 1).coerceIn(0, 10)
        return (30_000L * (1L shl exponent)).coerceAtMost(6 * 60 * 60 * 1000L)
    }
}
