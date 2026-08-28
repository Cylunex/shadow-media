package top.cylunex.shadowmedia.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import top.cylunex.shadowmedia.model.ExternalMediaEntry

/**
 * An intentionally isolated player for user-imported media URLs.
 *
 * Its client has no Emby interceptor, cookie jar, session store, or playback reporting path.
 */
class ExternalPlaybackRuntime(
    context: Context,
    entry: ExternalMediaEntry,
) : Closeable {
    private val errorState = MutableStateFlow<String?>(null)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val exoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.DEFAULT, true)
        setHandleAudioBecomingNoisy(true)
        setWakeMode(C.WAKE_MODE_LOCAL)
        repeatMode = Player.REPEAT_MODE_OFF
        addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorState.value = error.cause?.message ?: error.message
            }
        })
        val dataSourceFactory = OkHttpDataSource.Factory(client)
            .setUserAgent("Shadow Media")
            .setDefaultRequestProperties(entry.requestHeaders)
        setMediaSource(
            DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(entry.url))
        )
        prepare()
        playWhenReady = true
    }

    val player: Player = exoPlayer
    val error: StateFlow<String?> = errorState.asStateFlow()

    override fun close() {
        exoPlayer.release()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
