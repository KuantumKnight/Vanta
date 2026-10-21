package dev.system.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncRequestGateTest {
    @Test
    fun refreshDuringFetchedPassSchedulesOneFollowUpThatReadsLatestState() {
        val gate = SyncRequestGate()
        var scheduled = 0
        var remoteVersion = 1
        var versionFetchedByA = 0
        var versionFetchedByB = 0

        gate.request { scheduled += 1 }
        assertEquals(1, scheduled)
        gate.workerStarted()

        // Sync A has fetched the current state. A remote mutation and its FCM
        // refresh arrive before A finishes; repeated hints coalesce.
        versionFetchedByA = remoteVersion
        remoteVersion = 2
        gate.request { scheduled += 1 }
        gate.request { scheduled += 1 }
        gate.workerFinished { scheduled += 1 }

        assertEquals(2, scheduled)
        gate.workerStarted()
        versionFetchedByB = remoteVersion
        gate.workerFinished { scheduled += 1 }

        assertEquals(1, versionFetchedByA)
        assertEquals(2, versionFetchedByB)
        assertEquals(2, scheduled)
    }

    @Test
    fun refreshesWhileWorkIsAlreadyScheduledDoNotCreateDuplicateWork() {
        val gate = SyncRequestGate()
        var scheduled = 0
        gate.request { scheduled += 1 }
        gate.request { scheduled += 1 }
        gate.request { scheduled += 1 }

        assertEquals(1, scheduled)
        gate.workerStarted()
        gate.workerFinished { scheduled += 1 }
        assertEquals(1, scheduled)
    }
}
