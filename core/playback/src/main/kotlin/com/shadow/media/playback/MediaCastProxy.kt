package com.shadow.media.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import com.shadow.media.model.ExternalMediaEntry
import com.shadow.media.network.NetworkStorageRepository
import com.shadow.media.network.OfflineModeInterceptor

/** Receiver-scoped, in-memory bridge. Only opaque resource IDs cross the LAN; credentials stay here. */
class MediaCastProxy(
    context: Context,
    entry: ExternalMediaEntry,
    bindAddress: InetAddress,
    private val receiverAddress: InetAddress,
    repository: NetworkStorageRepository?,
    val mimeType: String = castMimeType(entry.url),
) : Closeable {
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val resources = ConcurrentHashMap<String, String>().apply { put("0", entry.url) }
    private val resourceIds = ConcurrentHashMap<String, String>().apply { put(entry.url, "0") }
    private val closed = AtomicBoolean()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(4)
    private val server = ServerSocket(0, 4, bindAddress)
    private val workers = Executors.newFixedThreadPool(4) { task -> Thread(task, "media-cast-resource").apply { isDaemon = true } }
    private val client = OkHttpClient.Builder().addInterceptor(OfflineModeInterceptor()).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val origin = (entry.credentialOrigin ?: entry.url).toHttpUrlOrNull()
            val request = chain.request()
            if (origin?.isHttps == true && !request.url.isHttps) throw java.io.IOException("拒绝媒体连接降级")
            chain.proceed(request.newBuilder().headers(externalHeaders(origin, request.url, request.headers)).build())
        }.build()
    private val factory = OkHttpDataSource.Factory(client).setDefaultRequestProperties(entry.requestHeaders).setUserAgent("Shadow Media")
    private val sources = DataSource.Factory { DefaultDataSource(context.applicationContext, RoutingDataSource(factory.createDataSource(), repository)) }
    private val host = requireNotNull(bindAddress.hostAddress).let { if (it.contains(':')) "[$it]" else it }
    val url: String = "http://$host:${server.localPort}/$token/0"
    private val resourceOrder = LinkedHashSet<String>()
    private val acceptor = Thread({
        while (!closed.get()) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            if (closed.get() || socket.inetAddress != receiverAddress || !slots.tryAcquire()) { socket.close(); continue }
            sockets += socket
            try { workers.execute { try { socket.use(::serve) } finally { sockets -= socket; slots.release() } } }
            catch (_: RuntimeException) { sockets -= socket; slots.release(); socket.close() }
        }
    }, "media-cast-listener").apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val output = socket.getOutputStream()
        var sent = false
        val source = sources.createDataSource()
        try {
            val input = socket.getInputStream()
            val header = ByteArrayOutputStream()
            var ending = 0
            while (header.size() < 16_384) {
                val next = input.read(); if (next < 0) return
                header.write(next)
                ending = (ending shl 8) or next
                if (ending == 0x0d0a0d0a) break
            }
            require(header.size() < 16_384)
            val lines = header.toString("US-ASCII").split("\r\n")
            val request = lines.first().split(' ')
            require(request.size == 3 && request[0] in setOf("GET", "HEAD"))
            val path = request[1].substringBefore('?')
            if (!path.startsWith("/$token/")) { respond(output, 404, emptyMap()); return }
            val resource = resources[path.removePrefix("/$token/")] ?: run { respond(output, 404, emptyMap()); return }
            val range = lines.drop(1).firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim()
            val parsed = try { parseCastRange(range) } catch (_: IllegalArgumentException) { respond(output, 416, emptyMap()); return }
            var start = parsed.first ?: 0L
            var available = source.open(DataSpec.Builder().setUri(resource).setPosition(start).build())
            if (parsed.third != null) {
                if (available < 0) { respond(output, 416, emptyMap()); return }
                val total = available
                start = (total - parsed.third!!).coerceAtLeast(0)
                source.close()
                available = source.open(DataSpec.Builder().setUri(resource).setPosition(start).build())
            }
            val total = if (available >= 0) start + available else -1
            val responseMime = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull()?.substringBefore(';')
                ?: if (resource == resources["0"]) mimeType else castMimeType(resource)
            val playlist = responseMime.contains("mpegurl", true) || resource.substringBefore('?').endsWith(".m3u8", true)
            if (playlist && request[0] != "HEAD") {
                val content = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = source.read(buffer, 0, buffer.size); if (n == C.RESULT_END_OF_INPUT) break
                    require(content.size() + n <= 2 * 1024 * 1024)
                    content.write(buffer, 0, n)
                }
                val base = source.uri?.toString()?.toHttpUrlOrNull() ?: resource.toHttpUrlOrNull() ?: error("Invalid manifest")
                val rewritten = rewriteCastPlaylist(content.toString("UTF-8")) { reference ->
                    val target = base.resolve(reference)?.takeIf { it.username.isEmpty() && it.password.isEmpty() } ?: error("Invalid resource")
                    val id = resourceId(target.toString())
                    "http://$host:${server.localPort}/$token/$id"
                }.toByteArray()
                respond(output, 200, mapOf("Content-Type" to responseMime, "Content-Length" to rewritten.size.toString()))
                sent = true; output.write(rewritten); return
            }
            if (total == 0L) {
                respond(output, if (range == null) 200 else 416, mapOf("Content-Length" to "0", "Content-Type" to responseMime))
                return
            }
            val end = when { total < 0 -> parsed.second; parsed.second == null -> total - 1; else -> minOf(parsed.second!!, total - 1) }
            require(end == null || end >= start)
            var remaining = end?.let { it - start + 1 } ?: available
            val headers = linkedMapOf("Content-Type" to responseMime, "Accept-Ranges" to "bytes", "transferMode.dlna.org" to "Streaming", "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0")
            if (remaining >= 0) headers["Content-Length"] = remaining.toString()
            if (range != null && total >= 0) headers["Content-Range"] = "bytes $start-$end/$total"
            respond(output, if (range != null && total >= 0) 206 else 200, headers)
            sent = true
            if (request[0] == "HEAD") return
            val buffer = ByteArray(64 * 1024)
            while (!closed.get() && remaining != 0L) {
                val limit = if (remaining < 0) buffer.size else minOf(buffer.size.toLong(), remaining).toInt()
                val n = source.read(buffer, 0, limit); if (n == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, n)
                if (remaining > 0) remaining -= n
            }
        } catch (_: Exception) { if (!sent) runCatching { respond(output, 502, emptyMap()) } }
        finally { runCatching { source.close() } }
    }
    // Sliding live playlists must not exhaust a lifetime quota. Keep recent opaque references only.
    @Synchronized private fun resourceId(target: String): String {
        resourceIds[target]?.let { id ->
            if (id != "0") { resourceOrder.remove(id); resourceOrder.add(id) }
            return id
        }
        while (resourceOrder.size >= 4095) {
            val oldest = resourceOrder.first()
            resourceOrder.remove(oldest)
            resources.remove(oldest)?.let { resourceIds.remove(it) }
        }
        return UUID.randomUUID().toString().also { id -> resources[id] = target; resourceIds[target] = id; resourceOrder.add(id) }
    }
    private fun respond(output: OutputStream, status: Int, headers: Map<String, String>) {
        val text = "HTTP/1.1 $status ${if (status in 200..299) "OK" else "Unavailable"}\r\nConnection: close\r\n" + headers.entries.joinToString("") { "${it.key}: ${it.value.replace("\r", "").replace("\n", "")}\r\n" } + "\r\n"
        output.write(text.toByteArray(Charsets.US_ASCII))
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow(); client.dispatcher.cancelAll()
        resources.clear(); resourceIds.clear(); client.connectionPool.evictAll()
    }
}

/** start/end/suffix, with inclusive end. Invalid/multipart ranges never reach the source. */
internal fun parseCastRange(value: String?): Triple<Long?, Long?, Long?> {
    if (value == null) return Triple(0, null, null)
    val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(value) ?: throw IllegalArgumentException("Invalid range")
    val start = match.groupValues[1].takeIf { it.isNotEmpty() }?.toLong()
    val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong()
    require(start != null || end != null)
    if (start == null) { require(end!! > 0); return Triple(null, null, end) }
    require(start >= 0 && (end == null || end >= start))
    return Triple(start, end, null)
}
internal fun rewriteCastPlaylist(text: String, resolve: (String) -> String): String {
    require(text.trimStart().startsWith("#EXTM3U"))
    return text.lineSequence().joinToString("\n") { line ->
        when { line.isBlank() -> line; !line.startsWith('#') -> resolve(line.trim()); else -> Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"${resolve(it.groupValues[1])}\"" } }
    }
}
fun castMimeType(url: String): String = when (url.substringBefore('?').substringAfterLast('.').lowercase()) {
    "m3u8" -> "application/vnd.apple.mpegurl"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "ts", "m2ts" -> "video/mp2t"
    "mp3" -> "audio/mpeg"; "aac" -> "audio/aac"; "vtt" -> "text/vtt"; else -> "video/mp4"
}
