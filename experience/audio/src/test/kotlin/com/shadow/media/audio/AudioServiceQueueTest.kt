@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.shadow.media.audio

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.shadow.media.database.LibraryAssetEntity
import com.shadow.media.database.AudioQueueEntity
import com.shadow.media.database.AudioQueueEntryEntity
import com.shadow.media.database.AudioQueueSnapshot
import com.shadow.media.database.ShadowMediaDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Real MediaController -> MediaLibrarySession -> service, backed by a test-local Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AudioServiceQueueTest {
    private lateinit var service: AudiobookService
    private lateinit var controller: MediaController
    private lateinit var player: ExoPlayer

    @Before fun start() {
        val context = RuntimeEnvironment.getApplication()
        val db = ShadowMediaDatabase.create(context)
        context.getSharedPreferences("audio_preferences", 0).edit().clear().commit()
        context.getSharedPreferences("audio_queue", 0).edit().clear().commit()
        runBlocking {
            AudioQueuePersistence.flush()
            withContext(Dispatchers.IO) { db.clearAllTables() }
            listOf("song" to "MUSIC", "book" to "AUDIOBOOK").forEach { (id, kind) ->
                db.libraryDao().putAsset(LibraryAssetEntity(id, "local", id, id, kind = kind,
                    format = "mp3", localUri = "content://example.com/$id", addedAt = 0))
            }
        }
        service = Robolectric.buildService(AudiobookService::class.java).create().get()
        player = ReflectionHelpers.getField(service, "player")
        val session: MediaLibrarySession = ReflectionHelpers.getField(service, "session")
        controller = await(MediaController.Builder(context, session.token).buildAsync())
        pumpUntil { ReflectionHelpers.getField<Boolean>(service, "initialized") }
    }

    @After fun stop() {
        if (::controller.isInitialized) controller.release()
        if (::service.isInitialized) service.onDestroy()
        runBlocking { AudioQueuePersistence.flush() }
    }

    @Test fun `new audiobook queue does not inherit music shuffle or repeat`() {
        select("song")
        controller.shuffleModeEnabled = true
        controller.repeatMode = Player.REPEAT_MODE_ONE
        pumpUntil { player.shuffleModeEnabled && player.repeatMode == Player.REPEAT_MODE_ONE }
        select("book")
        assertFalse(player.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
        switchMode("MUSIC")
        assertEquals("song", player.currentMediaItem?.mediaId)
        assertTrue(player.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
        assertFalse(player.playWhenReady)
    }

    @Test fun `restoring another queue clears the old sleep deadline`() {
        select("song")
        select("book")
        assertEquals(SessionResult.RESULT_SUCCESS, sleep(30).resultCode)
        switchMode("MUSIC")
        assertEquals(0L, AudioSleep.remainingMs.value)
        assertEquals(0L, ReflectionHelpers.getField<Long>(service, "sleepDeadline"))
    }

    @Test fun `new queue in the same mode clears sleep but retains music controls`() {
        select("song")
        controller.repeatMode = Player.REPEAT_MODE_ALL
        pumpUntil { player.repeatMode == Player.REPEAT_MODE_ALL }
        assertEquals(SessionResult.RESULT_SUCCESS, sleep(-1).resultCode)
        val oldEntry = player.currentMediaItem!!.entryId()
        controller.setMediaItems(listOf(MediaItem.Builder().setMediaId("song").build()), 0, 0)
        pumpUntil { player.currentMediaItem?.entryId() != oldEntry }
        assertEquals(0L, AudioSleep.remainingMs.value)
        assertFalse(player.pauseAtEndOfMediaItems)
        assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
    }

    @Test fun `track timer uses player boundary and manual next cancels even for duplicate tracks`() {
        controller.setMediaItems(List(2) { MediaItem.Builder().setMediaId("song").build() }, 0, 0)
        pumpUntil { player.mediaItemCount == 2 }
        assertEquals(SessionResult.RESULT_SUCCESS, sleep(-1).resultCode)
        assertTrue(player.pauseAtEndOfMediaItems)
        assertEquals(-1L, AudioSleep.remainingMs.value)
        controller.seekToNextMediaItem()
        pumpUntil { player.currentMediaItemIndex == 1 }
        assertFalse(player.pauseAtEndOfMediaItems)
        assertEquals(0L, AudioSleep.remainingMs.value)
    }

    @Test fun `invalid or unavailable sleep request preserves existing timer`() {
        select("book")
        assertEquals(SessionResult.RESULT_SUCCESS, sleep(30).resultCode)
        val deadline: Long = ReflectionHelpers.getField(service, "sleepDeadline")
        assertTrue(AudioSleep.remainingMs.value > 0)
        assertEquals(SessionError.ERROR_BAD_VALUE, sleep(241).resultCode)
        assertEquals(SessionError.ERROR_BAD_VALUE, sleep(-3).resultCode)
        assertEquals(SessionError.ERROR_NOT_SUPPORTED, sleep(-2).resultCode)
        assertEquals(deadline, ReflectionHelpers.getField<Long>(service, "sleepDeadline"))
        assertEquals(SessionResult.RESULT_SUCCESS, sleep(0).resultCode)
        assertEquals(0L, AudioSleep.remainingMs.value)
    }

    @Test fun `empty queue rejects sleep without leaving a timer for future audio`() {
        assertEquals(SessionError.ERROR_INVALID_STATE, sleep(-1).resultCode)
        assertEquals(SessionError.ERROR_INVALID_STATE, sleep(15).resultCode)
        assertFalse(player.pauseAtEndOfMediaItems)
        assertEquals(0L, AudioSleep.remainingMs.value)
    }

    @Test fun `appending keeps the sleep target but clearing the queue disarms it`() {
        select("song")
        assertEquals(SessionResult.RESULT_SUCCESS, sleep(15).resultCode)
        val deadline: Long = ReflectionHelpers.getField(service, "sleepDeadline")
        controller.addMediaItem(MediaItem.Builder().setMediaId("song").build())
        pumpUntil { player.mediaItemCount == 2 }
        assertEquals(deadline, ReflectionHelpers.getField<Long>(service, "sleepDeadline"))
        controller.clearMediaItems()
        pumpUntil { player.mediaItemCount == 0 }
        assertEquals(0L, AudioSleep.remainingMs.value)
        controller.addMediaItem(MediaItem.Builder().setMediaId("song").build())
        pumpUntil { player.mediaItemCount == 1 }
        assertEquals(0L, AudioSleep.remainingMs.value)
    }

    @Test fun `legacy listening queue restores speed and position but normalizes music controls`() {
        select("song")
        runBlocking {
            AudioQueuePersistence.flush()
            ShadowMediaDatabase.create(service).audioQueueDao().save(AudioQueueSnapshot(
                AudioQueueEntity("AUDIOBOOK", "book-entry", 12345, 1.75f, Player.REPEAT_MODE_ONE, true),
                listOf(AudioQueueEntryEntity("book-entry", "AUDIOBOOK", "book", "", 0, 0))))
        }
        switchMode("AUDIOBOOK")
        assertEquals("book", player.currentMediaItem?.mediaId)
        assertEquals(12345L, player.currentPosition)
        assertEquals(1.75f, player.playbackParameters.speed)
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
        assertFalse(player.shuffleModeEnabled)
        assertFalse(player.playWhenReady)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        runBlocking {
            AudioQueuePersistence.flush()
            val saved = ShadowMediaDatabase.create(service).audioQueueDao().load("AUDIOBOOK")!!
            assertEquals(12345L, saved.queue.positionMs)
            assertFalse(saved.queue.shuffleEnabled)
        }
    }

    @Test fun `end of track pauses before advancing or repeating and releases the boundary afterwards`() {
        // Exercise ExoPlayer's finite timeline; audio output is disabled on the host, not mocked.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        for (repeat in listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)) {
            controller.setMediaItems(List(2) { MediaItem.Builder().setMediaId("song").build() }, 0, 0)
            pumpUntil { player.mediaItemCount == 2 && player.currentMediaItemIndex == 0 }
            val items = (0..1).map(player::getMediaItemAt)
            player.setMediaSources(items.map { item ->
                SilenceMediaSource(100_000).apply { updateMediaItem(item) }
            })
            player.repeatMode = repeat
            var endPauses = 0
            val listener = object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) endPauses++
                }
            }
            player.addListener(listener)
            assertEquals(SessionResult.RESULT_SUCCESS, sleep(-1).resultCode)
            player.prepare()
            player.play()
            pumpUntil(advanceTime = true) { endPauses == 1 }
            assertNull(player.playerError)
            assertFalse(player.playWhenReady)
            assertEquals(0, player.currentMediaItemIndex)
            assertEquals(items[0].entryId(), player.currentMediaItem?.entryId())
            assertFalse(player.pauseAtEndOfMediaItems)
            assertEquals(0L, AudioSleep.remainingMs.value)
            player.removeListener(listener)
            // Continue should be allowed to cross the boundary; disabling repeat makes this observable.
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.play()
            pumpUntil(advanceTime = true) { player.currentMediaItemIndex == 1 }
            player.pause()
            player.stop()
        }
    }

    private fun select(id: String) {
        controller.setMediaItems(listOf(MediaItem.Builder().setMediaId(id).build()), 0, 0)
        pumpUntil { player.currentMediaItem?.mediaId == id }
    }

    private fun switchMode(mode: String) {
        val result = await(controller.sendCustomCommand(SessionCommand(AudiobookService.SWITCH_MODE, Bundle.EMPTY),
            Bundle().apply { putString("mode", mode) }))
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
    }

    private fun sleep(minutes: Int) = await(controller.sendCustomCommand(SessionCommand(AudiobookService.SLEEP, Bundle.EMPTY),
        Bundle().apply { putInt("minutes", minutes) }))

    private fun <T> await(future: ListenableFuture<T>): T {
        pumpUntil { future.isDone }
        return future.get()
    }

    private fun pumpUntil(advanceTime: Boolean = false, condition: () -> Boolean) {
        val timeout = System.nanoTime() + 5_000_000_000L
        do {
            if (advanceTime) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            else shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < timeout)
        fail("Audio service did not reach the expected state")
    }
}
