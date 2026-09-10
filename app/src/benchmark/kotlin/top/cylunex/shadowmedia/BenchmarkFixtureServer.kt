package top.cylunex.shadowmedia

import android.content.Context
import android.net.Uri
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** Reproducible local test protocol, not a production server or configurable remote proxy. */
internal class BenchmarkFixtureServer(private val context: Context) {
    private val socket = ServerSocket(PORT, 8, InetAddress.getLoopbackAddress())
    private val executor = Executors.newFixedThreadPool(4)
    private val video by lazy { context.assets.open("fixture-video.mp4").use { it.readBytes() } }
    private val image by lazy { java.util.zip.ZipInputStream(context.assets.open("physical-pages.cbz")).use { input -> input.nextEntry; input.readBytes() } }
    fun start() { Thread({ while (!socket.isClosed) { runCatching { socket.accept() }.getOrNull()?.let { client -> executor.execute { runCatching { client.use { serve(it) } } } } } }, "fixture-listener").apply { isDaemon = true; start() } }
    private fun serve(client: java.net.Socket) {
        client.soTimeout = 5000
        val input = client.getInputStream()
        fun line(): String {
            val result = StringBuilder()
            while (result.length < 8192) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) result.append(c.toChar()) }
            return result.toString()
        }
        val request = line().split(' '); if (request.size < 2) return
        val headers = mutableMapOf<String, String>(); var total = 0
        while (true) { val line = line(); if (line.isEmpty()) break; total += line.length; require(total <= 32768); headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        require(length in 0..(2 * 1024 * 1024)); repeat(length) { require(input.read() >= 0) }
        val uri = Uri.parse("http://localhost${request[1]}"); val path = uri.path.orEmpty()
        fun send(bytes: ByteArray, type: String = "application/json", code: Int = 200, extra: String = "") {
            client.getOutputStream().apply {
                write("HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$extra\r\n".toByteArray())
                write(bytes); flush()
            }
        }
        when {
            path.contains("Images/") -> send(image, "image/png")
            path.endsWith("PlaybackInfo") -> send(JSONObject().put("PlaySessionId", "fixture-session").put("MediaSources", JSONArray().put(JSONObject().put("Id", "fixture-source").put("Container", "mp4").put("SupportsDirectPlay", true).put("RunTimeTicks", 60_000_000))).toString().toByteArray())
            path.contains("/Videos/") -> send(byteArrayOf(), code = 302, extra = "Location: /fixture/media.mp4\r\n")
            path == "/fixture/media.mp4" -> {
                val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(headers["range"].orEmpty())
                if (range == null) send(video, "video/mp4", extra = "Accept-Ranges: bytes\r\nETag: \"fixture-v1\"\r\n")
                else {
                    val start = range.groupValues[1].toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val end = (range.groupValues[2].toLongOrNull() ?: video.lastIndex.toLong()).coerceAtMost(video.lastIndex.toLong()).toInt()
                    if (start !in video.indices || end < start) send(byteArrayOf(), code = 416)
                    else send(video.copyOfRange(start, end + 1), "video/mp4", 206, "Content-Range: bytes $start-$end/${video.size}\r\nETag: \"fixture-v1\"\r\n")
                }
            }
            path.endsWith("Views") -> send(JSONObject().put("Items", JSONArray().put(JSONObject().put("Id", "movies").put("Name", "Fixture Movies").put("CollectionType", "movies"))).toString().toByteArray())
            path.endsWith("/Items/Latest") -> send(JSONArray().apply { repeat(20) { put(item(it)) } }.toString().toByteArray())
            path.endsWith("/Items/Resume") -> send(JSONObject().put("Items", JSONArray()).put("TotalRecordCount", 0).toString().toByteArray())
            path.endsWith("/Items") -> {
                val start = uri.getQueryParameter("StartIndex")?.toIntOrNull()?.coerceIn(0, 10_000) ?: 0
                val count = uri.getQueryParameter("Limit")?.toIntOrNull()?.coerceIn(1, 200) ?: 60
                val values = JSONArray(); for (i in start until minOf(start + count, 10_000)) values.put(item(i))
                send(JSONObject().put("Items", values).put("TotalRecordCount", 10_000).toString().toByteArray())
            }
            path.contains("/Items/video-") -> send(item(path.substringAfterLast('-').toIntOrNull() ?: 0).toString().toByteArray())
            else -> send(byteArrayOf(), code = 204)
        }
    }
    private fun item(index: Int) = JSONObject().put("Id", "video-$index").put("Name", "Fixture Video ${index.toString().padStart(5, '0')}")
        .put("Type", "Movie").put("RunTimeTicks", 60_000_000).put("ImageTags", JSONObject().put("Primary", "fixture"))
    companion object { const val PORT = 37841; const val ADDRESS = "http://localhost:$PORT/" }
}
