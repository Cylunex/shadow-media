package com.shadow.media.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

enum class PlaybackFailureKind { NETWORK, TIMEOUT, SERVER, ACCESS, MISSING, FORMAT, DECODER, OFFLINE, UNKNOWN }

/** Messages are deliberately independent of exception text, URLs and response bodies. */
data class PlaybackFailure(
    val kind: PlaybackFailureKind,
    val message: String,
    val canAutoRetry: Boolean = false,
)

fun classifyPlaybackFailure(error: PlaybackException, offlineOnly: Boolean): PlaybackFailure {
    if (offlineOnly) return PlaybackFailure(PlaybackFailureKind.OFFLINE, "仅离线模式已开启，请关闭后重试网络视频")
    val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
    causes.filterIsInstance<com.shadow.media.network.NetworkStorageFailureException>().firstOrNull()?.let { failure ->
        return when (failure.kind) {
            com.shadow.media.network.NetworkStorageFailure.TRANSPORT -> PlaybackFailure(PlaybackFailureKind.NETWORK, "网络存储连接已中断", true)
            com.shadow.media.network.NetworkStorageFailure.ACCESS -> PlaybackFailure(PlaybackFailureKind.ACCESS, "网络存储访问被拒绝，请检查账号和共享权限")
            com.shadow.media.network.NetworkStorageFailure.MISSING -> PlaybackFailure(PlaybackFailureKind.MISSING, "网络存储文件已移除，请刷新目录")
            com.shadow.media.network.NetworkStorageFailure.OTHER -> PlaybackFailure(PlaybackFailureKind.UNKNOWN, "网络存储读取失败，请检查连接或文件状态")
        }
    }
    val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    return classifyPlaybackFailure(error.errorCode, offlineOnly, http?.responseCode,
        causes.any { it is SSLException }, causes.any { it is SocketTimeoutException })
}

internal fun classifyPlaybackFailure(
    errorCode: Int,
    offlineOnly: Boolean = false,
    httpStatus: Int? = null,
    secureConnectionFailure: Boolean = false,
    timeout: Boolean = false,
): PlaybackFailure {
    if (offlineOnly) return PlaybackFailure(PlaybackFailureKind.OFFLINE, "仅离线模式已开启，请关闭后重试网络视频")
    if (httpStatus != null) return when (httpStatus) {
        401, 403 -> PlaybackFailure(PlaybackFailureKind.ACCESS, "播放地址已过期或无访问权限，请重新解析或检查来源账号")
        404, 410 -> PlaybackFailure(PlaybackFailureKind.MISSING, "媒体资源不存在或已移除，请刷新来源")
        408, 425, 429, 500, 502, 503, 504 -> PlaybackFailure(PlaybackFailureKind.SERVER, "服务器暂时无法提供视频（HTTP $httpStatus）", true)
        else -> PlaybackFailure(PlaybackFailureKind.SERVER, "服务器拒绝播放请求（HTTP $httpStatus）")
    }
    if (secureConnectionFailure) return PlaybackFailure(PlaybackFailureKind.ACCESS, "安全连接失败，请检查服务器证书和设备时间")
    if (timeout) return PlaybackFailure(PlaybackFailureKind.TIMEOUT, "连接超时，请检查网络或服务器", true)
    return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> PlaybackFailure(PlaybackFailureKind.NETWORK, "网络连接中断或服务器不可达", true)
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackFailure(PlaybackFailureKind.TIMEOUT, "连接超时，请检查网络或服务器", true)
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlaybackFailure(PlaybackFailureKind.ACCESS, "没有读取权限，请重新授权文件或检查来源账号")
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackFailure(PlaybackFailureKind.MISSING, "媒体文件不存在，请刷新来源或重新选择文件")
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> PlaybackFailure(PlaybackFailureKind.FORMAT, "视频或播放列表格式不受支持，或内容已损坏")
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> PlaybackFailure(PlaybackFailureKind.DECODER, "设备无法解码此视频，请尝试其他版本或服务端转码")
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> PlaybackFailure(PlaybackFailureKind.NETWORK, "已离开直播可播放时间范围，正在返回直播", true)
        else -> PlaybackFailure(PlaybackFailureKind.UNKNOWN, "播放失败，请重试或更换来源（错误码 $errorCode）")
    }
}

internal fun isExternalSubtitleFailure(error: PlaybackException, item: MediaItem): Boolean {
    val subtitles = item.localConfiguration?.subtitleConfigurations.orEmpty()
    val textFormatId = if (error is ExoPlaybackException && error.type == ExoPlaybackException.TYPE_RENDERER &&
        androidx.media3.common.MimeTypes.getTrackType(error.rendererFormat?.sampleMimeType) == C.TRACK_TYPE_TEXT) error.rendererFormat?.id else null
    val requestUris = generateSequence<Throwable>(error) { it.cause }.take(16)
        .filterIsInstance<HttpDataSource.HttpDataSourceException>().map { it.dataSpec.uri.toString() }.toList()
    return matchesExternalSubtitleFailure(subtitles.map { it.uri.toString() }.toSet(),
        subtitles.mapNotNull { it.id }.toSet(), requestUris, textFormatId)
}

internal fun matchesExternalSubtitleFailure(
    subtitleUris: Set<String>,
    subtitleFormatIds: Set<String>,
    failedRequestUris: List<String>,
    failedTextFormatId: String?,
): Boolean = (failedTextFormatId != null && failedTextFormatId in subtitleFormatIds) ||
    failedRequestUris.any { it in subtitleUris }
