package com.shadow.media.reading

import android.content.Intent
import android.media.session.MediaSession
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReaderNarrationServiceTest {
    @Test fun notificationOwnsMediaControlsAndStopDoesNotAutoResume() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(ReaderNarrationService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(Intent(context, ReaderNarrationService::class.java).setAction(ReaderNarrationService.OPTIONS)
                .putExtra("speed", 1.5f).putExtra("chapter", true), 0, 1)
            val notification = shadowOf(service).lastForegroundNotification
            assertEquals(4, notification.actions.size)
            val token = notification.extras.getParcelable<MediaSession.Token>("android.mediaSession")
            assertNotNull(token)
            service.onStartCommand(Intent(context, ReaderNarrationService::class.java).setAction(ReaderNarrationService.PAUSE), 0, 2)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(ReaderNarrationService.state.value.playing)
            assertEquals(1.5f, ReaderNarrationService.state.value.speed)
            service.onStartCommand(Intent(context, ReaderNarrationService::class.java).setAction(ReaderNarrationService.STOP), 0, 3)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertTrue(shadowOf(service).isForegroundStopped)
            assertFalse(ReaderNarrationService.state.value.playing)
        } finally { controller.destroy() }
    }
    @Test fun serviceReadsAcrossChaptersWithoutAnActivityAndPersistsTheSpokenLocation() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val library = com.shadow.media.library.LibraryRepository(context)
        val file = java.io.File(context.cacheDir, "background-speech.txt").apply {
            writeText("第一章 起航\n星光照船。\n第二章 归来\n月光照岸。")
        }
        val id = com.shadow.media.library.LibraryRepository.digest("background-speech")
        val item = library.importPrepared(id, "local", id, "远航", "txt", file)
        val controller = Robolectric.buildService(ReaderNarrationService::class.java).create()
        val service = controller.get()
        try {
            org.robolectric.shadows.ShadowTextToSpeech.addVoice(android.speech.tts.Voice("test-zh", java.util.Locale.SIMPLIFIED_CHINESE,
                400, 200, false, emptySet()))
            val engine = org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance()
            val shadow = shadowOf(engine)
            shadow.onInitListener.onInit(android.speech.tts.TextToSpeech.SUCCESS)
            service.onStartCommand(Intent(context, ReaderNarrationService::class.java).setAction(ReaderNarrationService.OPTIONS)
                .putExtra("chapter", true), 0, 1)
            service.onStartCommand(Intent(context, ReaderNarrationService::class.java).setAction(ReaderNarrationService.PLAY)
                .putExtra("asset", item.id), 0, 2)
            val deadline = System.nanoTime() + 5_000_000_000
            while ((ReaderNarrationService.state.value.playing || shadow.spokenTextList.isEmpty()) && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
                Thread.sleep(10)
            }
            assertNull(ReaderNarrationService.state.value.error)
            assertTrue(shadow.spokenTextList.any { it.contains("星光照船") })
            assertFalse(shadow.spokenTextList.any { it.contains("月光照岸") })
            assertTrue(ReaderNarrationService.state.value.stopAtChapter)
            service.onStartCommand(Intent(context, ReaderNarrationService::class.java).setAction(ReaderNarrationService.PLAY), 0, 3)
            val resumed = System.nanoTime() + 5_000_000_000
            while (ReaderNarrationService.state.value.playing && System.nanoTime() < resumed) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
                Thread.sleep(10)
            }
            assertTrue(shadow.spokenTextList.any { it.contains("月光照岸") })
            assertEquals(1, shadow.spokenTextList.count { it.contains("星光照船") })
            assertNotNull(ReaderNarrationService.state.value.locator)
            assertNotNull(context.getSharedPreferences("reader_narration", 0).getString(item.id, null))
            com.shadow.media.library.ProgressWriter.flush()
            assertNotNull(library.dao.progress(item.id))
        } finally { controller.destroy() }
    }
    @Test fun timerAndChapterOptionsConfiguredBeforePlaybackSurviveServiceRestart() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("reader_narration", 0).edit().clear().commit()
        val snapshot = org.robolectric.util.ReflectionHelpers.getStaticField<kotlinx.coroutines.flow.MutableStateFlow<NarrationState>>(
            ReaderNarrationService::class.java, "snapshot")
        snapshot.value = NarrationState()
        val first = Robolectric.buildService(ReaderNarrationService::class.java).create()
        first.get().onStartCommand(Intent(context, ReaderNarrationService::class.java).setAction(ReaderNarrationService.OPTIONS)
            .putExtra("minutes", 15).putExtra("chapter", true), 0, 1)
        val deadline = ReaderNarrationService.state.value.deadline
        assertNotNull(deadline)
        first.destroy()
        val second = Robolectric.buildService(ReaderNarrationService::class.java).create()
        try {
            second.get().onStartCommand(Intent(context, ReaderNarrationService::class.java).setAction(ReaderNarrationService.PLAY)
                .putExtra("asset", "book-to-load"), 0, 2)
            assertEquals(deadline, ReaderNarrationService.state.value.deadline)
            assertTrue(ReaderNarrationService.state.value.stopAtChapter)
            second.get().onStartCommand(Intent(context, ReaderNarrationService::class.java).setAction(ReaderNarrationService.STOP), 0, 3)
            assertNull(ReaderNarrationService.state.value.deadline)
        } finally { second.destroy() }
    }

    @Test fun freshServiceRestoresDurablePausedLocationWithoutStartingPlayback() {
        val context = RuntimeEnvironment.getApplication()
        val locator = """{"href":"chapter.xhtml","native_text":{"offset":17}}"""
        context.getSharedPreferences("reader_narration", 0).edit()
            .putString("last_asset", "saved-book").putString("saved-book", locator).commit()
        val snapshot = org.robolectric.util.ReflectionHelpers.getStaticField<kotlinx.coroutines.flow.MutableStateFlow<NarrationState>>(
            ReaderNarrationService::class.java, "snapshot")
        snapshot.value = NarrationState()
        val controller = Robolectric.buildService(ReaderNarrationService::class.java).create()
        try {
            assertEquals("saved-book", ReaderNarrationService.state.value.assetId)
            assertEquals(locator, ReaderNarrationService.state.value.locator)
            assertFalse(ReaderNarrationService.state.value.playing)
        } finally { controller.destroy() }
    }

    @Test fun sentenceNavigationPreservesUtf16BoundariesAndStopsAtChapterEnd() {
        val text = "甲😀。乙。"
        assertEquals(4, narrationSentenceStep(text, 0, 1))
        assertEquals(0, narrationSentenceStep(text, 4, -1))
        assertEquals(text.length, narrationSentenceStep(text, 4, 1))
        assertEquals(0, narrationSentenceStep(text, 0, -1))
    }
}
