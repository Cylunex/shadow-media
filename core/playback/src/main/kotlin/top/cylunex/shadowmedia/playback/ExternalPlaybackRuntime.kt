package top.cylunex.shadowmedia.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.cylunex.shadowmedia.model.ExternalMediaEntry
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
) : Closeable {
    private val audible: top.cylunex.shadowmedia.model.PlaybackCoordinator.Participant = top.cylunex.shadowmedia.model.PlaybackCoordinator.process.participant { exoPlayer.pause() }
    private val errorState = MutableStateFlow<String?>(null)
    private val client = OkHttpClient.Builder()
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
    private val exoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.DEFAULT, true)
        setHandleAudioBecomingNoisy(true)
        setWakeMode(C.WAKE_MODE_LOCAL)
        repeatMode = Player.REPEAT_MODE_OFF
        addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (playWhenReady) audible.claim() else audible.abandon() }
            override fun onPlayerError(error: PlaybackException) {
                errorState.value = error.cause?.message ?: error.message
            }
        })
        val httpFactory = OkHttpDataSource.Factory(client)
            .setUserAgent("Shadow Media")
            .setDefaultRequestProperties(entry.requestHeaders)
        val dataSourceFactory = DataSource.Factory {
            RoutingDataSource(httpFactory.createDataSource(), networkStorageRepository)
        }
        setMediaSource(
            DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(entry.url))
        )
        if (entry.startPositionMs > 0) seekTo(entry.startPositionMs)
        prepare()
        playWhenReady = true
    }

    val player: Player = exoPlayer
    val error: StateFlow<String?> = errorState.asStateFlow()

    override fun close() {
        audible.close()
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

    override fun open(dataSpec: DataSpec): Long {
        val delegate = if (dataSpec.uri.scheme == "shadow-smb") {
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
