package dev.system.app.data

internal enum class RemoteIdentityState {
    AUTHENTICATED,
    REFRESH_FAILURE,
    OTHER,
}

internal object LocalCacheIdentity {
    fun userId(
        state: RemoteIdentityState,
        currentSessionUserId: String?,
        persistedUserId: String?,
    ): String? = when (state) {
        RemoteIdentityState.AUTHENTICATED -> currentSessionUserId
        RemoteIdentityState.REFRESH_FAILURE -> currentSessionUserId ?: persistedUserId
        RemoteIdentityState.OTHER -> null
    }
}
