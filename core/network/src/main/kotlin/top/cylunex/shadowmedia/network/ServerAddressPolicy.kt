package top.cylunex.shadowmedia.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerAddressPolicy {
    fun validate(rawUrl: String, allowInsecureHttp: Boolean): Result<HttpUrl> = runCatching {
        val normalized = rawUrl.trim().trimEnd('/')
        require(normalized.isNotEmpty()) { "请输入 Emby 服务地址" }
        val url = normalized.toHttpUrlOrNull() ?: error("服务地址格式无效")
        require(url.username.isEmpty() && url.password.isEmpty()) { "服务地址不能包含账号或密码" }
        require(url.query == null && url.fragment == null) { "服务地址不能包含查询参数或片段" }
        require(url.scheme == "https" || url.scheme == "http") { "仅支持 HTTP 或 HTTPS" }
        require(url.scheme == "https" || allowInsecureHttp) {
            "HTTP 连接未加密，请确认这是受信任的局域网后再允许"
        }
        url
    }
}

internal object EmbyEndpoints {
    fun endpoint(serverUrl: String, vararg pathSegments: String): HttpUrl {
        val base = serverUrl.toHttpUrlOrNull() ?: error("Invalid server URL")
        val builder = base.newBuilder().query(null).fragment(null)
        val nonEmptySegments = base.pathSegments.filter(String::isNotEmpty)
        if (nonEmptySegments.lastOrNull()?.equals("emby", ignoreCase = true) != true) {
            builder.addPathSegment("emby")
        }
        pathSegments.forEach(builder::addPathSegment)
        return builder.build()
    }

    fun resolvePlaybackUrl(serverUrl: String, pathOrUrl: String): String {
        val server = serverUrl.toHttpUrlOrNull() ?: error("Invalid server URL")
        val resolved = pathOrUrl.toHttpUrlOrNull()
            ?: server.resolve(pathOrUrl)
            ?: error("Invalid playback URL")
        val route = resolved.pathSegments.embyPlaybackRoute()
        if (route == null) return resolved.toString()

        // PlaybackInfo may contain an absolute URL pointing at Emby's private upstream address.
        // Rebuild known Emby media routes from the configured public entry so MediaWarp and other
        // 302 reverse proxies always get a chance to handle the stream request.
        return endpoint(serverUrl, *route.toTypedArray()).newBuilder()
            .encodedQuery(resolved.encodedQuery)
            .build()
            .toString()
    }

    private fun List<String>.embyPlaybackRoute(): List<String>? {
        val nonEmpty = filter(String::isNotEmpty)
        val embyIndex = nonEmpty.indexOfFirst { it.equals("emby", ignoreCase = true) }
        val route = if (embyIndex >= 0) nonEmpty.drop(embyIndex + 1) else nonEmpty
        val mediaKind = route.firstOrNull()
        val action = route.getOrNull(2)?.substringBefore('.').orEmpty()
        val isMediaRoute = mediaKind.equals("Videos", ignoreCase = true) ||
            mediaKind.equals("Audio", ignoreCase = true)
        val isPlaybackAction = action.equals("stream", ignoreCase = true) ||
            action.equals("original", ignoreCase = true) ||
            action.equals("master", ignoreCase = true) ||
            action.equals("universal", ignoreCase = true)
        return route.takeIf { it.size >= 3 && isMediaRoute && isPlaybackAction }
    }

    fun directPlayUrl(
        serverUrl: String,
        itemId: String,
        mediaSourceId: String,
        container: String?,
        playSessionId: String,
    ): String {
        val extension = container
            ?.substringBefore(',')
            ?.lowercase()
            ?.takeIf { value -> value.isNotBlank() && value.all { it.isLetterOrDigit() } }
            ?: "mp4"
        return endpoint(serverUrl, "Videos", itemId, "stream.$extension")
            .newBuilder()
            .addQueryParameter("MediaSourceId", mediaSourceId)
            .addQueryParameter("Static", "true")
            .apply {
                if (playSessionId.isNotBlank()) {
                    addQueryParameter("PlaySessionId", playSessionId)
                }
            }
            .build()
            .toString()
    }

    fun hlsTranscodingUrl(
        serverUrl: String,
        itemId: String,
        mediaSourceId: String,
        playSessionId: String,
        deviceId: String,
    ): String = endpoint(serverUrl, "Videos", itemId, "master.m3u8")
        .newBuilder()
        .addQueryParameter("DeviceId", deviceId)
        .addQueryParameter("MediaSourceId", mediaSourceId)
        .addQueryParameter("PlaySessionId", playSessionId)
        .addQueryParameter("VideoCodec", "h264")
        .addQueryParameter("AudioCodec", "aac")
        .addQueryParameter("VideoBitrate", "120000000")
        .addQueryParameter("AudioBitrate", "384000")
        .addQueryParameter("TranscodingMaxAudioChannels", "2")
        .addQueryParameter("SegmentContainer", "ts")
        .addQueryParameter("MinSegments", "1")
        .addQueryParameter("BreakOnNonKeyFrames", "true")
        .addQueryParameter("allowVideoStreamCopy", "false")
        .addQueryParameter("allowAudioStreamCopy", "false")
        .build()
        .toString()
}
