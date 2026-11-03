@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.shadow.media.reading

import android.app.*
import android.content.*
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.shadow.media.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content

internal data class NarrationState(
    val assetId: String? = null, val locator: String? = null, val title: String = "",
    val playing: Boolean = false, val error: String? = null, val speed: Float = 1f,
    val voices: List<Pair<String, String>> = emptyList(), val voice: String? = null,
    val stopAtChapter: Boolean = false, val deadline: Long? = null,
)

/** Owns speech independently of either reader Activity, including the media session and progress. */
class ReaderNarrationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var library: LibraryRepository
    private lateinit var novel: NovelRepository
    private lateinit var source: NativeTextSource
    private lateinit var speech: ReadAloud
    private lateinit var media: MediaSession
    private var destroying = false
    private var job: Job? = null
    private var timer: Job? = null
    private var idle: Job? = null
    private var publication: Publication? = null
    private var chapter: NativeTextChapter? = null
    private var writer: ProgressSession? = null
    private val wakeLock by lazy {
        getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "shadow:readerSpeech").apply { setReferenceCounted(false) }
    }
    private var offset = 0
    private var epubText: String? = null
    private var epubBase: Locator? = null
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { pause() }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        library = LibraryRepository(this); novel = NovelRepository(this, library); source = NativeTextSource(library, novel)
        speech = ReadAloud(this) { pause() }
        media = MediaSession(this, "ReaderNarration").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onStop() { finishPlayback() }
                override fun onSkipToNext() { step(1) }
                override fun onSkipToPrevious() { step(-1) }
            })
            isActive = true
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "阅读朗读", NotificationManager.IMPORTANCE_LOW))
        val filter = IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(noisy, filter) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground ownership is established before requesting audio focus.
        startForeground(NOTIFICATION, notification())
        when (intent?.action) {
            PLAY -> {
                val asset = intent.getStringExtra("asset")
                if (asset != null) {
                    pause()
                    publication?.close(); publication = null; chapter = null; writer = null
                    timer?.cancel()
                    snapshot.value = NarrationState(assetId = asset, locator = intent.getStringExtra("locator"), speed = snapshot.value.speed, voice = snapshot.value.voice)
                }
                play()
            }
            PAUSE -> pause()
            STOP -> finishPlayback()
            NEXT -> step(1)
            PREVIOUS -> step(-1)
            OPTIONS -> {
                val value = snapshot.value
                val speed = intent.getFloatExtra("speed", value.speed).coerceIn(.5f, 3f)
                val voice = intent.getStringExtra("voice") ?: value.voice
                snapshot.value = value.copy(speed = speed, voice = voice,
                    stopAtChapter = intent.getBooleanExtra("chapter", value.stopAtChapter))
                speech.speed = speed; speech.voiceName = voice
                if (intent.hasExtra("minutes")) {
                    timer?.cancel()
                    val minutes = intent.getIntExtra("minutes", 0).coerceIn(0, 480)
                    snapshot.value = snapshot.value.copy(deadline = if (minutes == 0) null else SystemClock.elapsedRealtime() + minutes * 60_000L)
                    if (minutes > 0) timer = scope.launch { delay(minutes * 60_000L); pause() }
                }
                updateNotification()
                if (snapshot.value.assetId == null) stopSelf()
            }
            else -> { pause(); stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun play() {
        if (snapshot.value.playing || snapshot.value.assetId == null) return
        idle?.cancel()
        val previous = job
        wakeLock.acquire(10 * 60_000L)
        snapshot.value = snapshot.value.copy(playing = true, error = null)
        updateNotification()
        job = scope.launch {
            previous?.join()
            snapshot.value = snapshot.value.copy(playing = true)
            updateNotification()
            try {
                while (isActive) {
                    val state = snapshot.value
                    val item = requireNotNull(library.dao.asset(requireNotNull(state.assetId))) { "图书已移除" }
                    val pub = novel.dao.publication(item.id)
                    writer = writer ?: ProgressWriter.begin(library, item).also { activeSession = item.id to it }
                    speech.speed = state.speed; speech.voiceName = state.voice
                    if (item.format == "txt" || pub != null) {
                        val locator = state.locator?.let(::JSONObject)
                        val current = chapter ?: source.chapter(item, pub, locator?.optString("href")).also {
                            offset = it.restore(locator); chapter = it
                        }
                        if (pub != null) novel.beginReading(pub, current.locator(offset))
                        speech.readText(current.text, offset) { point ->
                            offset = point
                            publish(item.id, current.ref.title, current.locator(point).toString())
                            val fraction = (current.ref.ordinal + point.toDouble() / current.text.length.coerceAtLeast(1)) / current.total.coerceAtLeast(1)
                            ProgressWriter.save(library, requireNotNull(writer), "text", current.locator(point), fraction, false)
                        }
                        val finished = current.locator(current.text.length)
                        ProgressWriter.save(library, requireNotNull(writer), "text", finished,
                            (current.ref.ordinal + 1.0) / current.total.coerceAtLeast(1),
                            pub == null && current.ref.ordinal == current.total - 1)
                        snapshot.value = snapshot.value.copy(locator = finished.toString())
                        if (snapshot.value.stopAtChapter) break
                        val next = nextChapter(item, pub, current) ?: break
                        ProgressWriter.flush()
                        val prepared = if (pub == null) item else novel.cachedChapter(pub.scope, pub.workId, pub.versionId, next.id)?.asset
                            ?: novel.prepare(novel.connection(pub.scope), pub.workId, pub.versionId, next.id).asset
                        chapter = null; writer = null
                        snapshot.value = snapshot.value.copy(assetId = prepared.id, locator = JSONObject().put("href", next.href).toString())
                    } else {
                        val book = publication ?: ReadiumEngineAdapter(this@ReaderNarrationService).open(library.localFile(item)).also { publication = it }
                        val saved = state.locator?.let(::JSONObject)
                        val initial = saved?.let { Locator.fromJSON(it.optJSONObject("speech_base") ?: it) }
                        val iterator = requireNotNull(book.content(initial)) { "本书无法提取正文" }.iterator()
                        var first = true
                        var reachedEnd = true
                        var initialHref = initial?.href
                        while (iterator.hasNext()) {
                            val element = iterator.next() as? Content.TextElement ?: continue
                            if (element.text.isBlank()) continue
                            if (initialHref == null) initialHref = element.locator.href
                            if (snapshot.value.stopAtChapter && element.locator.href != initialHref) { reachedEnd = false; break }
                            epubText = element.text; epubBase = element.locator
                            val start = if (first) saved?.optInt("speech_offset", 0) ?: 0 else 0
                            first = false
                            speech.readText(element.text, start) { point ->
                                offset = point
                                val end = narrationSentenceStep(element.text, point, 1)
                                val locator = element.locator.toJSON()
                                locator.put("text", JSONObject().put("before", element.text.take(point).takeLast(80))
                                    .put("highlight", element.text.substring(point, end)).put("after", element.text.drop(end).take(80)))
                                locator.put("speech_offset", point).put("speech_base", element.locator.toJSON())
                                publish(item.id, element.locator.title ?: item.title, locator.toString())
                                ProgressWriter.save(library, requireNotNull(writer), "epub", locator, element.locator.locations.totalProgression ?: 0.0, false)
                            }
                        }
                        if (reachedEnd) snapshot.value.locator?.let { savedLocation ->
                            val finished = JSONObject(savedLocation)
                            finished.optJSONObject("locations")?.put("totalProgression", 1.0)
                            ProgressWriter.save(library, requireNotNull(writer), "epub", finished, 1.0, true)
                        }
                        break
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { snapshot.value = snapshot.value.copy(error = e.message ?: "朗读失败，请检查系统语音") }
            finally {
                if (coroutineContext[Job] == job) {
                    snapshot.value = snapshot.value.copy(playing = false)
                    if (wakeLock.isHeld) wakeLock.release()
                    updateNotification()
                    idle?.cancel()
                    idle = scope.launch { delay(5 * 60_000L); finishPlayback() }
                }
                withContext(NonCancellable) { ProgressWriter.flush() }
                NovelRepository.schedule(this@ReaderNarrationService)
            }
        }
    }
    private suspend fun nextChapter(item: com.shadow.media.database.LibraryAssetEntity, pub: com.shadow.media.database.NovelPublicationEntity?, current: NativeTextChapter): NativeChapterRef? {
        if (current.ref.ordinal >= current.total - 1) return null
        if (pub == null) return source.local(item).getOrNull(current.ref.ordinal + 1)?.ref
        val rows = novel.cachedContents(pub)
        fun ref(row: JSONObject) = NativeChapterRef(row.getString("id"), row.optString("title"), row.getInt("ordinal"), "chapters/${row.getString("id")}.xhtml")
        if (rows.isNotEmpty()) rows.firstOrNull { it.optInt("ordinal") == current.ref.ordinal + 1 && it.optBoolean("readable", true) }?.let { return ref(it) }
        var start = current.ref.ordinal + 1
        while (true) {
            val page = novel.get(novel.connection(pub.scope), listOf("works", pub.workId, "contents"),
                listOf("version_id" to pub.versionId, "offset" to start.toString(), "limit" to "100"))
            page.novelObjects("items").firstOrNull { it.optBoolean("readable", true) }?.let { return ref(it) }
            start += 100
            if (start >= page.optInt("total")) return null
        }
    }
    private fun publish(asset: String, title: String, locator: String) {
        wakeLock.acquire(10 * 60_000L)
        snapshot.value = snapshot.value.copy(assetId = asset, locator = locator, title = title,
            voices = speech.availableVoices.map { it.name to it.locale.displayName })
        getSharedPreferences("reader_narration", MODE_PRIVATE).edit().putString("last_asset", asset).putString(asset, locator).apply()
        updateNotification()
    }
    private fun step(direction: Int) {
        val text = chapter?.text ?: epubText ?: return
        pause()
        offset = narrationSentenceStep(text, offset, direction)
        val location = chapter?.locator(offset) ?: epubBase?.toJSON()?.put("speech_base", epubBase?.toJSON())?.put("speech_offset", offset) ?: return
        snapshot.value = snapshot.value.copy(locator = location.toString())
        play()
    }
    private fun pause() {
        job?.cancel(); speech.stop()
        if (wakeLock.isHeld) wakeLock.release()
        snapshot.value = snapshot.value.copy(playing = false)
        updateNotification()
        scope.launch { ProgressWriter.flush(); NovelRepository.schedule(this@ReaderNarrationService) }
        idle?.cancel()
        idle = scope.launch { delay(5 * 60_000L); finishPlayback() }
    }
    private fun finishPlayback() {
        pause(); timer?.cancel(); idle?.cancel()
        media.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun action(name: String): PendingIntent = PendingIntent.getService(this, name.hashCode(),
        Intent(this, ReaderNarrationService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification(): Notification {
        val state = snapshot.value
        val native = chapter != null || runCatching { state.locator?.let(::JSONObject)?.has("native_text") == true }.getOrDefault(false)
        val target = if (native) NativeTextReaderActivity::class.java else ReaderActivity::class.java
        val open = PendingIntent.getActivity(this, 0, Intent(this, target).putExtra("assetId", state.assetId)
            .putExtra("novelLocator", state.locator), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        media.setSessionActivity(open)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.title.ifBlank { "阅读朗读" }).setContentText(state.error ?: if (state.playing) "正在朗读" else "已暂停")
            .setContentIntent(open).setOngoing(state.playing).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "上一句", action(PREVIOUS)).build())
            .addAction(Notification.Action.Builder(if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (state.playing) "暂停" else "继续", action(if (state.playing) PAUSE else PLAY)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "下一句", action(NEXT)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "结束", action(STOP)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0, 1, 2)).build()
    }
    private fun updateNotification() {
        if (!::media.isInitialized || destroying) return
        val state = snapshot.value
        media.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, if (state.playing) state.speed else 0f).build())
        media.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, state.title).build())
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }
    override fun onDestroy() {
        destroying = true
        unregisterReceiver(noisy); job?.cancel(); timer?.cancel(); idle?.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        speech.close(); media.release(); publication?.close(); scope.cancel()
        snapshot.value = snapshot.value.copy(playing = false, deadline = null)
        activeSession = null
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "reader-narration"
        private const val NOTIFICATION = 7319
        const val PLAY = "reader.play"
        const val PAUSE = "reader.pause"
        const val STOP = "reader.stop"
        const val NEXT = "reader.next"
        const val PREVIOUS = "reader.previous"
        const val OPTIONS = "reader.options"
        internal var activeSession: Pair<String, ProgressSession>? = null
            private set
        private val snapshot = MutableStateFlow(NarrationState())
        internal val state = snapshot.asStateFlow()
        internal fun command(context: Context, action: String, asset: String? = null, locator: String? = null, options: Intent.() -> Unit = {}) {
            val intent = Intent(context, ReaderNarrationService::class.java).setAction(action).apply {
                if (asset != null) putExtra("asset", asset)
                if (locator != null) putExtra("locator", locator)
                options()
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
internal fun narrationSentenceStep(text: String, offset: Int, direction: Int): Int {
    val point = safeTextOffset(text, offset)
    val boundaries = charArrayOf('。', '！', '？', '\n', '.', '!', '?')
    return if (direction > 0) text.indexOfAny(boundaries, point).let { if (it < 0) text.length else it + 1 }
    else if (point <= 1) 0 else text.lastIndexOfAny(boundaries, point - 2).let { if (it < 0) 0 else it + 1 }
}
