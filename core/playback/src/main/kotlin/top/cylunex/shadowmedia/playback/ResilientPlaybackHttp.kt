package top.cylunex.shadowmedia.playback

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

data class PlaybackHttpTrace(
    val resolvedHost: String? = null,
    val responseCode: Int? = null,
    val redirectCount: Int = 0,
    val attempt: Int = 1,
    val responseHeadersMs: Long? = null,
    val lastNetworkError: String? = null,
)

/**
 * Reopens the original Emby URL when a temporary CDN response is returned. Retrying the signed
 * CDN URL itself would keep using the same expired 115 link, while reopening the Emby URL lets
 * MediaWarp/OpenList resolve a fresh redirect without ever exposing Emby credentials off-origin.
 */
internal class ResilientPlaybackHttpInterceptor(
    private val embyOrigin: HttpUrl,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val onTrace: (PlaybackHttpTrace) -> Unit = {},
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.method !in IDEMPOTENT_METHODS) return chain.proceed(original)

        var lastFailure: IOException? = null
        repeat(maxAttempts) { zeroBasedAttempt ->
            val attempt = zeroBasedAttempt + 1
            val startedAt = System.nanoTime()
            try {
                val response = chain.proceed(original)
                val trace = PlaybackHttpTrace(
                    resolvedHost = response.request.url.host,
                    responseCode = response.code,
                    redirectCount = response.redirectCount(),
                    attempt = attempt,
                    responseHeadersMs = System.nanoTime().elapsedMillisecondsSince(startedAt),
                )
                onTrace(trace)
                if (!response.shouldRetry(attempt)) return response
                val retryDelayMs = response.retryDelayMs(attempt)
                response.close()
                if (!chain.call().isCanceled()) Thread.sleep(retryDelayMs)
            } catch (error: IOException) {
                lastFailure = error
                onTrace(
                    PlaybackHttpTrace(
                        attempt = attempt,
                        responseHeadersMs = System.nanoTime().elapsedMillisecondsSince(startedAt),
                        lastNetworkError = error.javaClass.simpleName,
                    )
                )
                if (attempt >= maxAttempts || chain.call().isCanceled()) throw error
                Thread.sleep(backoffMs(attempt))
            }
        }
        throw lastFailure ?: IOException("播放链路重试结束但没有收到响应")
    }

    private fun Response.shouldRetry(attempt: Int): Boolean {
        if (attempt >= maxAttempts) return false
        return when (code) {
            403 -> !request.url.sameOriginAs(embyOrigin)
            408, 425, 429, 500, 502, 503, 504 -> true
            else -> false
        }
    }

    private fun Response.retryDelayMs(attempt: Int): Long {
        val retryAfterSeconds = header("Retry-After")?.toLongOrNull()
        return retryAfterSeconds?.let { TimeUnit.SECONDS.toMillis(it).coerceAtMost(MAX_RETRY_AFTER_MS) }
            ?: backoffMs(attempt)
    }

    private fun HttpUrl.sameOriginAs(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    private fun Response.redirectCount(): Int = generateSequence(priorResponse) { it.priorResponse }.count()

    private fun backoffMs(attempt: Int): Long = BASE_RETRY_DELAY_MS * attempt

    private fun Long.elapsedMillisecondsSince(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(this - startedAt).coerceAtLeast(0L)

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val BASE_RETRY_DELAY_MS = 350L
        const val MAX_RETRY_AFTER_MS = 3_000L
        val IDEMPOTENT_METHODS = setOf("GET", "HEAD")
    }
}
