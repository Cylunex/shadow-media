package top.cylunex.shadowmedia.network

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cylunex.shadowmedia.model.IntegrationConnection
import top.cylunex.shadowmedia.model.IntegrationHealth
import top.cylunex.shadowmedia.model.IntegrationKind

class IntegrationsTest {
    @Test
    fun `probes seerr with api key and extracts version`() = runBlocking {
        var apiKey: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            apiKey = chain.request().header("X-Api-Key")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"version":"3.1.0"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val connection = IntegrationConnection(
            id = "seerr",
            name = "Seerr",
            kind = IntegrationKind.SEERR,
            baseUrl = "https://requests.example.com",
            apiToken = "secret-test-key",
        )

        val status = DefaultIntegrationRepository(client).probe(connection)

        assertEquals(IntegrationHealth.ONLINE, status.health)
        assertEquals("3.1.0", status.version)
        assertEquals("secret-test-key", apiKey)
    }

    @Test
    fun `derives tunarr playlist and guide urls`() {
        val connection = IntegrationConnection(
            id = "tunarr",
            name = "Tunarr",
            kind = IntegrationKind.TUNARR,
            baseUrl = "https://tv.example.com/tunarr",
        )

        val urls = DefaultIntegrationRepository(OkHttpClient()).virtualChannelUrls(connection)

        requireNotNull(urls)
        assertEquals("https://tv.example.com/tunarr/api/channels.m3u", urls.first)
        assertEquals("https://tv.example.com/tunarr/api/xmltv.xml", urls.second)
    }

    @Test
    fun `rejects cleartext service unless explicitly allowed`() = runBlocking {
        val connection = IntegrationConnection(
            id = "local",
            name = "Local",
            kind = IntegrationKind.TUNARR,
            baseUrl = "http://media.example.com:8000",
        )

        val failure = runCatching { DefaultIntegrationRepository(OkHttpClient()).probe(connection) }

        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }
}
