package top.cylunex.shadowmedia.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.Assert.*
import top.cylunex.shadowmedia.model.*
class EmbyNextUpTest {
    @Test fun `next up is scoped to the active account and ignores completed episodes`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"next","Name":"Next","Type":"Episode","SeriesId":"show"},{"Id":"done","Name":"Done","Type":"Episode","UserData":{"Played":true}},{"Id":"resume","Name":"Resume","Type":"Episode","UserData":{"PlaybackPositionTicks":42}}]}"""))
            server.start()
            val session = EmbySession(server.url("/").toString(), "server", "user-one", "User", "secret", true)
            val repository = DefaultEmbyRepository(okhttp3.OkHttpClient(), ClientIdentity("Test", "test", "test"))
            val items = repository.nextUp(session)
            assertEquals(listOf("next", "resume"), items.map { it.id })
            assertTrue(items.first().nextUp); assertFalse(items.last().nextUp)
            val request = server.takeRequest()
            assertEquals("user-one", request.requestUrl!!.queryParameter("UserId"))
            assertTrue(request.path!!.substringBefore('?').endsWith("/Shows/NextUp"))
            assertFalse(request.path!!.contains("secret"))
        }
    }
    @Test fun `mixed folder browsing does not recursively flatten nested directories`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"folder","Name":"Folder","Type":"Folder","IsFolder":true}]}""")); server.start()
            val session = EmbySession(server.url("/").toString(), "server", "user", "User", "secret", true)
            val result = DefaultEmbyRepository(okhttp3.OkHttpClient(), ClientIdentity("Test", "test", "test")).browse(session,
                BrowseRequest("parent", setOf("Folder", "Movie", "Series"), recursive = false))
            assertTrue(result.items.single().isFolder)
            val request = server.takeRequest()
            assertEquals("false", request.requestUrl!!.queryParameter("Recursive"))
            assertEquals("parent", request.requestUrl!!.queryParameter("ParentId"))
        }
    }
}
