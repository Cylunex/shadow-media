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
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import top.cylunex.shadowmedia.library.LibraryRepository
import top.cylunex.shadowmedia.library.LibraryResources
import top.cylunex.shadowmedia.library.ProgressWriter
import top.cylunex.shadowmedia.model.PlaybackCandidate
import top.cylunex.shadowmedia.playback.RoutingDataSource

/** UI/controller never owns the player. Progress is captured before changing tracks. */
class AudiobookService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var library: LibraryRepository
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sleepDeadline = 0L
    private var stopAfterTrack = false
    private val preparedTracks = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        library = LibraryRepository(this)
        val factory = DefaultMediaSourceFactory(DataSource.Factory { LibraryAudioDataSource(this, library) })
        player = ExoPlayer.Builder(this).setMediaSourceFactory(factory).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId) {
                        val previous = oldPosition.mediaItem?.mediaId
                        if (previous != null && preparedTracks.remove(previous)) {
                            save(previous, oldPosition.positionMs, null, reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION, stopped = true)
                        }
                    } else if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        saveCurrent(playbackState == Player.STATE_ENDED)
                    }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (playbackState == Player.STATE_READY) mediaItem?.mediaId?.let(preparedTracks::add)
                    if (stopAfterTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) { pause(); clearSleep() }
                    mediaItem?.mediaId?.let { id ->
                        val speed = getSharedPreferences("audio_preferences", MODE_PRIVATE).getFloat("speed:$id", 1f)
                        setPlaybackSpeed(speed.coerceIn(0.5f, 3f))
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (playbackState == Player.STATE_READY) currentMediaItem?.mediaId?.let(preparedTracks::add)
                    saveCurrent(playbackState == Player.STATE_ENDED)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) { currentMediaItem?.mediaId?.let(preparedTracks::add); saveCurrent() }
                    if (playbackState == Player.STATE_ENDED) { saveCurrent(true); clearSleep() }
                }
                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    currentMediaItem?.mediaId?.let { getSharedPreferences("audio_preferences", MODE_PRIVATE).edit().putFloat("speed:$it", playbackParameters.speed).apply() }
                }
            })
        }
        val builder = MediaSession.Builder(this, player).setCallback(Callback())
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setSessionActivity(PendingIntent.getActivity(this, 91, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        session = builder.build()
        scope.launch {
            var tick = 0
            while (isActive) {
                delay(1000)
                if (sleepDeadline > 0 && SystemClock.elapsedRealtime() >= sleepDeadline) { player.pause(); clearSleep() }
                AudioSleep.remaining.value = when { stopAfterTrack -> -1; sleepDeadline > 0 -> (sleepDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0); else -> 0 }
                if (player.isPlaying && ++tick % 5 == 0) saveCurrent()
            }
        }
    }

    private fun saveCurrent(completed: Boolean = false) {
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_ENDED) return
        save(player.currentMediaItem?.mediaId, player.currentPosition, player.duration.takeIf { it > 0 }, completed, stopped = completed)
    }
    private fun save(id: String?, position: Long, duration: Long?, completed: Boolean, stopped: Boolean = false) {
        if (id.isNullOrBlank()) return
        val locator = JSONObject().put("trackId", id).put("positionMs", position.coerceAtLeast(0)).put("durationMs", duration)
        ProgressWriter.save(library, id, "time", locator, duration?.let { position.toDouble() / it }, completed)
        LibraryResources.audioEvent?.invoke(top.cylunex.shadowmedia.library.AudioProgressSnapshot(id, position.coerceAtLeast(0), !player.isPlaying,
            player.isCurrentMediaItemSeekable, player.playbackState == Player.STATE_READY || completed, stopped))
    }
    private fun clearSleep() { sleepDeadline = 0; stopAfterTrack = false; AudioSleep.remaining.value = 0 }
    private suspend fun resolveItem(item: MediaItem): MediaItem {
        val asset = requireNotNull(library.dao.asset(item.mediaId)) { "音频已从书库移除" }
        require(asset.kind == "AUDIOBOOK")
        // Resolve remote URLs only when that track is actually opened by the loader, not for the
        // entire queue. Signed URLs must not expire while waiting behind hundreds of other tracks.
        val uri = Uri.parse(asset.localUri.ifBlank { "shadow-audio://${asset.id}/audio.${asset.format}" })
        require(uri.scheme in setOf("content", "file", "shadow-audio"))
        if (uri.scheme == "file") library.localFile(asset)
        return item.buildUpon().setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(asset.title).setArtist(asset.author)
            .setArtworkUri(asset.coverPath.takeIf(String::isNotBlank)?.let { Uri.fromFile(java.io.File(it)) }).build()).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: android.content.Intent?) { if (!player.playWhenReady) stopSelf() }
    override fun onDestroy() {
        // Queue a captured snapshot on the process-owned writer; never block service teardown.
        val id = player.currentMediaItem?.mediaId
        val position = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.takeIf { it > 0 }
        if (id != null && player.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED)) ProgressWriter.save(library, id, "time", JSONObject().put("trackId", id).put("positionMs", position).put("durationMs", duration), duration?.let { position.toDouble() / it }, player.playbackState == Player.STATE_ENDED)
        if (id != null) LibraryResources.audioEvent?.invoke(top.cylunex.shadowmedia.library.AudioProgressSnapshot(id, position, true, player.isCurrentMediaItemSeekable, false, true))
        scope.cancel(); clearSleep(); session?.release(); player.release(); super.onDestroy()
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SessionCommand(SLEEP, Bundle.EMPTY)).build()).build()
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (customCommand.customAction != SLEEP) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            val minutes = args.getInt("minutes")
            clearSleep()
            if (minutes == -1) stopAfterTrack = true
            else if (minutes in 1..240) sleepDeadline = SystemClock.elapsedRealtime() + minutes * 60_000L
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
            val future = SettableFuture.create<List<MediaItem>>()
            val job = scope.launch {
                try {
                    val resolved = mediaItems.take(2000).map { item -> resolveItem(item) }
                    future.set(resolved)
                } catch (e: Exception) { future.setException(e) }
            }
            future.addListener({ if (future.isCancelled) job.cancel() }, androidx.core.content.ContextCompat.getMainExecutor(this@AudiobookService))
            return future
        }
    }
    companion object { const val SLEEP = "shadow.audio.sleep" }
}

object AudioSleep {
    internal val remaining = MutableStateFlow(0L)
    val remainingMs = remaining.asStateFlow()
}

object AudiobookController {
    fun connect(context: Context): ListenableFuture<MediaController> = MediaController.Builder(context,
        SessionToken(context, ComponentName(context, AudiobookService::class.java))).buildAsync()

    suspend fun play(context: Context, controller: MediaController, ids: List<String>, selected: String) {
        require(selected in ids && ids.size <= 2000)
        val sameQueue = ids.size == 1 || (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } == ids
        if (sameQueue && controller.currentMediaItem?.mediaId == selected && controller.playbackState in setOf(Player.STATE_READY, Player.STATE_BUFFERING) && controller.playerError == null) {
            controller.play()
            return
        }
        ProgressWriter.flush()
        val library = LibraryRepository(context)
        val progress = library.dao.progress(selected)
        val position = progress?.takeUnless { it.completed }?.let { runCatching { JSONObject(it.locatorJson).optLong("positionMs", 0) }.getOrDefault(0) } ?: 0
        context.getSharedPreferences("audio_queue", Context.MODE_PRIVATE).edit().putString("ids", JSONArray(ids).toString()).putString("selected", selected).apply()
        controller.setMediaItems(ids.map { MediaItem.Builder().setMediaId(it).build() }, ids.indexOf(selected), position)
        controller.prepare(); controller.play()
    }
    fun sleep(controller: MediaController, minutes: Int) {
        controller.sendCustomCommand(SessionCommand(AudiobookService.SLEEP, Bundle.EMPTY), Bundle().apply { putInt("minutes", minutes) })
    }
}
