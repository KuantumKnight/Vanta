package dev.system.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineStateTest {
    @Test
    fun refreshFailureRetainsLocalAccountAndCachedAccountVisibility() {
        val signedIn = LocalAccountContext("user-a", RemoteAuthAvailability.AVAILABLE)
        val offline = localContextAfterRefreshFailure(signedIn)

        assertEquals("user-a", offline.userId)
        assertEquals(RemoteAuthAvailability.TEMPORARY_FAILURE, offline.remoteAuth)
        assertTrue(offline.hasLocalAccount)
        assertFalse(offline.canCallRemote)
    }

    @Test
    fun aNewRemoteUserCannotUseAnotherAccountsLocalContext() {
        val oldAccount = LocalAccountContext("user-a", RemoteAuthAvailability.AVAILABLE)

        assertFalse(oldAccount.canShowAccount("user-b"))
        assertTrue(oldAccount.canShowAccount(null))
        assertTrue(oldAccount.canShowAccount("user-a"))
        assertEquals(null, oldAccount.visibleUserId("user-b"))
        assertEquals("user-a", oldAccount.visibleUserId(null))
    }
}
