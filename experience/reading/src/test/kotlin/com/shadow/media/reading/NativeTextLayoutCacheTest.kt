package com.shadow.media.reading

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeTextLayoutCacheTest {
    private suspend fun document() = NativeTextLayout.paginate(
        NativeTextChapter(NativeChapterRef("c1", "第二章", 1, "c1.xhtml"), "旅人提灯前行。", "v1", 3),
        360, 640, ReaderSettings(), 1f, 1f)

    @Test fun chapterTurnJoinsWarmupEvenWhenThePrefetchCallerIsCancelled() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val cache = NativeTextLayoutCache(scope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val builds = AtomicInteger()
        val expected = document()
        try {
            val warmup = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("chapter-style-size") { builds.incrementAndGet(); entered.complete(Unit); release.await(); expected }
            }
            entered.await()
            warmup.cancelAndJoin()
            val foreground = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("chapter-style-size") { builds.incrementAndGet(); error("must join the original layout") }
            }
            release.complete(Unit)
            assertSame(expected, foreground.await())
            assertSame(expected, cache.get("chapter-style-size") { error("must reuse completed layout") })
            assertEquals(1, builds.get())
        } finally { scope.cancel() }
    }

    @Test fun layoutFailuresCanRetryAndCompletedLayoutsRemainBounded() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val cache = NativeTextLayoutCache(scope, capacity = 2)
        val expected = document()
        try {
            try { cache.get("small-font") { error("bad layout") }; fail("failure must propagate") }
            catch (_: IllegalStateException) { }
            assertSame(expected, cache.get("small-font") { expected })
            cache.get("large-font") { expected }
            cache.get("rotated") { expected }
            var rebuilt = false
            cache.get("small-font") { rebuilt = true; expected }
            assertTrue("Old font/viewport entries must not accumulate", rebuilt)
        } finally { scope.cancel() }
    }

    @Test fun disposingTheReaderCancelsPendingPagination() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val cache = NativeTextLayoutCache(scope)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        try {
            val warmup = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("chapter") {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
            }
            entered.await()
            cache.clear()
            cancelled.await()
            warmup.join()
            assertTrue(warmup.isCancelled)
        } finally { scope.cancel() }
    }
}
