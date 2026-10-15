package top.cylunex.shadowmedia.network

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element

/** Ephemeral descriptions and control addresses belong only to the current discovery session. */
data class DlnaDevice(val id: String, val name: String, val descriptionUrl: String, val controlUrl: String, val serviceType: String)
data class DlnaPosition(val positionMs: Long, val durationMs: Long, val transport: String, val trackUri: String)

class DlnaClient(private val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(OfflineModeInterceptor())
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).followRedirects(false).build()) {
    suspend fun discover(): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val locations = linkedMapOf<String, InetAddress>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 250
            val search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n".toByteArray(Charsets.US_ASCII)
            socket.send(DatagramPacket(search, search.size, InetAddress.getByName("239.255.255.250"), 1900))
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (System.nanoTime() < end && locations.size < 32) {
                currentCoroutineContext().ensureActive()
                val packet = DatagramPacket(ByteArray(8192), 8192)
                try {
                    socket.receive(packet)
                    val headers = packet.data.copyOf(packet.length).toString(Charsets.US_ASCII).lineSequence().mapNotNull {
                        val index = it.indexOf(':'); if (index < 0) null else it.substring(0, index).trim().lowercase() to it.substring(index + 1).trim()
                    }.toMap()
                    headers["location"]?.let { value ->
                        val url = runCatching { value.toHttpUrl() }.getOrNull()
                        if (url != null && url.username.isEmpty() && url.password.isEmpty() && InetAddress.getAllByName(url.host).contains(packet.address)) locations[value] = packet.address
                    }
                } catch (_: SocketTimeoutException) { }
            }
        }
        locations.keys.mapNotNull { location ->
            currentCoroutineContext().ensureActive()
            try { describe(location) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        }.distinctBy { it.id }
    }
    suspend fun describe(location: String): DlnaDevice = withContext(Dispatchers.IO) {
        val origin = location.toHttpUrl()
        require(origin.username.isEmpty() && origin.password.isEmpty())
        require(InetAddress.getAllByName(origin.host).all { it.isSiteLocalAddress || it.isLinkLocalAddress || it.isLoopbackAddress }) { "请选择局域网播放设备" }
        val root = xml(request(Request.Builder().url(origin).get().build())).documentElement
        val service = root.getElementsByTagNameNS("*", "service").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }.firstOrNull { text(it, "serviceType").startsWith("urn:schemas-upnp-org:service:AVTransport:") }
        } ?: throw IOException("设备不提供 AVTransport 播放服务")
        val base = text(root, "URLBase").takeIf { it.isNotBlank() }?.toHttpUrl() ?: origin
        val control = base.resolve(text(service, "controlURL")) ?: throw IOException("设备控制地址无效")
        require(control.scheme == origin.scheme && control.host == origin.host && control.port == origin.port && control.username.isEmpty() && control.password.isEmpty()) { "设备控制地址跨来源" }
        require(Regex("urn:schemas-upnp-org:service:AVTransport:[1-9][0-9]*").matches(text(service, "serviceType")))
        DlnaDevice(text(root, "UDN").ifBlank { origin.toString() }, text(root, "friendlyName").ifBlank { "DLNA 播放设备" }, location, control.toString(), text(service, "serviceType"))
    }
    suspend fun setMedia(device: DlnaDevice, uri: String, title: String, mime: String) {
        val metadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${escapeXml(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"http-get:*:${escapeXml(mime)}:*\">${escapeXml(uri)}</res></item></DIDL-Lite>"
        action(device, "SetAVTransportURI", linkedMapOf("CurrentURI" to uri, "CurrentURIMetaData" to metadata))
    }
    suspend fun play(device: DlnaDevice) { action(device, "Play", mapOf("Speed" to "1")) }
    suspend fun pause(device: DlnaDevice) { action(device, "Pause") }
    suspend fun stop(device: DlnaDevice) { action(device, "Stop") }
    suspend fun seek(device: DlnaDevice, positionMs: Long) { action(device, "Seek", mapOf("Unit" to "REL_TIME", "Target" to dlnaTime(positionMs))) }
    suspend fun position(device: DlnaDevice): DlnaPosition {
        val position = xml(action(device, "GetPositionInfo")).documentElement
        val transport = xml(action(device, "GetTransportInfo")).documentElement
        return DlnaPosition(parseDlnaTime(text(position, "RelTime")), parseDlnaTime(text(position, "TrackDuration")), text(transport, "CurrentTransportState"), text(position, "TrackURI"))
    }
    private suspend fun action(device: DlnaDevice, name: String, arguments: Map<String, String> = emptyMap()): ByteArray = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val values = linkedMapOf("InstanceID" to "0").apply { putAll(arguments) }.entries.joinToString("") { (key, value) -> "<$key>${escapeXml(value)}</$key>" }
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$name xmlns:u=\"${device.serviceType}\">$values</u:$name></s:Body></s:Envelope>"
        val response = request(Request.Builder().url(device.controlUrl).header("SOAPAction", "\"${device.serviceType}#$name\"").post(body.toRequestBody("text/xml; charset=utf-8".toMediaType())).build())
        if (response.isNotEmpty() && xml(response).getElementsByTagNameNS("*", "Fault").length > 0) throw IOException("播放设备拒绝了此操作")
        response
    }
    private fun request(request: Request): ByteArray = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("设备请求失败（HTTP ${response.code}）")
        val source = response.body?.source() ?: throw IOException("设备没有返回内容")
        source.request(512 * 1024L + 1)
        require(source.buffer.size <= 512 * 1024) { "设备响应过大" }
        source.readByteArray()
    }
    companion object {
        internal fun xml(bytes: ByteArray): org.w3c.dom.Document {
            // Android's parser does not implement the Xerces security feature switches.
            // Decode strictly first, reject declarations before any parser sees them, then
            // use a character source so an encoding declaration cannot bypass the guard.
            require(bytes.size <= 512 * 1024)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            require('\u0000' !in text && !Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "设备 XML 声明不受支持" }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw org.xml.sax.SAXException("拒绝外部 XML 实体") }
            }
            return builder.parse(org.xml.sax.InputSource(java.io.StringReader(text)))
        }
        private fun text(element: Element, name: String) = element.getElementsByTagNameNS("*", name).item(0)?.textContent.orEmpty().trim()
    }
}
internal fun escapeXml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
fun dlnaTime(ms: Long): String { val s = ms.coerceAtLeast(0) / 1000; return "%02d:%02d:%02d".format(java.util.Locale.ROOT, s / 3600, s / 60 % 60, s % 60) }
fun parseDlnaTime(value: String): Long = runCatching {
    val parts = value.split(':'); require(parts.size == 3)
    val h = parts[0].toLong(); val m = parts[1].toLong(); val s = parts[2].toDouble()
    require(h in 0..1_000_000 && m in 0..59 && s.isFinite() && s >= 0 && s < 60)
    (h * 3_600_000 + m * 60_000 + (s * 1000).toLong())
}.getOrDefault(0)
