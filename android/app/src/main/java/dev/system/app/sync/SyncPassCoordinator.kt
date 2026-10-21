package dev.system.app.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes sync calls; a WorkManager replacement waits here and performs a fresh pass. */
internal class SyncPassCoordinator {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
