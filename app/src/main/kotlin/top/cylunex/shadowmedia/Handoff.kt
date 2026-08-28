package top.cylunex.shadowmedia

import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import top.cylunex.shadowmedia.model.MediaKey

class HandoffInbox {
    private val channel = Channel<Uri>(Channel.BUFFERED)
    val intents = channel.receiveAsFlow()

    fun offer(uri: Uri?) {
        if (uri?.scheme == SCHEME && uri.host == HOST) channel.trySend(uri)
    }
}

data class MediaHandoff(
    val key: MediaKey,
    val positionMs: Long,
)

fun MediaKey.toHandoffUri(positionMs: Long = 0): Uri = Uri.Builder()
    .scheme(SCHEME)
    .authority(HOST)
    .appendQueryParameter("provider", providerId)
    .appendQueryParameter("item", itemId)
    .appendQueryParameter("position", positionMs.coerceAtLeast(0).toString())
    .build()

fun Uri.toMediaHandoffOrNull(): MediaHandoff? {
    if (scheme != SCHEME || host != HOST) return null
    val provider = getQueryParameter("provider")?.takeIf { it.length in 1..240 } ?: return null
    val item = getQueryParameter("item")?.takeIf { it.length in 1..480 } ?: return null
    val position = getQueryParameter("position")?.toLongOrNull()?.coerceIn(0, MAX_POSITION_MS) ?: 0
    return MediaHandoff(MediaKey(provider, item), position)
}

private const val SCHEME = "shadowmedia"
private const val HOST = "play"
private const val MAX_POSITION_MS = 30L * 24 * 60 * 60 * 1_000
