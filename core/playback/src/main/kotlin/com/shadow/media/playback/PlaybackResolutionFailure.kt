package com.shadow.media.playback

import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import com.shadow.media.network.EmbyApiException

fun classifyPlaybackResolutionFailure(error: Throwable, offlineOnly: Boolean): PlaybackFailure {
    if (offlineOnly) return PlaybackFailure(PlaybackFailureKind.OFFLINE, "仅离线模式已开启，此内容还没有可用的离线副本")
    val causes = generateSequence(error) { it.cause }.take(16).toList()
    val api = causes.filterIsInstance<EmbyApiException>().firstOrNull()
    if (api?.httpStatus != null) return classifyPlaybackFailure(2004, httpStatus = api.httpStatus)
    if (causes.any { it is SSLException }) return classifyPlaybackFailure(2001, secureConnectionFailure = true)
    if (api == null && causes.any { it is IOException }) return classifyPlaybackFailure(if (causes.any { it is SocketTimeoutException }) 2002 else 2001)
    return PlaybackFailure(PlaybackFailureKind.UNKNOWN, "播放资源解析失败，请检查来源或重新选择内容")
}
