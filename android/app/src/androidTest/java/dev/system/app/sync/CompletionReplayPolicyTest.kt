package dev.system.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompletionReplayPolicyTest {
    @Test
    fun classifiesAlreadyAppliedAndDeletedOccurrences() {
        assertEquals(
            CompletionReplayOutcome.ALREADY_APPLIED,
            CompletionReplayPolicy.classify(CompletionFailure(code = "ALREADY_APPLIED")),
        )
        assertEquals(
            CompletionReplayOutcome.PERMANENT_FAILURE,
            CompletionReplayPolicy.classify(CompletionFailure(code = "P0002", message = "Task occurrence not found")),
        )
    }

    @Test
    fun retainsRetryableNetworkAndServerFailures() {
        assertEquals(CompletionReplayOutcome.RETRYABLE, CompletionReplayPolicy.classify(IOException("offline")))
        assertEquals(CompletionReplayOutcome.RETRYABLE, CompletionReplayPolicy.classify(CompletionFailure(status = 429)))
        assertEquals(CompletionReplayOutcome.RETRYABLE, CompletionReplayPolicy.classify(CompletionFailure(status = 503)))
    }

    @Test
    fun separatesAuthenticationAndConflictOutcomes() {
        assertEquals(CompletionReplayOutcome.AUTH_REQUIRED, CompletionReplayPolicy.classify(CompletionFailure(status = 401)))
        assertEquals(CompletionReplayOutcome.CONFLICT, CompletionReplayPolicy.classify(CompletionFailure(status = 409)))
        assertEquals(CompletionReplayOutcome.CONFLICT, CompletionReplayPolicy.classify(CompletionFailure(code = "22023")))
        assertEquals(CompletionReplayOutcome.PERMANENT_FAILURE, CompletionReplayPolicy.classify(CompletionFailure(status = 422)))
    }

    @Test
    fun retryBackoffIsBoundedAndGrowsBetweenAttempts() {
        assertEquals(30_000L, CompletionReplayPolicy.retryDelayMillis(1))
        assertEquals(60_000L, CompletionReplayPolicy.retryDelayMillis(2))
        assertEquals(6 * 60 * 60 * 1000L, CompletionReplayPolicy.retryDelayMillis(40))
    }
}
