package top.cylunex.shadowmedia.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ResourcePriority { FOREGROUND, ADJACENT, VISIBLE, OFFLINE, INDEX }

/** Reserve two slots for foreground work. Queues share permits, never requests or credentials. */
class ResourceScheduler(private val capacity: Int = 4, private val backgroundCapacity: Int = 2) {
    init { require(capacity > 0 && backgroundCapacity in 1..capacity) }
    private data class Request(val priority: ResourcePriority, val ready: CompletableDeferred<Unit> = CompletableDeferred(), var granted: Boolean = false)
    private val lock = Mutex()
    private val waiting = mutableListOf<Request>()
    private var running = 0
    private var background = 0
    class Permit internal constructor(private val releaseBlock: suspend () -> Unit) {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        suspend fun release() { if (released.compareAndSet(false, true)) releaseBlock() }
    }
    suspend fun acquire(priority: ResourcePriority): Permit {
        val request = Request(priority)
        lock.withLock { waiting += request; dispatch() }
        val permit = Permit {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { lock.withLock {
                waiting.remove(request)
                if (request.granted) { running--; if (priority != ResourcePriority.FOREGROUND) background-- }
                dispatch()
            } }
        }
        try { request.ready.await(); return permit }
        catch (e: Throwable) { permit.release(); throw e }
    }
    suspend fun <T> run(priority: ResourcePriority, block: suspend () -> T): T {
        val permit = acquire(priority)
        try { return block() } finally { permit.release() }
    }
    private fun dispatch() {
        while (running < capacity) {
            val next = waiting.filter { it.priority == ResourcePriority.FOREGROUND || background < backgroundCapacity }.minByOrNull { it.priority.ordinal } ?: break
            waiting.remove(next); next.granted = true; running++
            if (next.priority != ResourcePriority.FOREGROUND) background++
            next.ready.complete(Unit)
        }
    }
    companion object { val process = ResourceScheduler() }
}
