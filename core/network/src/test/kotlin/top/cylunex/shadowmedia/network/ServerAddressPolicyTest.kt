package top.cylunex.shadowmedia.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressPolicyTest {
    @Test
    fun `accepts https and trims trailing slash`() {
        val result = ServerAddressPolicy.validate(" https://media.example.com/ ", false).getOrThrow()
        assertEquals("https://media.example.com/", result.toString())
    }

    @Test
    fun `blocks http unless user explicitly opts in`() {
        assertTrue(ServerAddressPolicy.validate("http://192.0.2.1:8096", false).isFailure)
        assertTrue(ServerAddressPolicy.validate("http://192.0.2.1:8096", true).isSuccess)
    }

    @Test
    fun `rejects credentials embedded in url`() {
        assertTrue(ServerAddressPolicy.validate("https://user:pass@example.com", false).isFailure)
    }

    @Test
    fun `builds endpoint without duplicating emby segment`() {
        val fromRoot = EmbyEndpoints.endpoint("https://media.example.com/", "Users", "abc", "Views")
        val fromApi = EmbyEndpoints.endpoint("https://media.example.com/emby", "Users", "abc", "Views")
        assertEquals("https://media.example.com/emby/Users/abc/Views", fromRoot.toString())
        assertEquals(fromRoot, fromApi)
    }

    @Test
    fun `resolves server relative playback url`() {
        assertEquals(
            "https://media.example.com/emby/Videos/1/stream.mkv",
            EmbyEndpoints.resolvePlaybackUrl(
                "https://media.example.com/",
                "/emby/Videos/1/stream.mkv",
            ),
        )
    }

    @Test
    fun `rebases absolute private emby playback url onto configured proxy`() {
        assertEquals(
            "https://proxy.example.com/media/emby/Videos/1/stream.mkv?MediaSourceId=source-1",
            EmbyEndpoints.resolvePlaybackUrl(
                "https://proxy.example.com/media",
                "http://emby.internal:8096/emby/Videos/1/stream.mkv?MediaSourceId=source-1",
            ),
        )
    }

    @Test
    fun `keeps external storage url outside emby route unchanged`() {
        assertEquals(
            "https://cdn.example.net/videos/library/movie.mkv?sign=temporary",
            EmbyEndpoints.resolvePlaybackUrl(
                "https://proxy.example.com/",
                "https://cdn.example.net/videos/library/movie.mkv?sign=temporary",
            ),
        )
    }

    @Test
    fun `builds authenticated direct play endpoint without token in url`() {
        assertEquals(
            "https://media.example.com/emby/Videos/item-1/stream.mkv?" +
                "MediaSourceId=source-1&Static=true&PlaySessionId=play-1",
            EmbyEndpoints.directPlayUrl(
                serverUrl = "https://media.example.com",
                itemId = "item-1",
                mediaSourceId = "source-1",
                container = "mkv,webm",
                playSessionId = "play-1",
            ),
        )
    }
}
