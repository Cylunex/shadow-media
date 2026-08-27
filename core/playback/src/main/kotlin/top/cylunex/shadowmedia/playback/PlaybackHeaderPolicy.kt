package top.cylunex.shadowmedia.playback

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.network.ClientIdentity

/** Ensures Emby credentials never follow a redirect to a third-party CDN. */
class PlaybackHeaderPolicy(
    private val embyOrigin: HttpUrl,
    private val session: EmbySession,
    private val clientIdentity: ClientIdentity,
) {
    fun apply(url: HttpUrl, source: Headers): Headers {
        val result = source.newBuilder()
            .removeAll("X-Emby-Token")
            .removeAll("X-Emby-Authorization")
            .removeAll("Authorization")
            .removeAll("Cookie")

        if (url.sameOriginAs(embyOrigin)) {
            result.add("X-Emby-Token", session.accessToken)
            result.add("X-Emby-Authorization", clientIdentity.authorizationHeader(session))
        }
        return result.build()
    }

    private fun HttpUrl.sameOriginAs(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port
}

internal class ScopedPlaybackHeadersInterceptor(
    private val policy: PlaybackHeaderPolicy,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(request.newBuilder().headers(policy.apply(request.url, request.headers)).build())
    }
}
