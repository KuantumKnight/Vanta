package dev.system.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ReplayErrorClassifierTest {
    @Test
    fun mapsStableSqlStatesWithoutParsingEnglishMessages() {
        assertEquals(ReplayOutcome.AUTH_REQUIRED, classifyReplayError(DatabaseError("28000")).outcome)
        assertEquals(ReplayOutcome.PERMANENT_FAILURE, classifyReplayError(DatabaseError("P0002")).outcome)
        assertEquals(ReplayOutcome.CONFLICT, classifyReplayError(DatabaseError("22023", "IDEMPOTENCY_CONFLICT")).outcome)
        assertEquals(ReplayOutcome.PERMANENT_FAILURE, classifyReplayError(DatabaseError("22023", "INVALID_MUTATION")).outcome)
    }

    @Test
    fun networkAndServerFailuresAreRetryable() {
        assertEquals(ReplayOutcome.RETRYABLE, classifyReplayError(IOException("connection refused")).outcome)
        assertEquals(ReplayOutcome.RETRYABLE, classifyReplayError(DatabaseError("40001")).outcome)
        assertEquals(ReplayOutcome.RETRYABLE, classifyReplayError(DatabaseError("08006")).outcome)
    }

    @Test
    fun mapsHttpAuthConflictAndTemporaryStatuses() {
        assertEquals(ReplayOutcome.AUTH_REQUIRED, classifyReplayError(HttpError(401)).outcome)
        assertEquals(ReplayOutcome.CONFLICT, classifyReplayError(HttpError(409)).outcome)
        assertEquals(ReplayOutcome.RETRYABLE, classifyReplayError(HttpError(429)).outcome)
        assertEquals(ReplayOutcome.RETRYABLE, classifyReplayError(HttpError(503)).outcome)
    }

    private class DatabaseError(val code: String, val detail: String? = null) : RuntimeException("opaque backend error")
    private class HttpError(val response: FakeResponse) : RuntimeException("opaque HTTP error")
    private class FakeResponse(val status: FakeStatus)
    private class FakeStatus(val value: Int)
}
