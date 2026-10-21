package com.shadow.media.library

import android.content.Context
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.shadow.media.model.*

@RunWith(RobolectricTestRunner::class) @Config(manifest = Config.NONE, sdk = [28])
class VideoQueueStoreTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val movie = UnifiedMediaItem(MediaKey("local-video", "https://example.com/movie.mp4?signature=secret"), "movie", "Video")
    @Before fun clear() { context.getSharedPreferences("video_queue", 0).edit().clear().commit() }
    @Test fun `queue persists duplicates order position and repeat without stream addresses`() {
        val store = VideoQueueStore(context)
        val queue = store.start(listOf(movie, movie))
        store.select(queue.entries.last().entryId)
        store.position(queue.entries.last().entryId, 42_000)
        store.repeat(true)
        val snapshot = context.getSharedPreferences("video_queue", 0).getString("snapshot", "")!!
        assertFalse(snapshot.contains("example.com")); assertFalse(snapshot.contains("secret"))
        val restored = VideoQueueStore(context)
        restored.restore(listOf(movie))
        assertEquals(queue.entries.map { it.entryId }, restored.queue.value.entries.map { it.entryId })
        assertEquals(42_000L, restored.queue.value.positionMs)
        assertTrue(restored.queue.value.repeat)
        assertFalse(restored.active)
    }
    @Test fun `late snapshot from previous entry cannot overwrite the new current entry`() {
        val store = VideoQueueStore(context)
        val queue = store.start(listOf(movie, movie))
        store.advance()
        store.position(queue.entries.first().entryId, 90_000)
        assertEquals(0L, store.queue.value.positionMs)
        assertEquals(queue.entries.last().entryId, store.queue.value.currentEntryId)
    }
    @Test fun `removed current file relocates paused restoration and clears stale position`() {
        val store = VideoQueueStore(context)
        store.start(listOf(movie, movie.copy(key = MediaKey("local-video", "content://videos/2"))))
        store.position(store.queue.value.currentEntryId!!, 30_000)
        val restored = VideoQueueStore(context)
        restored.restore(listOf(movie.copy(key = MediaKey("local-video", "content://videos/2"))))
        assertEquals(1, restored.queue.value.entries.size)
        assertEquals(0L, restored.queue.value.positionMs)
    }
}
