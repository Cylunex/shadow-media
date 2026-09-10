package top.cylunex.shadowmedia.library

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.Assert.*
import top.cylunex.shadowmedia.model.*

class ResourceDownloaderTest {
    @Test fun partialHttpResponseCannotBeInstalledAsWholeFile() = runBlocking {
        val server = MockWebServer(); server.start(); val file = File.createTempFile("shadow-partial-test", ".part")
        try {
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2/100").setBody("abc"))
            try { ResourceDownloader().download(PlaybackCandidate(server.url("/file").toString(), PlayMethod.DIRECT_PLAY, emptyMap()), file); fail("must reject partial resource") }
            catch (_: IllegalArgumentException) {}
            assertFalse(file.exists())
        } finally { file.delete(); server.shutdown() }
    }
    @Test fun truncatedSmbResponseRemovesPartialFile() = runBlocking {
        val previous = LibraryResources.networkStorage
        LibraryResources.networkStorage = object : top.cylunex.shadowmedia.network.NetworkStorageRepository {
            override fun provider(connection: NetworkStorageConnection): top.cylunex.shadowmedia.provider.MediaProvider = error("unused")
            override suspend fun probe(connection: NetworkStorageConnection): NetworkStorageStatus = error("unused")
            override fun openSmbResource(url: String, position: Long) = object : top.cylunex.shadowmedia.network.NetworkReadHandle {
                val input = "abc".byteInputStream()
                override val remainingLength = 100L
                override fun read(buffer: ByteArray, offset: Int, length: Int) = input.read(buffer, offset, length)
                override fun close() = input.close()
            }
        }
        val file = File.createTempFile("shadow-smb-test", ".part")
        try {
            try { ResourceDownloader().download(PlaybackCandidate("shadow-smb://example/resource", PlayMethod.DIRECT_PLAY, emptyMap()), file); fail("must reject truncated resource") }
            catch (_: IllegalArgumentException) {}
            assertFalse(file.exists())
        } finally { file.delete(); LibraryResources.networkStorage = previous }
    }
    @Test fun credentialsDoNotCrossOriginEvenWhenOnlyPortChanges() = runBlocking {
        val api = MockWebServer(); val cdn = MockWebServer(); api.start(); cdn.start()
        val file = File.createTempFile("shadow-resource-test", ".part")
        try {
            api.enqueue(MockResponse().setResponseCode(302).addHeader("Location", cdn.url("/book.epub")))
            cdn.enqueue(MockResponse().setBody("epub bytes"))
            val candidate = PlaybackCandidate(api.url("/download").toString(), PlayMethod.DIRECT_PLAY,
                mapOf("Authorization" to "Bearer example", "X-Emby-Authorization" to "example", "Cookie" to "example=1"), credentialOrigin = api.url("/").toString())
            ResourceDownloader().download(candidate, file)
            assertEquals("Bearer example", api.takeRequest().getHeader("Authorization"))
            val redirected = cdn.takeRequest()
            assertNull(redirected.getHeader("Authorization")); assertNull(redirected.getHeader("X-Emby-Authorization")); assertNull(redirected.getHeader("Cookie"))
            assertEquals("epub bytes", file.readText())
        } finally { file.delete(); api.shutdown(); cdn.shutdown() }
    }
    @Test fun oversizedResponseRemovesPartialFile() = runBlocking {
        val server = MockWebServer(); server.start(); val file = File.createTempFile("shadow-limit-test", ".part")
        try {
            server.enqueue(MockResponse().setBody("123456789"))
            try { ResourceDownloader().download(PlaybackCandidate(server.url("/file").toString(), PlayMethod.DIRECT_PLAY, emptyMap()), file, 4); fail("must reject") } catch (_: IllegalArgumentException) {}
            assertFalse(file.exists())
        } finally { file.delete(); server.shutdown() }
    }
}
