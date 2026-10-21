package dev.system.app.sync

/** Coalesces refresh hints and schedules a follow-up pass after an active sync. */
internal class SyncRequestGate {
    private val lock = Any()
    private var runningWorkers = 0
    private var workScheduled = false
    private var dirty = false

    fun request(enqueue: () -> Unit) = synchronized(lock) {
        when {
            runningWorkers > 0 -> dirty = true
            workScheduled -> Unit
            else -> {
                workScheduled = true
                enqueue()
            }
        }
    }

    fun workerStarted() = synchronized(lock) {
        runningWorkers += 1
        workScheduled = false
        // Any earlier hint is covered by this pass. New hints during the pass
        // set dirty again and cause one follow-up work item at completion.
        dirty = false
    }

    fun workerFinished(enqueueFollowUp: () -> Unit) = synchronized(lock) {
        if (runningWorkers > 0) runningWorkers -= 1
        if (runningWorkers == 0 && dirty && !workScheduled) {
            dirty = false
            workScheduled = true
            enqueueFollowUp()
        }
    }
}
