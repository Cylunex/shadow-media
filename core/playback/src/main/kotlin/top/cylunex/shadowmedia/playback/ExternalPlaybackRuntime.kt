package top.cylunex.shadowmedia.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.session.MediaSession
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.cylunex.shadowmedia.model.ExternalMediaEntry
import top.cylunex.shadowmedia.model.NetworkPolicy
import top.cylunex.shadowmedia.network.NetworkReadHandle
import top.cylunex.shadowmedia.network.NetworkStorageRepository

/**
 * An intentionally isolated player for user-imported media URLs.
 *
 * Its client has no Emby interceptor, cookie jar, session store, or playback reporting path.
 */
class ExternalPlaybackRuntime(
    context: Context,
    entry: ExternalMediaEntry,
    networkStorageRepository: NetworkStorageRepository? = null,
    startPlayWhenReady: Boolean = true,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var released = false
    private val networkMedia = entry.url.substringBefore(':').lowercase() in setOf("http", "https", "shadow-smb")
    private val audible: top.cylunex.shadowmedia.model.PlaybackCoordinator.Participant = top.cylunex.shadowmedia.model.PlaybackCoordinator.process.participant { exoPlayer.pause() }
    private val errorState = MutableStateFlow<String?>(null)
    private val client = OkHttpClient.Builder().addInterceptor(top.cylunex.shadowmedia.network.OfflineModeInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor { chain ->
            val origin = (entry.credentialOrigin ?: entry.url).toHttpUrlOrNull()
            val request = chain.request()
            val scoped = request.newBuilder().headers(externalHeaders(origin, request.url, request.headers)).build()
            chain.proceed(scoped)
        }
        .build()
    private val httpFactory = OkHttpDataSource.Factory(client)
        .setUserAgent("Shadow Media")
        .setDefaultRequestProperties(entry.requestHeaders)
    private val dataSourceFactory = DataSource.Factory {
        DefaultDataSource(context, RoutingDataSource(httpFactory.createDataSource(), networkStorageRepository))
    }
    private val sourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
    private var mediaItem = MediaItem.Builder().setUri(entry.url).setMediaId(entry.id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).build())
        .setSubtitleConfigurations(entry.subtitles.mapIndexed { index, subtitle -> subtitle.configuration(index == 0) }).build()
    private var pendingSubtitle: top.cylunex.shadowmedia.model.ExternalSubtitle? = null
    private val exoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.DEFAULT, true)
        setHandleAudioBecomingNoisy(true)
        setWakeMode(C.WAKE_MODE_LOCAL)
        repeatMode = Player.REPEAT_MODE_OFF
    }
    private val recoveryController = PlaybackRecovery(scope, { !networkMedia || !NetworkPolicy.offlineOnly }) {
        prepareCurrent()
    }
    private val networkMonitor = if (networkMedia) PlaybackNetworkMonitor(context, recoveryController::setNetworkAvailable) else null
    val recovery: StateFlow<PlaybackRecoveryState> = recoveryController.state

    init {
        recoveryController.setPlayWhenReady(startPlayWhenReady)
        exoPlayer.apply {
            addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (playWhenReady) audible.claim() else audible.abandon()
                    recoveryController.setPlayWhenReady(playWhenReady)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        errorState.value = null
                        recoveryController.ready()
                    }
                }
                override fun onTracksChanged(tracks: Tracks) {
                    val requested = pendingSubtitle ?: return
                    for (group in tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }) {
                        val index = (0 until group.length).firstOrNull {
                            group.isTrackSupported(it) && group.getTrackFormat(it).label == requested.label
                        } ?: continue
                        pendingSubtitle = null
                        trackSelectionParameters = trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
                        break
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (released) return
                    val failure = classifyPlaybackFailure(error, networkMedia && NetworkPolicy.offlineOnly)
                    if (networkMedia && recoveryController.recover(failure)) return
                    if (isExternalSubtitleFailure(error, mediaItem)) {
                        // Only a failure attributable to a sidecar may discard optional subtitles.
                        val position = currentPosition.coerceAtLeast(0)
                        val playing = playWhenReady
                        pendingSubtitle = null
                        mediaItem = mediaItem.buildUpon().setSubtitleConfigurations(emptyList()).build()
                        setMediaSource(sourceFactory.createMediaSource(mediaItem), position)
                        prepare()
                        playWhenReady = playing
                    } else errorState.value = failure.message
                }
            })
            setMediaSource(sourceFactory.createMediaSource(mediaItem))
            if (entry.startPositionMs > 0) seekTo(entry.startPositionMs)
            prepare()
            playWhenReady = startPlayWhenReady
        }
    }

    private val mediaSession = MediaSession.Builder(context, exoPlayer).setId("external-video-${java.util.UUID.randomUUID()}").build()

    val player: Player = exoPlayer
    val error: StateFlow<String?> = errorState.asStateFlow()

    fun setForeground(active: Boolean) = recoveryController.setForeground(active)

    fun retry() {
        if (!released && recoveryController.manualRetry()) prepareCurrent()
    }

    private fun prepareCurrent() {
        if (released) return
        val position = if (exoPlayer.isCurrentMediaItemLive) C.TIME_UNSET else exoPlayer.currentPosition.coerceAtLeast(0L)
        val playing = exoPlayer.playWhenReady
        errorState.value = null
        exoPlayer.setMediaSource(sourceFactory.createMediaSource(mediaItem), position)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playing
    }

    fun addSubtitle(subtitle: top.cylunex.shadowmedia.model.ExternalSubtitle) {
        if (released) return
        recoveryController.ready()
        val position = exoPlayer.currentPosition
        val playing = exoPlayer.playWhenReady
        pendingSubtitle = subtitle
        errorState.value = null
        mediaItem = mediaItem.buildUpon().setSubtitleConfigurations(
            mediaItem.localConfiguration?.subtitleConfigurations.orEmpty().filterNot { it.uri.toString() == subtitle.uri } + subtitle.configuration(true)
        ).build()
        exoPlayer.setMediaSource(sourceFactory.createMediaSource(mediaItem), position)
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playing
    }

    private fun top.cylunex.shadowmedia.model.ExternalSubtitle.configuration(default: Boolean) =
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri)).setMimeType(mimeType).setLabel(label)
            .setId("shadow-sidecar-${java.util.UUID.randomUUID()}")
            .setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0).build()

    override fun close() {
        if (released) return
        released = true
        recoveryController.close()
        networkMonitor?.close()
        scope.cancel()
        audible.close()
        mediaSession.release()
        exoPlayer.release()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

class RoutingDataSource(
    private val http: DataSource,
    private val networkStorageRepository: NetworkStorageRepository?,
) : DataSource {
    private var active: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        http.addTransferListener(transferListener)
    }
    companion object { var offlineFactory: DataSource.Factory? = null }

    override fun open(dataSpec: DataSpec): Long {
        val delegate = if (dataSpec.uri.scheme == "shadow-cached") {
            requireNotNull(offlineFactory) { "离线播放服务尚未初始化" }.createDataSource().also { value -> listeners.forEach(value::addTransferListener) }
        } else if (dataSpec.uri.scheme == "shadow-smb") {
            val repository = networkStorageRepository ?: throw java.io.IOException("SMB 播放服务未初始化")
            SmbDataSource(repository).also { source -> listeners.forEach(source::addTransferListener) }
        } else http
        active = delegate
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int =
        active?.read(buffer, offset, readLength) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

    override fun close() {
        active?.close()
        active = null
    }
}

private class SmbDataSource(
    private val repository: NetworkStorageRepository,
) : BaseDataSource(false) {
    private var handle: NetworkReadHandle? = null
    private var opened = false
    private var currentUri: Uri? = null
    private var remaining: Long = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri
        handle = repository.openSmbResource(dataSpec.uri.toString(), dataSpec.position)
        val available = handle?.remainingLength
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() && available != null -> minOf(dataSpec.length, available)
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            available != null -> available
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val allowed = if (remaining == C.LENGTH_UNSET.toLong()) readLength else minOf(readLength.toLong(), remaining).toInt()
        val read = handle?.read(buffer, offset, allowed) ?: C.RESULT_END_OF_INPUT
        if (read < 0) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        runCatching { handle?.close() }
        handle = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
