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
}
