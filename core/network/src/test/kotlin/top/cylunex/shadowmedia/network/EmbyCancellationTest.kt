package top.cylunex.shadowmedia.network

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import top.cylunex.shadowmedia.model.EmbySession
import java.util.concurrent.TimeUnit

class EmbyCancellationTest {
    @Test fun cancelledCatalogRequestDoesNotWaitForSocketTimeout() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val repo = DefaultEmbyRepository(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build(), ClientIdentity("test", "test", "test"))
            val session = EmbySession(server.url("/").toString(), "server", "user", "test", "example", true)
            val request = launch(Dispatchers.IO) { repo.libraries(session) }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(2000) { request.cancelAndJoin() }
        } finally { server.shutdown() }
    }
    @Test fun catalogRedirectCannotCarryCustomEmbyAuthenticationAcrossOrigins() = runBlocking {
        val server = MockWebServer(); val other = MockWebServer(); server.start(); other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/catalog")))
            val repo = DefaultEmbyRepository(OkHttpClient(), ClientIdentity("test", "test", "test"))
            val session = EmbySession(server.url("/").toString(), "server", "user", "test", "example", true)
            try { repo.libraries(session); fail("cross-origin API redirect") } catch (_: java.io.IOException) {}
            assertNull(other.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally { server.shutdown(); other.shutdown() }
    }
}
