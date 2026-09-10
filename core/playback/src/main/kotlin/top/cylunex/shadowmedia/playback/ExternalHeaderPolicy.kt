package top.cylunex.shadowmedia.playback

import okhttp3.Headers
import okhttp3.HttpUrl

/** Imported header credentials belong to the resolved origin, including when none was explicit. */
internal fun externalHeaders(origin: HttpUrl?, destination: HttpUrl, headers: Headers): Headers {
    if (origin != null && origin.scheme == destination.scheme && origin.host == destination.host && origin.port == destination.port) return headers
    return headers.newBuilder().apply {
        listOf("Authorization", "Cookie", "X-Emby-Token", "X-Emby-Authorization", "X-MediaBrowser-Token", "X-MediaBrowser-Authorization", "X-API-Key").forEach(::removeAll)
    }.build()
}
