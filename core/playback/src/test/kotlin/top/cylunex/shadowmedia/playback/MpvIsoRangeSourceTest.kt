package top.cylunex.shadowmedia.playback

import com.fongmi.android.tv.player.iso.IsoSessionManager
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.network.ClientIdentity

class MpvIsoRangeSourceTest {
    @Test
    fun `native ISO source keeps random reads valid across credential stripping redirect`() {
        val data = "ABCDEFGHIJ".encodeToByteArray()
        val embyToken = AtomicReference<String?>()
        val cdnToken = AtomicReference<String?>()
        val cdnCookie = AtomicReference<String?>()
        val ranges = CopyOnWriteArrayList<String>()
        val cdn = TestHttpServer { request ->
            cdnToken.set(request.header("X-Emby-Token"))
            cdnCookie.set(request.header("Cookie"))
            val range = requireNotNull(request.header("Range"))
            ranges += range
            val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range)!!
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toInt().coerceAtMost(data.lastIndex)
            val body = data.copyOfRange(start, end + 1)
            TestResponse(
                status = 206,
                reason = "Partial Content",
                headers = mapOf(
                    "Accept-Ranges" to "bytes",
                    "Content-Range" to "bytes " + start + "-" + end + "/" + data.size,
                    "ETag" to "test-disc",
                ),
                body = body,
            )
        }
        val emby = TestHttpServer { request ->
            embyToken.set(request.header("X-Emby-Token"))
            TestResponse(
                status = 302,
                reason = "Found",
                headers = mapOf("Location" to "http://127.0.0.1:" + cdn.port + "/movie.iso"),
            )
        }
        val session = EmbySession(
            serverUrl = "http://127.0.0.1:" + emby.port + "/",
            serverId = "server",
            userId = "user",
            userName = "Viewer",
            accessToken = "secret-token",
            allowInsecureHttp = true,
        )
        val client = OkHttpClient.Builder().addNetworkInterceptor(
            ScopedPlaybackHeadersInterceptor(
                PlaybackHeaderPolicy(
                    session.serverUrl.toHttpUrl(),
                    session,
                    ClientIdentity("Test", "device", "test"),
                )
            )
        ).build()
        IsoSessionManager.configure(client)
        val uri = IsoSessionManager.create(
            "http://127.0.0.1:" + emby.port + "/movie.iso",
            mapOf("Cookie" to "must-not-leak=true"),
        )

        try {
            val id = IsoSessionManager.parseId(uri)
            assertEquals(data.size.toLong(), IsoSessionManager.length(id))
            val target = ByteBuffer.allocateDirect(4)
            assertEquals(4, IsoSessionManager.readAt(id, 2, target, 4))
            target.flip()
            val result = ByteArray(4).also(target::get)

            assertEquals("CDEF", result.decodeToString())
            assertEquals("secret-token", embyToken.get())
            assertNull(cdnToken.get())
            assertNull(cdnCookie.get())
            assertEquals(listOf("bytes=0-0", "bytes=0-9"), ranges)
            assertEquals(2, IsoSessionManager.stats(uri).requestCount)
            assertTrue(IsoSessionManager.stats(uri).validatorPresent)
            assertFalse(uri.contains("secret-token"))
        } finally {
            IsoSessionManager.closeUri(uri)
            emby.close()
            cdn.close()
        }
    }

    @Test
    fun rejectsUpstreamThatIgnoresRange() {
        val server = TestHttpServer {
            TestResponse(status = 200, reason = "OK", body = "not-a-range".encodeToByteArray())
        }
        IsoSessionManager.configure(OkHttpClient())
        val uri = IsoSessionManager.create("http://127.0.0.1:" + server.port + "/movie.iso", emptyMap())

        try {
            assertEquals(-1L, IsoSessionManager.length(IsoSessionManager.parseId(uri)))
            assertTrue(IsoSessionManager.stats(uri).lastError.orEmpty().contains("忽略 Range"))
        } finally {
            IsoSessionManager.closeUri(uri)
            server.close()
        }
    }
}

internal data class TestRequest(val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase()]
}

internal data class TestResponse(
    val status: Int,
    val reason: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = byteArrayOf(),
)

internal class TestHttpServer(
    private val handler: (TestRequest) -> TestResponse,
) : Closeable {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int = socket.localPort
    private val worker = thread(isDaemon = true, name = "iso-test-http-$port") {
        while (!socket.isClosed) {
            try {
                socket.accept().use { connection ->
                    val reader = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    if (reader.readLine() == null) return@use
                    val headers = buildMap {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val separator = line.indexOf(':')
                            if (separator > 0) {
                                put(
                                    line.substring(0, separator).trim().lowercase(),
                                    line.substring(separator + 1).trim(),
                                )
                            }
                        }
                    }
                    val response = handler(TestRequest(headers))
                    connection.getOutputStream().buffered().use { output ->
                        output.write(("HTTP/1.1 " + response.status + " " + response.reason + "\r\n").toByteArray())
                        response.headers.forEach { (name, value) ->
                            output.write((name + ": " + value + "\r\n").toByteArray())
                        }
                        output.write(("Content-Length: " + response.body.size + "\r\n").toByteArray())
                        output.write("Connection: close\r\n\r\n".toByteArray())
                        output.write(response.body)
                    }
                }
            } catch (error: SocketException) {
                if (!socket.isClosed) throw error
            }
        }
    }

    override fun close() {
        socket.close()
        worker.join(2_000)
    }
}
