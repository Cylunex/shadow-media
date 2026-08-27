package com.fongmi.android.tv.player.iso

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class IsoSourceStats(
    val requestCount: Long = 0,
    val cacheHits: Long = 0,
    val cacheMisses: Long = 0,
    val upstreamHost: String? = null,
    val upstreamStatus: Int? = null,
    val totalBytes: Long = -1,
    val lastOffset: Long = 0,
    val lastLength: Int = 0,
    val validatorPresent: Boolean = false,
    val lastError: String? = null,
)

/** JNI entry point expected by WebHTV's GPL-3.0 `libplayer.so`. */
class IsoSessionManager private constructor() {
    companion object {
        private val nextId = AtomicLong(1_000)
        private val sessions = ConcurrentHashMap<Long, IsoPlaybackSession>()
        private val closedStats = ConcurrentHashMap<Long, IsoSourceStats>()
        @Volatile private var configuredClient: OkHttpClient? = null

        @JvmStatic
        fun configure(client: OkHttpClient) {
            configuredClient = client
        }

        @JvmStatic
        fun create(url: String, headers: Map<String, String>): String {
            val client = configuredClient ?: throw IllegalStateException("ISO HTTP client is not configured")
            val id = nextId.incrementAndGet()
            closedStats.remove(id)
            sessions[id] = IsoPlaybackSession(HttpRangeIsoSource(url, headers, client))
            return "webhtv-dvdiso://$id/longest"
        }

        @JvmStatic
        fun close(id: Long) {
            sessions.remove(id)?.let { session ->
                closedStats[id] = session.stats()
                session.close()
                if (closedStats.size > MAX_CLOSED_STATS) {
                    closedStats.keys.firstOrNull()?.let(closedStats::remove)
                }
            }
        }

        @JvmStatic
        fun closeUri(uri: String?) = close(parseId(uri))

        @JvmStatic
        fun length(id: Long): Long = runCatching { sessions[id]?.length() ?: -1 }.getOrDefault(-1)

        @JvmStatic
        fun readAt(id: Long, offset: Long, target: ByteBuffer?, length: Int): Int {
            val session = sessions[id] ?: return -1
            if (target == null) return -1
            return runCatching { session.readAt(offset, target, length) }.getOrDefault(-1)
        }

        /** Native asks for this after selecting a Blu-ray playlist; language discovery is optional. */
        @JvmStatic
        fun prepareTrackMetadata(id: Long, playlist: Int) = Unit

        @JvmStatic
        fun getTrackLanguage(id: Long, trackType: Int, demuxId: Int, ordinal: Int): String? = null

        @JvmStatic
        fun parseId(uri: String?): Long {
            if (uri == null) return -1
            val start = uri.indexOf("://").let { if (it < 0) 0 else it + 3 }
            val end = uri.indexOf('/', start).let { if (it < 0) uri.length else it }
            return uri.substring(start, end).toLongOrNull() ?: -1
        }

        @JvmStatic
        fun stats(uri: String?): IsoSourceStats {
            val id = parseId(uri)
            return sessions[id]?.stats() ?: closedStats[id] ?: IsoSourceStats()
        }

        private const val MAX_CLOSED_STATS = 32
    }
}

private class IsoPlaybackSession(source: HttpRangeIsoSource) : Closeable {
    private val cache = IsoPageCache(source)

    fun length(): Long = cache.length()

    fun readAt(offset: Long, target: ByteBuffer, length: Int): Int {
        val wanted = minOf(length, target.remaining())
        if (wanted <= 0) return 0
        val buffer = ByteArray(wanted)
        val read = cache.readAt(offset, buffer, 0, wanted)
        if (read > 0) target.put(buffer, 0, read)
        return read
    }

    fun stats(): IsoSourceStats = cache.stats()

    override fun close() = cache.close()
}

private interface RemoteIsoSource : Closeable {
    fun length(): Long
    fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int
    fun stats(): IsoSourceStats
}

private class HttpRangeIsoSource(
    private val url: String,
    inputHeaders: Map<String, String>,
    private val client: OkHttpClient,
) : RemoteIsoSource {
    private val headers = inputHeaders.filterKeys { key ->
        key.isNotBlank() && !FORBIDDEN_HEADERS.any { it.equals(key, ignoreCase = true) }
    }
    private var sourceLength = -1L
    private var validator: String? = null
    private var mutableStats = IsoSourceStats()
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var closed = false

    @Synchronized
    override fun length(): Long {
        ensureOpen()
        if (sourceLength >= 0) return sourceLength
        try {
            executeRange(0, 1).use { validate(it.response, 0, 1) }
        } catch (error: IOException) {
            updateError(error)
            throw error
        }
        return sourceLength
    }

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        ensureOpen()
        require(offset >= 0 && bufferOffset >= 0 && length >= 0 && bufferOffset + length <= buffer.size)
        val total = length()
        if (offset >= total || length == 0) return 0
        val requested = minOf(length.toLong(), total - offset).toInt()
        var lastFailure: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                executeRange(offset, requested).use { active ->
                    validate(active.response, offset, requested)
                    val body = active.response.body
                    var read = 0
                    body.byteStream().use { input ->
                        while (read < requested) {
                            val count = input.read(buffer, bufferOffset + read, requested - read)
                            if (count < 0) break
                            read += count
                        }
                    }
                    if (read != requested) {
                        throw EOFException("ISO Range 响应体长度不完整：期望 $requested，实际 $read")
                    }
                    return read
                }
            } catch (error: IOException) {
                lastFailure = error
                updateError(error)
                if (error is IsoSourceException) throw error
                if (closed || attempt == MAX_ATTEMPTS - 1) return@repeat
            }
        }
        throw lastFailure ?: IOException("ISO Range 请求失败")
    }

    private fun executeRange(offset: Long, length: Int): ActiveRangeResponse {
        val request = Request.Builder()
            .url(url)
            .headers(Headers.Builder().apply {
                headers.forEach { (name, value) -> add(name, value) }
                add("Range", "bytes=$offset-${offset + length - 1}")
                add("Accept-Encoding", "identity")
                if (headers.keys.none { it.equals("User-Agent", true) }) add("User-Agent", "Shadow Media ISO/MPV")
            }.build())
            .get()
            .build()
        val call = client.newCall(request)
        activeCalls += call
        if (closed) {
            call.cancel()
            activeCalls -= call
            throw IsoSourceException("ISO 数据源已关闭")
        }
        return try {
            ActiveRangeResponse(call, call.execute()) { activeCalls -= it }
        } catch (error: Throwable) {
            activeCalls -= call
            throw error
        }
    }

    @Synchronized
    private fun validate(response: okhttp3.Response, offset: Long, requested: Int) {
        val code = response.code
        val range = response.header("Content-Range")
        val match = range?.let(CONTENT_RANGE::matchEntire)
        when (code) {
            401, 403 -> throw IsoSourceException("ISO 地址未授权或已过期", code)
            404 -> throw IsoSourceException("ISO 文件不存在", code)
            416 -> throw IsoSourceException("ISO Range 超出范围", code)
            200 -> throw IsoSourceException("上游忽略 Range，不能随机读取 ISO", code)
            206 -> Unit
            else -> throw IsoSourceException("ISO 上游 HTTP $code", code)
        }
        if (match == null) throw IsoSourceException("上游缺少有效 Content-Range", code)
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].toLong()
        val total = match.groupValues[3].toLong()
        val expectedLength = if (total > offset) minOf(requested.toLong(), total - offset) else 0L
        if (
            total <= 0 || start != offset || expectedLength <= 0 ||
            end != offset + expectedLength - 1
        ) {
            throw IsoSourceException("上游返回的 ISO Range 不一致", code)
        }
        if (sourceLength >= 0 && sourceLength != total) throw IsoSourceException("ISO 文件大小已变化", code)
        sourceLength = total
        val nextValidator = response.header("ETag") ?: response.header("Last-Modified")
        if (validator != null && nextValidator != null && validator != nextValidator) {
            throw IsoSourceException("ISO 文件在播放期间发生变化", code)
        }
        if (validator == null) validator = nextValidator
        mutableStats = mutableStats.copy(
            requestCount = mutableStats.requestCount + 1,
            upstreamHost = response.request.url.host,
            upstreamStatus = code,
            totalBytes = total,
            lastOffset = offset,
            lastLength = (end - start + 1).toInt(),
            validatorPresent = validator != null,
            lastError = null,
        )
    }

    @Synchronized
    private fun updateError(error: Throwable) {
        mutableStats = mutableStats.copy(lastError = error.message ?: error.javaClass.simpleName)
    }

    override fun stats(): IsoSourceStats = synchronized(this) { mutableStats }

    private fun ensureOpen() {
        if (closed) throw IsoSourceException("ISO 数据源已关闭")
    }

    override fun close() {
        closed = true
        activeCalls.forEach(Call::cancel)
        activeCalls.clear()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
        val FORBIDDEN_HEADERS = setOf("Range", "Accept-Encoding", "Host", "Connection", "Content-Length")
    }
}

private class ActiveRangeResponse(
    private val call: Call,
    val response: Response,
    private val onClose: (Call) -> Unit,
) : Closeable {
    override fun close() {
        response.close()
        onClose(call)
    }
}

private class IsoPageCache(
    private val source: RemoteIsoSource,
    private val pageSize: Int = 4 * 1024 * 1024,
    private val maxPages: Int = 8,
) : RemoteIsoSource {
    private val pages = object : LinkedHashMap<Long, ByteArray>(maxPages, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>): Boolean =
            size > maxPages
    }
    private val pending = LinkedHashMap<Long, CompletableFuture<ByteArray>>()
    private val prefetchExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "shadow-iso-prefetch").apply { isDaemon = true }
    }
    private var hits = 0L
    private var misses = 0L
    @Volatile private var closed = false

    override fun length(): Long = source.length()

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        if (closed) throw IsoSourceException("ISO 缓存已关闭")
        val total = length()
        if (offset >= total || length == 0) return 0
        var cursor = offset
        var written = 0
        var remaining = minOf(length.toLong(), total - offset).toInt()
        while (remaining > 0) {
            val pageIndex = cursor / pageSize
            val pageOffset = (cursor % pageSize).toInt()
            val page = page(pageIndex)
            if (pageOffset >= page.size) break
            val count = minOf(remaining, page.size - pageOffset)
            page.copyInto(buffer, bufferOffset + written, pageOffset, pageOffset + count)
            cursor += count
            written += count
            remaining -= count
            if (pageOffset + count == page.size) prefetch(pageIndex + 1)
        }
        return written
    }

    private fun page(index: Long): ByteArray {
        if (closed) throw IsoSourceException("ISO 缓存已关闭")
        var ownsLoad = false
        val future: CompletableFuture<ByteArray>
        synchronized(this) {
            pages[index]?.let {
                hits++
                return it
            }
            misses++
            future = pending[index] ?: CompletableFuture<ByteArray>().also {
                pending[index] = it
                ownsLoad = true
            }
        }
        if (!ownsLoad) return awaitPage(future)
        return try {
            val result = loadPage(index)
            synchronized(this) {
                if (closed) throw IsoSourceException("ISO 缓存已关闭")
                pages[index] = result
                pending.remove(index)
            }
            future.complete(result)
            result
        } catch (error: Throwable) {
            synchronized(this) { pending.remove(index) }
            future.completeExceptionally(error)
            if (error is IOException) throw error
            throw IOException("ISO 缓存页读取失败", error)
        }
    }

    private fun loadPage(index: Long): ByteArray {
        val offset = index * pageSize
        val expected = minOf(pageSize.toLong(), length() - offset).coerceAtLeast(0).toInt()
        val data = ByteArray(expected)
        var read = 0
        while (read < expected) {
            val count = source.readAt(offset + read, data, read, expected - read)
            if (count <= 0) break
            read += count
        }
        return if (read == expected) data else data.copyOf(read)
    }

    private fun awaitPage(future: CompletableFuture<ByteArray>): ByteArray = try {
        future.get()
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("等待 ISO 缓存页时被中断", error)
    } catch (error: ExecutionException) {
        val cause = error.cause
        if (cause is IOException) throw cause
        throw IOException("ISO 缓存页读取失败", cause)
    }

    private fun prefetch(index: Long) {
        if (closed) return
        if (index * pageSize >= runCatching { length() }.getOrDefault(0L)) return
        synchronized(this) {
            if (pages.containsKey(index) || pending.containsKey(index)) return
        }
        runCatching {
            prefetchExecutor.execute {
                if (!closed) runCatching { page(index) }
            }
        }
    }

    @Synchronized
    override fun stats(): IsoSourceStats = source.stats().copy(cacheHits = hits, cacheMisses = misses)

    override fun close() {
        if (closed) return
        closed = true
        prefetchExecutor.shutdownNow()
        source.close()
        synchronized(this) {
            pages.clear()
            pending.clear()
        }
    }
}

private class IsoSourceException(message: String, val httpCode: Int = 0) : IOException(message)
