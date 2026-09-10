package top.cylunex.shadowmedia.library
import top.cylunex.shadowmedia.network.ResourceScheduler
import top.cylunex.shadowmedia.network.ResourcePriority

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ResourceSchedulerTest {
    @Test fun `background download cannot consume foreground reserved slot`() = runBlocking {
        withTimeout(3000) {
            val scheduler = ResourceScheduler(2, 1)
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val background = launch { scheduler.run(ResourcePriority.OFFLINE) { started.complete(Unit); release.await() } }
            started.await()
            val queued = launch { scheduler.run(ResourcePriority.INDEX) { fail("Queued scan should be cancelled") } }
            yield()
            assertEquals("ready", scheduler.run(ResourcePriority.FOREGROUND) { "ready" })
            queued.cancelAndJoin(); release.complete(Unit); background.join()
            assertEquals("next", scheduler.run(ResourcePriority.INDEX) { "next" })
        }
    }
    @Test fun `cancelled foreground request releases granted permit`() = runBlocking {
        withTimeout(3000) {
            val scheduler = ResourceScheduler(1, 1)
            val begun = CompletableDeferred<Unit>()
            val job = launch { scheduler.run(ResourcePriority.FOREGROUND) { begun.complete(Unit); awaitCancellation() } }
            begun.await(); job.cancelAndJoin()
            assertEquals(42, scheduler.run(ResourcePriority.FOREGROUND) { 42 })
        }
    }
}
