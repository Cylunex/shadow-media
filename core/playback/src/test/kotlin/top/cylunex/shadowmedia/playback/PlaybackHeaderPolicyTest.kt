package top.cylunex.shadowmedia.playback

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.network.ClientIdentity

class PlaybackHeaderPolicyTest {
    private val session = EmbySession(
        serverUrl = "https://media.example.com/",
        serverId = "server",
        userId = "user",
        userName = "Viewer",
        accessToken = "secret-token",
        allowInsecureHttp = false,
    )
    private val policy = PlaybackHeaderPolicy(
        "https://media.example.com/".toHttpUrl(),
        session,
        ClientIdentity("Test device", "device-id", "0.1.0"),
    )

    @Test
    fun `adds token only on exact Emby origin`() {
        val headers = policy.apply("https://media.example.com/video".toHttpUrl(), Headers.headersOf())
        assertEquals("secret-token", headers["X-Emby-Token"])
    }

    @Test
    fun `strips all sensitive headers after third party redirect`() {
        val original = Headers.headersOf(
            "X-Emby-Token", "secret-token",
            "Authorization", "Bearer secret",
            "Cookie", "internal=true",
            "Range", "bytes=100-",
            "User-Agent", "Shadow Media",
        )
        val headers = policy.apply("https://cdn.example.net/video".toHttpUrl(), original)
        assertNull(headers["X-Emby-Token"])
        assertNull(headers["Authorization"])
        assertNull(headers["Cookie"])
        assertEquals("bytes=100-", headers["Range"])
        assertEquals("Shadow Media", headers["User-Agent"])
    }
}
