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
        val absolute = pathOrUrl.toHttpUrlOrNull()
        if (absolute != null) return absolute.toString()
        val server = serverUrl.toHttpUrlOrNull() ?: error("Invalid server URL")
        return server.resolve(pathOrUrl)?.toString() ?: error("Invalid playback URL")
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
}
