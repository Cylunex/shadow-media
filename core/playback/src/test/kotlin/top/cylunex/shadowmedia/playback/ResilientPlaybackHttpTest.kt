package top.cylunex.shadowmedia.playback

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.network.ClientIdentity

class ResilientPlaybackHttpTest {
    @Test
    fun `cdn 403 reopens original emby url and resolves a fresh redirect`() {
        val embyRequests = AtomicInteger()
        val cdnRequests = AtomicInteger()
        val cdnToken = AtomicReference<String?>()
        val cdn = TestHttpServer { request ->
            cdnToken.set(request.header("X-Emby-Token"))
            if (cdnRequests.incrementAndGet() == 1) {
                TestResponse(status = 403, reason = "Expired")
            } else {
                TestResponse(status = 206, reason = "Partial Content", body = byteArrayOf(1))
            }
        }
        val emby = TestHttpServer {
            embyRequests.incrementAndGet()
            TestResponse(
                status = 302,
                reason = "Found",
                headers = mapOf("Location" to "http://127.0.0.1:${cdn.port}/video.mkv"),
            )
        }
        val session = testSession(emby.port)
        val latestTrace = AtomicReference<PlaybackHttpTrace>()
        val client = playbackClient(session) { latestTrace.set(it) }

        try {
            client.newCall(
                Request.Builder().url("http://127.0.0.1:${emby.port}/video.mkv").build()
            ).execute().use { response -> assertEquals(206, response.code) }

            assertEquals(2, embyRequests.get())
            assertEquals(2, cdnRequests.get())
            assertNull(cdnToken.get())
            assertEquals(2, latestTrace.get().attempt)
            assertEquals(1, latestTrace.get().redirectCount)
        } finally {
            emby.close()
            cdn.close()
        }
    }

    @Test
    fun `emby 403 is not retried`() {
        val requests = AtomicInteger()
        val emby = TestHttpServer {
            requests.incrementAndGet()
            TestResponse(status = 403, reason = "Forbidden")
        }
        val session = testSession(emby.port)
        val client = playbackClient(session)

        try {
            client.newCall(
                Request.Builder().url("http://127.0.0.1:${emby.port}/video.mkv").build()
            ).execute().use { response -> assertEquals(403, response.code) }
            assertEquals(1, requests.get())
        } finally {
            emby.close()
        }
    }

    private fun playbackClient(
        session: EmbySession,
        onTrace: (PlaybackHttpTrace) -> Unit = {},
    ): OkHttpClient {
        val origin = session.serverUrl.toHttpUrl()
        return OkHttpClient.Builder()
            .addInterceptor(ResilientPlaybackHttpInterceptor(origin, onTrace = onTrace))
            .addNetworkInterceptor(
                ScopedPlaybackHeadersInterceptor(
                    PlaybackHeaderPolicy(
                        origin,
                        session,
                        ClientIdentity("Test", "device", "test"),
                    )
                )
            )
            .build()
    }

    private fun testSession(port: Int) = EmbySession(
        serverUrl = "http://127.0.0.1:$port/",
        serverId = "server",
        userId = "user",
        userName = "Viewer",
        accessToken = "secret-token",
        allowInsecureHttp = true,
    )
}
