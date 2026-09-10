package top.cylunex.shadowmedia.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import top.cylunex.shadowmedia.model.*

class EmbyPaginationTest {
    private val session = EmbySession("https://example.com", "server", "user", "User", "example", true)
    @Test fun longSeriesLoadsEveryPageAndUsesRemoteOffsets() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"one","Name":"1"},{"Id":"two","Name":"2"}],"TotalRecordCount":3}"""))
            server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"three","Name":"3"}],"TotalRecordCount":3}"""))
            val repo = DefaultEmbyRepository(OkHttpClient(), ClientIdentity("test", "test", "test"))
            assertEquals(listOf("one", "two", "three"), repo.children(session.copy(serverUrl = server.url("/").toString()), "series").map { it.id })
            assertEquals("0", server.takeRequest().requestUrl!!.queryParameter("StartIndex"))
            assertEquals("2", server.takeRequest().requestUrl!!.queryParameter("StartIndex"))
        } finally { server.shutdown() }
    }
    @Test fun repeatedTerminalPageFailsInsteadOfPublishingTruncatedSeries() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            repeat(2) { server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"one","Name":"1"}],"TotalRecordCount":2}""")) }
            val repo = DefaultEmbyRepository(OkHttpClient(), ClientIdentity("test", "test", "test"))
            assertTrue(runCatching { repo.children(session.copy(serverUrl = server.url("/").toString()), "series") }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
}
