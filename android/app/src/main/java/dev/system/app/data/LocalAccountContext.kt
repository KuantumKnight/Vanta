package dev.system.app.data

enum class RemoteAuthAvailability {
    INITIALIZING,
    AVAILABLE,
    TEMPORARY_FAILURE,
    REAUTH_REQUIRED,
}

data class LocalAccountContext(
    val userId: String?,
    val remoteAuth: RemoteAuthAvailability,
) {
    val hasLocalAccount: Boolean get() = !userId.isNullOrBlank()
    val canCallRemote: Boolean get() = remoteAuth == RemoteAuthAvailability.AVAILABLE
    fun canShowAccount(remoteUserId: String?): Boolean =
        remoteUserId == null || userId == null || userId == remoteUserId

    fun visibleUserId(remoteUserId: String?): String? =
        userId?.takeIf { canShowAccount(remoteUserId) }
}

/** Keeps local ownership stable while Supabase refresh is temporarily unavailable. */
fun localContextAfterRefreshFailure(previous: LocalAccountContext): LocalAccountContext =
    previous.copy(remoteAuth = RemoteAuthAvailability.TEMPORARY_FAILURE)
