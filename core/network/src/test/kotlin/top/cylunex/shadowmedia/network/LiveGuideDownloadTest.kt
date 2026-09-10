package top.cylunex.shadowmedia.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class LiveGuideDownloadTest {
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()
    @Test fun `accepts gzip bytes without relying on filename`() {
        val xml = "<tv/>".toByteArray()
        assertArrayEquals(xml, decodeGuide(gzip(xml).inputStream(), 1024, 1024).use { it.readBytes() })
    }
    @Test fun `limits expanded bytes rather than just compressed input`() {
        val compressed = gzip(ByteArray(8192) { 65 })
        assertTrue(compressed.size < 256)
        try { decodeGuide(compressed.inputStream(), 1024, 256).use { it.readBytes() }; fail("must reject expanded payload") }
        catch (_: IOException) {}
    }
    @Test fun `enforces compressed byte budget as well`() {
        try { decodeGuide(gzip("<tv/>".toByteArray()).inputStream(), 1, 1024).use { it.readBytes() }; fail("must reject oversized compressed payload") }
        catch (_: IOException) {}
    }
    @Test fun `gz URL with HTTP gzip encoding is not decoded twice`() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(Buffer().write(gzip("<tv/>".toByteArray()))).setHeader("Content-Encoding", "gzip"))
            val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns { listOf(InetAddress.getByName("127.0.0.1")) }.build()
            val address = server.url("/guide.xml.gz").newBuilder().host("guide.example.com").build().toString()
            assertTrue(DefaultLiveGuideRepository(client).load("source", address, true).isEmpty())
        } finally { server.shutdown() }
    }
}
