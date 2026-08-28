package top.cylunex.shadowmedia.network

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import top.cylunex.shadowmedia.model.LiveProgram
import top.cylunex.shadowmedia.model.ExternalMediaEntry

interface LiveGuideRepository {
    suspend fun load(sourceId: String, url: String, allowInsecureHttp: Boolean): List<LiveProgram>
}

object CatchupUrlResolver {
    fun resolve(entry: ExternalMediaEntry, program: LiveProgram): ExternalMediaEntry? {
        val template = entry.catchupSource?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val startSeconds = program.startEpochMs / 1_000
        val endSeconds = program.endEpochMs / 1_000
        val durationSeconds = (endSeconds - startSeconds).coerceAtLeast(1)
        val formatter = SimpleDateFormat("yyyy-MM-dd:HH-mm-ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val compact = formatter.format(Date(program.startEpochMs)).split(':')
        val date = compact.getOrElse(0) { "" }.split('-')
        val time = compact.getOrElse(1) { "" }.split('-')
        val replacements = mapOf(
            "{utc}" to startSeconds.toString(),
            "{lutc}" to startSeconds.toString(),
            "{start}" to startSeconds.toString(),
            "{timestamp}" to startSeconds.toString(),
            "{utcend}" to endSeconds.toString(),
            "{end}" to endSeconds.toString(),
            "{duration}" to durationSeconds.toString(),
            "{offset}" to "0",
            "{Y}" to date.getOrElse(0) { "" },
            "{m}" to date.getOrElse(1) { "" },
            "{d}" to date.getOrElse(2) { "" },
            "{H}" to time.getOrElse(0) { "" },
            "{M}" to time.getOrElse(1) { "" },
            "{S}" to time.getOrElse(2) { "" },
        )
        val resolved = replacements.entries.fold(template) { value, (token, replacement) ->
            value.replace(token, replacement, ignoreCase = true)
        }
        if ('{' in resolved || resolved.toHttpUrlOrNull() == null) return null
        return entry.copy(
            id = "${entry.id}:catchup:${program.startEpochMs}",
            title = "${entry.title} · ${program.title}",
            url = resolved,
        )
    }
}

class DefaultLiveGuideRepository(
    private val client: OkHttpClient,
) : LiveGuideRepository {
    override suspend fun load(
        sourceId: String,
        url: String,
        allowInsecureHttp: Boolean,
    ): List<LiveProgram> = withContext(Dispatchers.IO) {
        val address = url.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("节目单地址格式无效")
        require(address.username.isEmpty() && address.password.isEmpty()) { "节目单地址不能包含账号密码" }
        require(address.scheme == "https" || address.scheme == "http") { "节目单仅支持 HTTP 或 HTTPS" }
        require(address.scheme == "https" || allowInsecureHttp) { "HTTP 节目单需要在订阅时明确允许" }
        require(!address.host.isLoopbackHost()) { "节目单不能指向本机回环地址" }

        val request = Request.Builder()
            .url(address)
            .header("Accept", "application/xml, text/xml, application/gzip;q=0.9")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("节目单读取失败（HTTP ${response.code}）")
            if (address.scheme == "https" && response.request.url.scheme != "https") {
                throw IOException("已拒绝节目单从 HTTPS 降级到 HTTP")
            }
            val body = response.body
            if (body.contentLength() > MAX_COMPRESSED_EPG_BYTES) throw IOException("节目单文件过大")
            val bounded = BoundedInputStream(body.byteStream(), MAX_EXPANDED_EPG_BYTES)
            val input = if (
                response.header("Content-Encoding").equals("gzip", true) ||
                response.request.url.encodedPath.endsWith(".gz", true)
            ) {
                GZIPInputStream(bounded)
            } else {
                bounded
            }
            XmlTvParser.parse(sourceId, input)
        }
    }

    private fun String.isLoopbackHost(): Boolean =
        equals("localhost", true) || this == "127.0.0.1" || this == "::1"

    private companion object {
        const val MAX_COMPRESSED_EPG_BYTES = 32L * 1024 * 1024
        const val MAX_EXPANDED_EPG_BYTES = 96L * 1024 * 1024
    }
}

object XmlTvParser {
    fun parse(sourceId: String, input: InputStream): List<LiveProgram> {
        val handler = ProgramHandler(sourceId)
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        factory.newSAXParser().parse(input, handler)
        return handler.programs.sortedBy(LiveProgram::startEpochMs)
    }

    private class ProgramHandler(private val sourceId: String) : DefaultHandler() {
        val programs = mutableListOf<LiveProgram>()
        private var channelId: String? = null
        private var startMs: Long? = null
        private var endMs: Long? = null
        private var title: String? = null
        private var description: String? = null
        private var category: String? = null
        private var iconUrl: String? = null
        private var capture: String? = null
        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName.lowercase()) {
                "programme" -> {
                    channelId = attributes.getValue("channel")?.trim()
                    startMs = attributes.getValue("start")?.let(::parseXmlTvDate)
                    endMs = attributes.getValue("stop")?.let(::parseXmlTvDate)
                    title = null
                    description = null
                    category = null
                    iconUrl = null
                }
                "title", "desc", "category" -> {
                    capture = qName.lowercase()
                    text.setLength(0)
                }
                "icon" -> iconUrl = attributes.getValue("src")?.trim()?.takeIf(String::isNotEmpty)
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capture != null) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName.lowercase()) {
                "title" -> title = text.toString().trim().takeIf(String::isNotEmpty)
                "desc" -> description = text.toString().trim().takeIf(String::isNotEmpty)
                "category" -> category = text.toString().trim().takeIf(String::isNotEmpty)
                "programme" -> addProgram()
            }
            if (qName.equals(capture, true)) capture = null
        }

        private fun addProgram() {
            if (programs.size >= MAX_PROGRAMS) return
            val channel = channelId ?: return
            val start = startMs ?: return
            val end = endMs ?: return
            val name = title ?: return
            if (end <= start) return
            val now = System.currentTimeMillis()
            if (end < now - PAST_WINDOW_MS || start > now + FUTURE_WINDOW_MS) return
            programs += LiveProgram(
                sourceId = sourceId,
                channelId = channel,
                title = name,
                description = description,
                category = category,
                startEpochMs = start,
                endEpochMs = end,
                iconUrl = iconUrl,
            )
        }
    }

    private fun parseXmlTvDate(value: String): Long? {
        val normalized = value.trim()
        if (normalized.length < 14) return null
        val datePart = normalized.take(14)
        val zone = normalized.drop(14).trim().takeIf(String::isNotEmpty)
        val formatter = SimpleDateFormat(
            if (zone == null) "yyyyMMddHHmmss" else "yyyyMMddHHmmss Z",
            Locale.US,
        ).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return runCatching { formatter.parse(listOfNotNull(datePart, zone).joinToString(" "))?.time }.getOrNull()
    }

    private const val MAX_PROGRAMS = 100_000
    private const val PAST_WINDOW_MS = 7L * 24 * 60 * 60 * 1_000
    private const val FUTURE_WINDOW_MS = 14L * 24 * 60 * 60 * 1_000
}

private class BoundedInputStream(
    delegate: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(delegate) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) account(1) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) account(it.toLong()) }

    private fun account(read: Long) {
        count += read
        if (count > maxBytes) throw IOException("节目单解压后超过安全上限")
    }
}
