@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package top.cylunex.shadowmedia.audio

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.datasource.DataSource
import androidx.media3.session.*
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import top.cylunex.shadowmedia.database.*
import top.cylunex.shadowmedia.library.*
import top.cylunex.shadowmedia.model.AudioMode

/** One application-looper player owns the queue; the UI only issues controller commands. */
class AudiobookService : MediaLibraryService() {
    private val audible = top.cylunex.shadowmedia.model.PlaybackCoordinator.process.participant { if (::player.isInitialized) player.pause() }
    private lateinit var player: ExoPlayer
    private lateinit var library: LibraryRepository
    private lateinit var queueDao: AudioQueueDao
    private lateinit var tree: AudioLibraryTree
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sleepDeadline = 0L
    private var stopAfterTrack = false
    private var chapterStop: Pair<String, Long>? = null
    private var mode = AudioMode.AUDIOBOOK
    private val candidates = AudioCandidateSessions()
    private lateinit var listening: ListeningTracker
    private var initialized = false
    private var applyingQueue = false
    private var queueRequest = 0L
    private var progressSession: ProgressSession? = null
    private var progressEntry: String? = null
    private var progressJob: Job? = null
    private var restoring: Job? = null
    private var pendingResumption: RestoredQueue? = null

    override fun onCreate() {
        super.onCreate()
        library = LibraryRepository(this)
        queueDao = ShadowMediaDatabase.create(this).audioQueueDao()
        listening = ListeningTracker(ShadowMediaDatabase.create(this).musicDao())
        mode = runCatching { AudioMode.valueOf(getSharedPreferences("audio_preferences", MODE_PRIVATE).getString("mode", "AUDIOBOOK")!!) }.getOrDefault(AudioMode.AUDIOBOOK)
        val factory = DefaultMediaSourceFactory(DataSource.Factory { LibraryAudioDataSource(this, library, candidates) })
        player = ExoPlayer.Builder(this).setMediaSourceFactory(factory).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    currentMediaItem?.audioMode()?.let { active ->
                        if (mode != active) {
                            mode = active
                            clearSleep()
                            setAudioAttributes(AudioAttributes.Builder().setContentType(if (mode == AudioMode.MUSIC) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build(), true)
                            setPlaybackSpeed(1f)
                        }
                        getSharedPreferences("audio_preferences", MODE_PRIVATE).edit().putString("mode", active.name).apply()
                    }
                    pendingResumption?.let { restored ->
                        if ((0 until mediaItemCount).map { getMediaItemAt(it).entryId() } == restored.items.map { it.entryId() }) {
                            pendingResumption = null
                            restoreParameters(restored)
                        }
                    }
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (oldPosition.mediaItem?.entryId() != newPosition.mediaItem?.entryId() || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        savePosition(oldPosition.mediaItem, oldPosition.positionMs, null,
                            reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION, stopped = true)
                        clearProgressSession()
                    } else if (reason == Player.DISCONTINUITY_REASON_SEEK) saveCurrent(playbackState == Player.STATE_ENDED)
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (stopAfterTrack && reason in setOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) { pause(); clearSleep() }
                    AudioDiagnostics.method.value = ""
                    AudioDiagnostics.format.value = ""
                    listening.sample(this@apply, transition = true)
                    if (chapterStop != null && chapterStop?.first != mediaItem?.entryId()) { pause(); clearSleep() }
                    if (!applyingQueue) {
                        val speed = if (mediaItem?.audioMode() == AudioMode.MUSIC) 1f else mediaItem?.mediaId?.let { getSharedPreferences("audio_preferences", MODE_PRIVATE).getFloat("speed:$it", 1f) } ?: 1f
                        setPlaybackSpeed(speed.takeIf { it.isFinite() && it in .5f..3f } ?: 1f)
                    }
                    startProgressSession()
                }
                override fun onPlayerError(error: PlaybackException) {
                    val entry = currentMediaItem?.entryId() ?: return
                    if (candidates.advance(entry)) {
                        val position = currentPosition
                        val requestedPlay = playWhenReady
                        val current = requireNotNull(currentMediaItem)
                        val uri = current.localConfiguration?.uri ?: return
                        // A new representation has different byte offsets. Rebuild this source and seek in media time.
                        replaceMediaItem(currentMediaItemIndex, current.buildUpon().setUri(uri.buildUpon().appendQueryParameter("retry", UUID.randomUUID().toString()).build()).build())
                        prepare(); seekTo(position); playWhenReady = requestedPlay
                    }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) startProgressSession()
                    if (playbackState == Player.STATE_ENDED) { saveCurrent(true); clearSleep() }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { saveCurrent(playbackState == Player.STATE_ENDED) }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (playWhenReady) audible.claim() else audible.abandon(); saveCurrent(playbackState == Player.STATE_ENDED) }
                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    if (!applyingQueue && mode != AudioMode.MUSIC) currentMediaItem?.mediaId?.let {
                        getSharedPreferences("audio_preferences", MODE_PRIVATE).edit().putFloat("speed:$it", playbackParameters.speed).apply()
                    }
                }
                override fun onEvents(player: Player, events: Player.Events) {
                    AudioDiagnostics.format.value = audioFormat?.let { format ->
                        listOfNotNull(format.sampleMimeType, format.codecs, format.sampleRate.takeIf { it > 0 }?.let { "$it Hz" }, format.channelCount.takeIf { it > 0 }?.let { "$it 声道" }).joinToString(" · ")
                    }.orEmpty()
                    listening.sample(player); persistQueue()
                }
            })
        }
        tree = AudioLibraryTree(ShadowMediaDatabase.create(this)) { asset -> resolveItem(MediaItem.Builder().setMediaId(asset.id).build(), asset) }
        val builder = MediaLibrarySession.Builder(this, player, Callback())
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setSessionActivity(PendingIntent.getActivity(this, 91, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        session = builder.build()
        restoring = scope.launch {
            val request = queueRequest
            try {
                val restored = loadQueue()
                // A new user queue always wins over slow startup restoration.
                if (request == queueRequest && player.mediaItemCount == 0) applyQueue(restored)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { AudioQueueStatus.message.value = "上次播放队列暂时无法恢复" }
        }
        scope.launch {
            var tick = 0
            while (isActive) {
                delay(1000)
                listening.sample(player)
                chapterStop?.let { (entry, end) ->
                    if (entry != player.currentMediaItem?.entryId() || player.currentPosition >= end) { player.pause(); clearSleep() }
                }
                if (sleepDeadline > 0 && SystemClock.elapsedRealtime() >= sleepDeadline) { player.pause(); clearSleep() }
                AudioSleep.remaining.value = when { chapterStop != null -> -2; stopAfterTrack -> -1; sleepDeadline > 0 -> (sleepDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0); else -> 0 }
                if (player.isPlaying && ++tick % 5 == 0) { startProgressSession(); saveCurrent(); persistQueue() }
            }
        }
    }

    private fun clearProgressSession() {
        progressJob?.cancel(); progressJob = null; progressEntry = null; progressSession = null
    }
    private fun startProgressSession() {
        val item = player.currentMediaItem ?: return
        if (player.playbackState != Player.STATE_READY || progressEntry == item.entryId()) return
        clearProgressSession()
        progressEntry = item.entryId()
        progressJob = scope.launch {
            try {
                val asset = requireNotNull(library.dao.asset(item.mediaId))
                check(asset.revision == item.resourceRevision()) { "音频版本已变化" }
                val token = ProgressWriter.begin(library, asset)
                if (player.currentMediaItem?.entryId() == item.entryId()) {
                    progressSession = token
                    saveCurrent(player.playbackState == Player.STATE_ENDED)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (progressEntry == item.entryId()) { progressEntry = null; progressSession = null }
                AudioQueueStatus.message.value = "音频进度暂时无法保存，播放期间会重试"
            }
        }
    }
    private fun saveCurrent(completed: Boolean = false) {
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_ENDED) return
        savePosition(player.currentMediaItem, player.currentPosition, player.duration.takeIf { it > 0 }, completed, stopped = completed)
    }
    private fun savePosition(item: MediaItem?, position: Long, duration: Long?, completed: Boolean, stopped: Boolean = false) {
        if (item == null || item.entryId() != progressEntry) return
        val token = progressSession ?: return
        val locator = JSONObject().put("trackId", item.mediaId).put("positionMs", position.coerceAtLeast(0)).put("durationMs", duration)
        ProgressWriter.save(library, token, "time", locator, duration?.let { position.toDouble() / it }, completed)
        LibraryResources.audioEvent?.invoke(AudioProgressSnapshot(item.mediaId, position.coerceAtLeast(0), !player.isPlaying,
            player.isCurrentMediaItemSeekable, player.playbackState == Player.STATE_READY || completed, stopped, requireNotNull(item.entryId())))
    }
    private fun clearSleep() { sleepDeadline = 0; stopAfterTrack = false; chapterStop = null; AudioSleep.remaining.value = 0 }

    private suspend fun resolveItem(item: MediaItem, asset: LibraryAssetEntity?, entryId: String = UUID.randomUUID().toString(), revision: String? = null): MediaItem {
        if (asset == null) throw QueueEntryUnavailable("音频已从书库移除")
        if (asset.kind !in AudioMode.entries.map { it.name }) throw QueueEntryUnavailable("条目不是音频")
        if (revision != null && asset.revision != revision) throw QueueEntryUnavailable("音频版本已变化")
        // Stable URI only; network resolution stays on the Media3 loader thread.
        val uri = Uri.parse(asset.localUri.ifBlank { "shadow-audio://${asset.id}/audio.${asset.format}?revision=${Uri.encode(asset.revision)}&entry=$entryId" })
        if (uri.scheme !in setOf("content", "file", "shadow-audio")) throw QueueEntryUnavailable("音频资源引用无效")
        val db = ShadowMediaDatabase.create(this)
        val chapters = db.chapterDao().chapters(asset.id, asset.revision)
        val music = if (asset.kind == "MUSIC") db.musicDao().track(asset.id) else null
        val extras = Bundle().apply { putString(ENTRY_ID, entryId); putString(REVISION, asset.revision); putString(MODE, asset.kind)
            putParcelableArrayList("shadow.audio.chapters", ArrayList(chapters.map { chapter -> Bundle().apply {
                putString("id", chapter.chapterId); putString("title", chapter.title); putLong("start", chapter.startMs); putLong("end", chapter.endMs ?: -1)
            } }))
        }
        return MediaItem.Builder().setMediaId(asset.id).setUri(uri).setMediaMetadata(MediaMetadata.Builder()
            .setTitle(asset.title).setArtist(music?.artist ?: asset.author).setAlbumTitle(music?.album).setAlbumArtist(music?.albumArtist).setTrackNumber(music?.track).setDiscNumber(music?.disc).setIsPlayable(true).setIsBrowsable(false).setMediaType(if (asset.kind == "MUSIC") MediaMetadata.MEDIA_TYPE_MUSIC else MediaMetadata.MEDIA_TYPE_AUDIO_BOOK).setExtras(extras)
            .setArtworkUri(asset.coverPath.takeIf(String::isNotBlank)?.let { Uri.fromFile(java.io.File(it)) }).build()).build()
    }

    private suspend fun assetMap(ids: List<String>): Map<String, LibraryAssetEntity> =
        ids.distinct().chunked(400).flatMap { library.dao.assetsByIds(it) }.associateBy { it.id }

    private suspend fun resolveItems(items: List<MediaItem>): List<MediaItem> {
        val assets = assetMap(items.map { it.mediaId })
        val resolved = items.map { resolveItem(it, assets[it.mediaId]) }
        require(resolved.map { it.audioMode() }.distinct().size <= 1) { "音乐与听书分别保存队列，请先切换模式" }
        return resolved
    }

    private class QueueEntryUnavailable(message: String) : IllegalArgumentException(message)
    private data class RestoredQueue(val snapshot: AudioQueueSnapshot, val items: List<MediaItem>)
    private suspend fun loadQueue(targetMode: AudioMode = mode): RestoredQueue {
        AudioQueuePersistence.flush()
        val saved = queueDao.load(targetMode.name) ?: if (targetMode == AudioMode.AUDIOBOOK) migrateLegacyQueue()
            else AudioQueueSnapshot(AudioQueueEntity(targetMode.name, null, 0, 1f, Player.REPEAT_MODE_OFF, false), emptyList())
        val assets = assetMap(saved.entries.map { it.assetId })
        val items = mutableMapOf<String, MediaItem>()
        for (entry in saved.entries.sortedBy { it.ordinal }) {
            try {
                if (assets[entry.assetId]?.kind != targetMode.name) throw QueueEntryUnavailable("音频分类已变化")
                items[entry.entryId] = resolveItem(MediaItem.Builder().setMediaId(entry.assetId).build(), assets[entry.assetId], entry.entryId, entry.resourceRevision) }
            catch (e: CancellationException) { throw e }
            catch (_: QueueEntryUnavailable) { /* Missing/replaced assets are skipped, unreachable sources are kept. */ }
        }
        val normalized = saved.retaining(items.keys)
        if (items.size != saved.entries.size) AudioQueueStatus.message.value = "已跳过移除或版本变化的音频条目"
        return RestoredQueue(normalized, normalized.entries.map { requireNotNull(items[it.entryId]) })
    }
    private suspend fun migrateLegacyQueue(): AudioQueueSnapshot {
        val prefs = getSharedPreferences("audio_queue", MODE_PRIVATE)
        val ids = runCatching { JSONArray(prefs.getString("ids", "[]")) }.getOrElse { JSONArray() }
        val entries = mutableListOf<AudioQueueEntryEntity>()
        val legacyIds = (0 until ids.length().coerceAtMost(2000)).map { ids.optString(it) }
        val assets = assetMap(legacyIds)
        for (id in legacyIds) {
            val asset = assets[id]?.takeIf { it.kind == "AUDIOBOOK" } ?: continue
            entries += AudioQueueEntryEntity(UUID.randomUUID().toString(), AUDIOBOOK_QUEUE, asset.id, asset.revision, entries.size, entries.size)
        }
        val selected = entries.firstOrNull { it.assetId == prefs.getString("selected", null) } ?: entries.firstOrNull()
        val progress = selected?.let { library.dao.progress(it.assetId) }?.takeUnless { it.completed }
        val position = progress?.let { runCatching { JSONObject(it.locatorJson).optLong("positionMs", 0) }.getOrDefault(0) } ?: 0
        val speed = selected?.let { getSharedPreferences("audio_preferences", MODE_PRIVATE).getFloat("speed:${it.assetId}", 1f) } ?: 1f
        val snapshot = AudioQueueSnapshot(AudioQueueEntity(AUDIOBOOK_QUEUE, selected?.entryId, position, speed, Player.REPEAT_MODE_OFF, false), entries)
        queueDao.save(snapshot)
        prefs.edit().remove("ids").remove("selected").apply()
        return snapshot
    }
    private fun applyQueue(restored: RestoredQueue) {
        applyingQueue = true
        try {
            val queue = restored.snapshot.queue
            mode = AudioMode.valueOf(queue.id)
            getSharedPreferences("audio_preferences", MODE_PRIVATE).edit().putString("mode", mode.name).apply()
            val index = restored.items.indexOfFirst { it.entryId() == queue.currentEntryId }.coerceAtLeast(0)
            player.setMediaItems(restored.items, index, queue.positionMs)
            restoreParameters(restored)
            // Reconnecting restores metadata/position only. Explicit play prepares the player.
            initialized = true
        } finally { applyingQueue = false }
        persistQueue()
    }
    private fun restoreParameters(restored: RestoredQueue) {
        val wasApplying = applyingQueue
        applyingQueue = true
        try {
            player.repeatMode = restored.snapshot.queue.repeatMode
            val order = restored.snapshot.entries.sortedBy { it.shuffleOrdinal }.map { it.ordinal }.toIntArray()
            player.setShuffleOrder(DefaultShuffleOrder(order, 0L))
            player.shuffleModeEnabled = restored.snapshot.queue.shuffleEnabled
            player.setPlaybackSpeed(restored.snapshot.queue.speed)
        } finally { applyingQueue = wasApplying }
    }
    private fun persistQueue() {
        if (!initialized || applyingQueue) return
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        // Never persist MediaSession's unresolved placeholder items.
        if (items.any { it.entryId() == null }) return
        val timeline = player.currentTimeline
        val order = mutableMapOf<Int, Int>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && index !in order && order.size < items.size) {
            order[index] = order.size
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        val entries = items.mapIndexed { i, item -> AudioQueueEntryEntity(requireNotNull(item.entryId()), mode.name,
            item.mediaId, item.resourceRevision(), i, order[i] ?: i) }
        AudioQueuePersistence.save(queueDao, AudioQueueSnapshot(AudioQueueEntity(mode.name,
            player.currentMediaItem?.entryId(), if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0), player.playbackParameters.speed,
            player.repeatMode, player.shuffleModeEnabled), entries))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onTaskRemoved(rootIntent: android.content.Intent?) { if (!player.playWhenReady) stopSelf() }
    override fun onDestroy() {
        persistQueue(); saveCurrent(player.playbackState == Player.STATE_ENDED)
        player.currentMediaItem?.let { LibraryResources.audioEvent?.invoke(AudioProgressSnapshot(it.mediaId,
            player.currentPosition.coerceAtLeast(0), true, player.isCurrentMediaItemSeekable, false, true, it.entryId() ?: it.mediaId)) }
        listening.sample(player, transition = true)
        initialized = false
        scope.cancel(); audible.close(); candidates.clear(); clearSleep(); session?.release(); player.release(); super.onDestroy()
    }
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = scope.launch {
            try { future.set(block()) }
            catch (e: CancellationException) { future.cancel(false); throw e }
            catch (e: Exception) { future.setException(e) }
        }
        future.addListener({ if (future.isCancelled) job.cancel() }, androidx.core.content.ContextCompat.getMainExecutor(this))
        return future
    }
    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(tree.root, params))
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            LibraryResult.ofItemList(tree.children(parentId, page, pageSize.coerceIn(1, 200)), params)
        }
        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = future {
            val count = library.dao.searchCount("MUSIC", "%" + query.replace("%", "\\%").replace("_", "\\_") + "%")
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid(params)
        }
        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            LibraryResult.ofItemList(tree.children("songs", page, pageSize.coerceIn(1, 200), query), params)
        }
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(SessionCommand(SLEEP, Bundle.EMPTY)).add(SessionCommand(SWITCH_MODE, Bundle.EMPTY)).build()).build()
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (customCommand.customAction == SWITCH_MODE) {
                val target = runCatching { AudioMode.valueOf(args.getString("mode").orEmpty()) }.getOrNull()
                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                val request = ++queueRequest
                pendingResumption = null
                player.pause(); saveCurrent(); persistQueue()
                return future {
                    val restored = loadQueue(target)
                    check(request == queueRequest) { "已切换播放队列" }
                    applyQueue(restored)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            if (customCommand.customAction != SLEEP) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            val minutes = args.getInt("minutes")
            clearSleep()
            if (minutes == -2) {
                val end = player.audioChapters().chapterAt(player.currentPosition)?.endMs
                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                chapterStop = requireNotNull(player.currentMediaItem?.entryId()) to end
            } else if (minutes == -1) stopAfterTrack = true
            else if (minutes in 1..240) sleepDeadline = SystemClock.elapsedRealtime() + minutes * 60_000L
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
            val request = ++queueRequest
            pendingResumption = null
            return future {
                require(player.mediaItemCount + mediaItems.size <= 2000) { "播放队列最多容纳 2000 个条目" }
                val resolved = resolveItems(mediaItems)
                require(player.mediaItemCount == 0 || resolved.all { it.audioMode() == mode }) { "请先切换音频模式再加入队列" }
                check(request == queueRequest) { "已切换播放队列" }
                initialized = true
                AudioQueueStatus.message.value = null
                resolved
            }
        }
        override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val request = ++queueRequest
            pendingResumption = null
            saveCurrent(player.playbackState == Player.STATE_ENDED)
            persistQueue()
            return future {
                require(mediaItems.size <= 2000) { "单次最多播放 2000 个已加载条目" }
                ProgressWriter.flush()
                val resolved = resolveItems(mediaItems)
                val index = startIndex.takeIf { it in resolved.indices } ?: 0
                val progress = resolved.getOrNull(index)?.let { library.dao.progress(it.mediaId) }?.takeUnless { it.completed || resolved.getOrNull(index)?.audioMode() == AudioMode.MUSIC }
                val position = if (startPositionMs == C.TIME_UNSET) progress?.let {
                    runCatching { JSONObject(it.locatorJson).optLong("positionMs", 0) }.getOrDefault(0)
                } ?: 0 else startPositionMs.coerceAtLeast(0)
                check(request == queueRequest) { "已切换播放队列" }
                initialized = true
                AudioQueueStatus.message.value = null
                MediaSession.MediaItemsWithStartPosition(resolved, index, position)
            }
        }
        override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val request = queueRequest
            return future {
                restoring?.join()
                val restored = loadQueue()
                check(request == queueRequest) { "已切换播放队列" }
                if (isForPlayback) { pendingResumption = restored; initialized = true }
                val index = restored.items.indexOfFirst { it.entryId() == restored.snapshot.queue.currentEntryId }.coerceAtLeast(0)
                val items = if (isForPlayback) restored.items else listOfNotNull(restored.items.getOrNull(index))
                MediaSession.MediaItemsWithStartPosition(items, if (isForPlayback) index else 0, restored.snapshot.queue.positionMs)
            }
        }
    }
    companion object {
        const val MODE = "shadow.audio.mode"
        const val SWITCH_MODE = "shadow.audio.switchMode"
        const val SLEEP = "shadow.audio.sleep"
        internal const val ENTRY_ID = "shadow.audio.entry"
        internal const val REVISION = "shadow.audio.revision"
    }
}

fun MediaItem.audioMode(): AudioMode = runCatching { AudioMode.valueOf(mediaMetadata.extras?.getString(AudiobookService.MODE).orEmpty()) }.getOrDefault(AudioMode.AUDIOBOOK)
internal fun MediaItem.entryId(): String? = mediaMetadata.extras?.getString(AudiobookService.ENTRY_ID)
internal fun MediaItem.resourceRevision(): String = mediaMetadata.extras?.getString(AudiobookService.REVISION).orEmpty()
object AudioQueueStatus {
    internal val message = MutableStateFlow<String?>(null)
    val recoveryMessage = message.asStateFlow()
    val persistenceError = AudioQueuePersistence.error
}
object AudioSleep {
    internal val remaining = MutableStateFlow(0L)
    val remainingMs = remaining.asStateFlow()
}

object AudiobookController {
    fun connect(context: Context): ListenableFuture<MediaController> = MediaController.Builder(context,
        SessionToken(context, ComponentName(context, AudiobookService::class.java))).buildAsync()

    fun play(controller: MediaController, ids: List<String>, selected: String, mode: AudioMode = AudioMode.AUDIOBOOK) {
        require(selected in ids && ids.size <= 2000)
        val sameQueue = ids.size == 1 || (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } == ids
        if (mode != AudioMode.MUSIC && sameQueue && controller.currentMediaItem?.mediaId == selected && controller.playbackState in setOf(Player.STATE_READY, Player.STATE_BUFFERING) && controller.playerError == null) {
            controller.play()
            return
        }
        // No suspension between the user's request and play: a later pause stays later in the
        // MediaSession command order, even while its onSetMediaItems reads local progress.
        controller.setMediaItems(ids.map { MediaItem.Builder().setMediaId(it).build() }, ids.indexOf(selected), C.TIME_UNSET)
        controller.prepare(); controller.play()
    }
    fun restoreMode(controller: MediaController, mode: AudioMode) {
        controller.sendCustomCommand(SessionCommand(AudiobookService.SWITCH_MODE, Bundle.EMPTY), Bundle().apply { putString("mode", mode.name) })
    }
    fun enqueue(controller: MediaController, ids: List<String>, next: Boolean) {
        val items = ids.map { MediaItem.Builder().setMediaId(it).build() }
        if (next) controller.addMediaItems((controller.currentMediaItemIndex + 1).coerceIn(0, controller.mediaItemCount), items)
        else controller.addMediaItems(items)
    }
    fun sleep(controller: MediaController, minutes: Int) {
        controller.sendCustomCommand(SessionCommand(AudiobookService.SLEEP, Bundle.EMPTY), Bundle().apply { putInt("minutes", minutes) })
    }
}

object AudioDiagnostics {
    internal val method = MutableStateFlow("")
    internal val format = MutableStateFlow("")
    val playbackMethod = method.asStateFlow()
    val decoderInput = format.asStateFlow()
}
