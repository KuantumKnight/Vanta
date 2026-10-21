package dev.system.app.data

import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.util.concurrent.CancellationException

enum class ReplayOutcome {
    SUCCESS,
    ALREADY_APPLIED,
    RETRYABLE,
    AUTH_REQUIRED,
    PERMANENT_FAILURE,
    CONFLICT,
}

data class ReplayResult(
    val outcome: ReplayOutcome,
    val errorCode: String? = null,
    val message: String? = null,
)

internal fun classifyReplayError(error: Throwable): ReplayResult {
    if (error is CancellationException) throw error
    val sqlState = error.readStringProperty("code")
        ?: error.readStringProperty("errorCode")
    when (sqlState) {
        "28000" -> return ReplayResult(ReplayOutcome.AUTH_REQUIRED, "AUTH_REQUIRED", "Sign in again to sync saved completions.")
        "P0002" -> return ReplayResult(ReplayOutcome.PERMANENT_FAILURE, "NOT_FOUND", "This occurrence is no longer available on the server.")
        "22023" -> {
            val detail = error.readStringProperty("detail") ?: error.readStringProperty("details")
            if (detail == "IDEMPOTENCY_CONFLICT") {
                return ReplayResult(ReplayOutcome.CONFLICT, detail, "The server reported a conflicting completion request.")
            }
            return ReplayResult(ReplayOutcome.PERMANENT_FAILURE, detail ?: "INVALID_MUTATION", "The server rejected this completion.")
        }
        "23505" -> return ReplayResult(ReplayOutcome.CONFLICT, "IDEMPOTENCY_CONFLICT", "The server reported a conflicting completion request.")
        "40001", "40P01", "55P03", "57014" ->
            return ReplayResult(ReplayOutcome.RETRYABLE, "TEMPORARY_FAILURE", "The server could not process this completion yet.")
    }
    if (sqlState?.startsWith("08") == true || sqlState?.startsWith("53") == true || sqlState?.startsWith("57") == true) {
        return ReplayResult(ReplayOutcome.RETRYABLE, "TEMPORARY_FAILURE", "The server could not process this completion yet.")
    }

    val status = error.responseStatus()
    if (status == 401) return ReplayResult(ReplayOutcome.AUTH_REQUIRED, "AUTH_REQUIRED", "Sign in again to sync saved completions.")
    if (status == 409) return ReplayResult(ReplayOutcome.CONFLICT, "CONFLICT", "The server reported a conflicting completion request.")
    if (status == 408 || status == 429 || status != null && status >= 500) {
        return ReplayResult(ReplayOutcome.RETRYABLE, "TEMPORARY_FAILURE", "The server is temporarily unavailable.")
    }
    if (status != null) return ReplayResult(ReplayOutcome.PERMANENT_FAILURE, "HTTP_$status", "The server rejected this completion.")
    if (error.hasCause<IOException>() || error.hasCause<HttpRequestTimeoutException>()) {
        return ReplayResult(ReplayOutcome.RETRYABLE, "NETWORK_UNAVAILABLE", "Could not reach the server.")
    }
    return ReplayResult(ReplayOutcome.PERMANENT_FAILURE, "INVALID_MUTATION", "This completion could not be applied.")
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean = findCause<T>() != null

private fun Throwable.readStringProperty(name: String): String? = runCatching {
    javaClass.methods.firstOrNull { it.name == "get" + name.replaceFirstChar { first -> first.uppercase() } }
        ?.invoke(this) as? String
}.getOrNull()

private fun Throwable.responseStatus(): Int? {
    var current: Throwable? = this
    while (current != null) {
        val cause = current
        val response = runCatching {
            cause.javaClass.methods.firstOrNull { it.name == "getResponse" }?.invoke(cause)
        }.getOrNull()
        val statusCode = response?.let { value ->
            runCatching { value.javaClass.methods.firstOrNull { it.name == "getStatus" }?.invoke(value) }.getOrNull()
        }
        val status = statusCode?.let { value ->
            runCatching { value.javaClass.methods.firstOrNull { it.name == "getValue" }?.invoke(value) as? Int }.getOrNull()
        }
        if (status != null) return status
        current = cause.cause
    }
    return null
}
