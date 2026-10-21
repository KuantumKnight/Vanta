package dev.system.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalCacheIdentityTest {
    @Test
    fun refreshFailureKeepsUsingPersistedOwnerForRoomRows() {
        assertEquals(
            "user-a",
            LocalCacheIdentity.userId(RemoteIdentityState.REFRESH_FAILURE, null, "user-a"),
        )
        assertEquals(
            "user-a",
            LocalCacheIdentity.userId(RemoteIdentityState.REFRESH_FAILURE, "user-a", null),
        )
    }

    @Test
    fun successfulAccountChangeSwitchesCacheOwnerAndSignedOutStateExposesNoCache() {
        assertEquals(
            "user-b",
            LocalCacheIdentity.userId(RemoteIdentityState.AUTHENTICATED, "user-b", "user-a"),
        )
        assertNull(LocalCacheIdentity.userId(RemoteIdentityState.OTHER, null, "user-a"))
    }
}
