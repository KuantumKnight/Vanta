package dev.system.app.sync

import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SyncPassCoordinatorTest {
    @Test
    fun refreshQueuedDuringActivePassRunsAfterThatPass() = runBlocking {
        val coordinator = SyncPassCoordinator()
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val first = launch {
            coordinator.run {
                events += "A started"
                firstStarted.complete(Unit)
                finishFirst.await()
                events += "A finished"
            }
        }
        firstStarted.await()

        val followUp = launch {
            coordinator.run { events += "B started" }
        }
        yield()

        assertFalse("the follow-up must wait while A owns the coordinator", followUp.isCompleted)
        assertEquals(listOf("A started"), events)
        finishFirst.complete(Unit)
        joinAll(first, followUp)

        assertEquals(listOf("A started", "A finished", "B started"), events)
    }

    @Test
    fun immediateRefreshUsesReplacePolicy() {
        assertEquals(ExistingWorkPolicy.REPLACE, SyncWorker.immediateWorkPolicy())
    }
}
