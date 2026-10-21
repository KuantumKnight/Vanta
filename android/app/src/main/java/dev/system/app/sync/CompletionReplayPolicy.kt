package dev.system.app.sync

import io.ktor.client.plugins.ResponseException
import java.io.IOException

internal enum class CompletionReplayOutcome {
    SUCCESS,
    ALREADY_APPLIED,
    RETRYABLE,
    AUTH_REQUIRED,
    PERMANENT_FAILURE,
    CONFLICT,
}

internal data class CompletionFailure(
    val status: Int? = null,
    val code: String? = null,
    val message: String? = null,
    val networkFailure: Boolean = false,
)

internal object CompletionReplayPolicy {
    fun classify(error: Throwable): CompletionReplayOutcome = classify(describe(error))

    fun classify(failure: CompletionFailure): CompletionReplayOutcome {
        val code = failure.code?.uppercase()
        val message = failure.message.orEmpty().lowercase()

        if (code == "ALREADY_APPLIED" || message.contains("already completed") || message.contains("already applied")) {
            return CompletionReplayOutcome.ALREADY_APPLIED
        }
        if (failure.status == 401 || code == "AUTH_REQUIRED" || code == "28000") {
            return CompletionReplayOutcome.AUTH_REQUIRED
        }
        if (failure.status == 408 || failure.status == 425 || failure.status == 429 ||
            failure.status?.let { it in 500..599 } == true || failure.networkFailure || code?.startsWith("08") == true
        ) {
            return CompletionReplayOutcome.RETRYABLE
        }
        if (failure.status == 403 || failure.status == 409 ||
            (code != null && code in setOf("22023", "23505", "40001", "42501", "CONFLICT"))
        ) {
            return CompletionReplayOutcome.CONFLICT
        }
        if (failure.status == 404 ||
            (code != null && code in setOf("P0002", "PGRST116", "PERMANENT_FAILURE"))
        ) {
            return CompletionReplayOutcome.PERMANENT_FAILURE
        }
        if (failure.status?.let { it in 400..499 } == true) return CompletionReplayOutcome.PERMANENT_FAILURE
        return CompletionReplayOutcome.PERMANENT_FAILURE
    }

    fun describe(error: Throwable): CompletionFailure {
        var current: Throwable? = error
        var status: Int? = null
        var code: String? = null
        var message: String? = null
        var networkFailure = false
        while (current != null) {
            if (status == null) {
                status = (current as? ResponseException)?.response?.status?.value
                    ?: readNumber(current, "statusCode")
            }
            if (code == null) code = readString(current, "code") ?: readString(current, "errorCode")
            if (message == null) message = current.message?.takeIf(String::isNotBlank)
            val className = current.javaClass.simpleName
            if (current is IOException || className.contains("Timeout", ignoreCase = true) ||
                className.contains("ConnectException", ignoreCase = true) ||
                className.contains("UnknownHost", ignoreCase = true)
            ) {
                networkFailure = true
            }
            current = current.cause
        }
        return CompletionFailure(status, code, message, networkFailure)
    }

    fun retryDelayMillis(retryCount: Int): Long {
        val exponent = (retryCount - 1).coerceIn(0, 10)
        return (30_000L * (1L shl exponent)).coerceAtMost(6 * 60 * 60 * 1000L)
    }

    fun userMessage(outcome: CompletionReplayOutcome, failure: CompletionFailure): String = when (outcome) {
        CompletionReplayOutcome.RETRYABLE -> "Temporary sync problem. This completion will retry automatically."
        CompletionReplayOutcome.AUTH_REQUIRED -> "Sign in again to sync this saved completion."
        CompletionReplayOutcome.PERMANENT_FAILURE -> failure.message?.take(240)
            ?: "This occurrence is no longer available. Review this saved completion."
        CompletionReplayOutcome.CONFLICT -> failure.message?.take(240)
            ?: "The server state conflicts with this saved completion. Review it before retrying."
        CompletionReplayOutcome.ALREADY_APPLIED -> "Already completed on the server."
        CompletionReplayOutcome.SUCCESS -> "Synced."
    }

    private fun readString(error: Throwable, property: String): String? =
        runCatching {
            error.javaClass.methods
                .firstOrNull { it.name == "get${property.replaceFirstChar { char -> char.uppercase() }}" && it.parameterCount == 0 }
                ?.invoke(error) as? String
        }.getOrNull()

    private fun readNumber(error: Throwable, property: String): Int? =
        runCatching {
            (error.javaClass.methods
                .firstOrNull { it.name == "get${property.replaceFirstChar { char -> char.uppercase() }}" && it.parameterCount == 0 }
                ?.invoke(error) as? Number)?.toInt()
        }.getOrNull()
}
