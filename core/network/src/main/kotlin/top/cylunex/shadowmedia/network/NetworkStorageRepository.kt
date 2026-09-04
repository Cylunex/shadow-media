package top.cylunex.shadowmedia.network

import android.content.Context
import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import top.cylunex.shadowmedia.model.MediaDetail
import top.cylunex.shadowmedia.model.MediaKey
import top.cylunex.shadowmedia.model.NetworkStorageConnection
import top.cylunex.shadowmedia.model.NetworkStorageHealth
import top.cylunex.shadowmedia.model.NetworkStorageKind
import top.cylunex.shadowmedia.model.NetworkStorageStatus
import top.cylunex.shadowmedia.model.PlayMethod
import top.cylunex.shadowmedia.model.PlaybackCandidate
import top.cylunex.shadowmedia.model.ProviderCapability
import top.cylunex.shadowmedia.model.ProviderDescriptor
import top.cylunex.shadowmedia.model.ProviderKind
import top.cylunex.shadowmedia.model.UnifiedMediaItem
import top.cylunex.shadowmedia.model.UnifiedMediaPage
import top.cylunex.shadowmedia.model.UnifiedPlaybackRequest
import top.cylunex.shadowmedia.provider.MediaProvider
import top.cylunex.shadowmedia.provider.ProviderBrowseRequest
import top.cylunex.shadowmedia.provider.ProviderSearchRequest
import top.cylunex.shadowmedia.provider.ProviderSection

interface NetworkReadHandle : Closeable {
    val remainingLength: Long?
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

interface NetworkStorageRepository {
    fun provider(connection: NetworkStorageConnection): MediaProvider
    suspend fun probe(connection: NetworkStorageConnection): NetworkStorageStatus
    fun openSmbResource(url: String, position: Long): NetworkReadHandle
}

class DefaultNetworkStorageRepository(
    context: Context,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val playbackState: suspend (providerId: String, itemId: String) -> Pair<Long, Boolean>? = { _, _ -> null },
) : NetworkStorageRepository {
    private val applicationContext = context.applicationContext
    private val connections = ConcurrentHashMap<String, NetworkStorageConnection>()
    private val backends = ConcurrentHashMap<String, NetworkBackend>()

    override fun provider(connection: NetworkStorageConnection): MediaProvider {
        connections[connection.id] = connection
        val backend = backend(connection)
        return NetworkStorageMediaProvider(connection, backend, ::cacheArtwork, playbackState)
    }

    override suspend fun probe(connection: NetworkStorageConnection): NetworkStorageStatus {
        val started = System.nanoTime()
        return runCatching {
            connections[connection.id] = connection
            backend(connection).list(connection.normalizedRootPath())
            NetworkStorageStatus(
                connectionId = connection.id,
                health = NetworkStorageHealth.ONLINE,
                message = "连接正常",
                latencyMs = (System.nanoTime() - started) / 1_000_000,
            )
        }.getOrElse { error ->
            val auth = error.message.orEmpty().contains("401") || error.message.orEmpty().contains("403") ||
                error.message.orEmpty().contains("logon", true) || error.message.orEmpty().contains("auth", true)
            NetworkStorageStatus(
                connectionId = connection.id,
                health = if (auth) NetworkStorageHealth.AUTH_REQUIRED else NetworkStorageHealth.OFFLINE,
                message = error.message ?: "无法连接",
                latencyMs = (System.nanoTime() - started) / 1_000_000,
            )
        }
    }

    override fun openSmbResource(url: String, position: Long): NetworkReadHandle {
        val uri = URI(url)
        require(uri.scheme == SMB_SCHEME) { "不是 Shadow SMB 地址" }
        val id = uri.host ?: throw IOException("SMB 连接标识缺失")
        val connection = connections[id] ?: throw IOException("SMB 连接已不存在")
        val encodedPath = uri.path.trimStart('/').substringBefore('/')
        val path = Base64.getUrlDecoder().decode(encodedPath).decodeToString()
        val length = uri.query?.substringAfter("length=", "")?.substringBefore('&')?.toLongOrNull()
        val backend = backend(connection) as? SmbBackend ?: throw IOException("SMB 后端不可用")
        return backend.open(path, position, length)
    }

    private fun backend(connection: NetworkStorageConnection): NetworkBackend =
        backends.compute(connection.id) { _, current ->
            if (current?.connection == connection) current else when (connection.kind) {
                NetworkStorageKind.OPENLIST -> OpenListBackend(connection, client, json)
                NetworkStorageKind.WEBDAV -> WebDavBackend(connection, client)
                NetworkStorageKind.SMB -> SmbBackend(connection)
            }
        }!!

    private suspend fun cacheArtwork(backend: NetworkBackend, path: String): String? = withContext(Dispatchers.IO) {
        val directory = File(applicationContext.cacheDir, "network_artwork").apply { mkdirs() }
        val extension = path.substringAfterLast('.', "jpg").lowercase().takeIf { it in IMAGE_EXTENSIONS } ?: "jpg"
        val digest = MessageDigest.getInstance("SHA-256").digest("${backend.connection.id}:$path".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(directory, "$digest.$extension")
        if (!target.isFile || target.length() == 0L) {
            val bytes = backend.read(path, MAX_ARTWORK_BYTES)
            if (bytes.isEmpty()) return@withContext null
            target.outputStream().use { it.write(bytes) }
        }
        Uri.fromFile(target).toString()
    }

    private companion object {
        const val SMB_SCHEME = "shadow-smb"
        const val MAX_ARTWORK_BYTES = 5 * 1024 * 1024
    }
}

private data class RemoteFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modifiedAtEpochMs: Long? = null,
    val thumbnailUrl: String? = null,
)

private data class ResolvedResource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val credentialOrigin: String? = null,
)

private interface NetworkBackend {
    val connection: NetworkStorageConnection
    suspend fun list(path: String): List<RemoteFile>
    suspend fun read(path: String, maxBytes: Int): ByteArray
    suspend fun resolve(file: RemoteFile): ResolvedResource
}

private class OpenListBackend(
    override val connection: NetworkStorageConnection,
    private val client: OkHttpClient,
    private val json: Json,
) : NetworkBackend {
    private val base = connection.validatedHttpBase()
    private val scopedClient = client.scopedCredentials(base)
    @Volatile private var token: String? = null

    override suspend fun list(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val result = mutableListOf<RemoteFile>()
        var page = 1
        var total = Long.MAX_VALUE
        while (result.size.toLong() < total) {
            val data = api(
                "api/fs/list",
                buildJsonObject {
                    put("path", path)
                    put("page", page)
                    put("per_page", OPENLIST_PAGE_SIZE)
                    put("refresh", false)
                },
            ) as? JsonObject ?: throw IOException("OpenList 目录响应无效")
            val content = data["content"] as? JsonArray ?: JsonArray(emptyList())
            total = data.long("total") ?: content.size.toLong()
            val rows = content.mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val name = row.string("name") ?: return@mapNotNull null
                RemoteFile(
                    path = joinPath(path, name),
                    name = name,
                    isDirectory = row.boolean("is_dir"),
                    size = row.long("size") ?: 0,
                    modifiedAtEpochMs = row.string("modified")?.toEpochMillisOrNull(),
                    thumbnailUrl = row.string("thumb")?.let(::resolveHttpReference),
                )
            }
            result += rows
            if (content.isEmpty()) break
            page++
        }
        result
    }

    override suspend fun read(path: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val resource = resolve(RemoteFile(path, fileName(path), false))
        val request = Request.Builder().url(resource.url).get().apply {
            resource.headers.forEach(::header)
        }.build()
        scopedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("OpenList 文件读取失败（HTTP ${response.code}）")
            response.body.readLimited(maxBytes)
        }
    }

    override suspend fun resolve(file: RemoteFile): ResolvedResource = withContext(Dispatchers.IO) {
        val data = api("api/fs/get", buildJsonObject { put("path", file.path) }) as? JsonObject
            ?: throw IOException("OpenList 文件响应无效")
        val raw = data.string("raw_url")?.takeIf(String::isNotBlank)
            ?: throw IOException("OpenList 没有返回可播放地址")
        val rawUrl = resolveHttpReference(raw).toHttpUrlOrNull()
            ?: throw IOException("OpenList 返回了无效播放地址")
        val stableUrl = if (rawUrl.sameOriginAs(base) && rawUrl.encodedPath.contains("/p/")) {
            rawUrl
        } else {
            base.newBuilder().apply {
                addPathSegment("d")
                file.path.trim('/').split('/').filter(String::isNotBlank).forEach(::addPathSegment)
                data.string("sign")?.takeIf(String::isNotBlank)?.let { addQueryParameter("sign", it) }
            }.build()
        }
        ResolvedResource(stableUrl.toString())
    }

    private fun api(path: String, payload: JsonObject, retry: Boolean = true): JsonElement? {
        val auth = if (connection.username.isBlank()) null else token ?: login()
        val request = Request.Builder()
            .url(base.resolve(path) ?: throw IOException("OpenList API 地址无效"))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply { auth?.let { header("Authorization", it) } }
            .build()
        scopedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("OpenList 请求失败（HTTP ${response.code}）")
            val root = json.parseToJsonElement(response.body.string()) as? JsonObject
                ?: throw IOException("OpenList 返回了无效 JSON")
            val code = (root["code"] as? JsonPrimitive)?.intOrNull ?: 500
            if (code == 401 && retry && connection.username.isNotBlank()) {
                token = null
                return api(path, payload, false)
            }
            if (code != 200) throw IOException(root.string("message") ?: "OpenList 请求失败（$code）")
            return root["data"]
        }
    }

    private fun login(): String {
        val payload = buildJsonObject {
            put("username", connection.username)
            put("password", connection.password)
        }
        val request = Request.Builder()
            .url(base.resolve("api/auth/login") ?: throw IOException("OpenList 登录地址无效"))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        scopedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("OpenList 登录失败（HTTP ${response.code}）")
            val root = json.parseToJsonElement(response.body.string()) as? JsonObject
                ?: throw IOException("OpenList 登录响应无效")
            val code = (root["code"] as? JsonPrimitive)?.intOrNull ?: 500
            if (code != 200) throw IOException(root.string("message") ?: "OpenList 登录失败")
            return ((root["data"] as? JsonObject)?.string("token")
                ?: throw IOException("OpenList 登录没有返回 Token")).also { token = it }
        }
    }

    private fun resolveHttpReference(value: String): String {
        val absolute = value.toHttpUrlOrNull()
        return (absolute ?: base.resolve(value) ?: throw IOException("OpenList 返回了无效地址")).toString()
    }

    private companion object {
        const val OPENLIST_PAGE_SIZE = 1_000
    }
}

private class WebDavBackend(
    override val connection: NetworkStorageConnection,
    private val client: OkHttpClient,
) : NetworkBackend {
    private val base = connection.validatedHttpBase()
    private val scopedClient = client.scopedCredentials(base)
    private val authorization = connection.username.takeIf(String::isNotBlank)?.let {
        Credentials.basic(connection.username, connection.password)
    }
    private val origin = base.origin()

    override suspend fun list(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val url = urlFor(path, directory = true)
        val request = Request.Builder().url(url)
            .method("PROPFIND", WEBDAV_PROPFIND.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .apply { authorization?.let { header("Authorization", it) } }
            .build()
        scopedClient.newCall(request).execute().use { response ->
            if (response.code != 207 && !response.isSuccessful) {
                throw IOException("WebDAV 列目录失败（HTTP ${response.code}）")
            }
            parseWebDav(response.body.byteStream(), path).filterNot { normalizePath(it.path) == normalizePath(path) }
        }
    }

    override suspend fun read(path: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(urlFor(path, false)).get()
            .apply { authorization?.let { header("Authorization", it) } }
            .build()
        scopedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("WebDAV 文件读取失败（HTTP ${response.code}）")
            response.body.readLimited(maxBytes)
        }
    }

    override suspend fun resolve(file: RemoteFile): ResolvedResource = ResolvedResource(
        url = urlFor(file.path, false).toString(),
        headers = authorization?.let { mapOf("Authorization" to it) }.orEmpty(),
        credentialOrigin = authorization?.let { origin },
    )

    private fun urlFor(path: String, directory: Boolean): HttpUrl = base.newBuilder().apply {
        path.trim('/').split('/').filter(String::isNotBlank).forEach(::addPathSegment)
        if (directory) addPathSegment("")
    }.build()

    private fun parseWebDav(input: InputStream, requestedPath: String): List<RemoteFile> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            setExpandEntityReferences(false)
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val root = factory.newDocumentBuilder().parse(input).documentElement
        val nodes = root.getElementsByTagNameNS("*", "response")
        return buildList {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                val href = element.firstText("href") ?: continue
                val displayName = element.firstText("displayname")
                val decodedPath = runCatching { URI(href).path }.getOrNull().orEmpty()
                val relative = decodedPath.removePrefix(base.encodedPath).trim('/')
                val path = if (relative.isBlank()) requestedPath else "/$relative"
                val name = displayName ?: fileName(path)
                val isDirectory = element.getElementsByTagNameNS("*", "collection").length > 0
                add(
                    RemoteFile(
                        path = normalizePath(path),
                        name = name,
                        isDirectory = isDirectory,
                        size = element.firstText("getcontentlength")?.toLongOrNull() ?: 0,
                        modifiedAtEpochMs = element.firstText("getlastmodified")?.toHttpDateEpochMillisOrNull(),
                    )
                )
            }
        }
    }
}

private class SmbBackend(override val connection: NetworkStorageConnection) : NetworkBackend {
    private val sizes = ConcurrentHashMap<String, Long>()

    override suspend fun list(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        withShare { share ->
            share.list(smbPath(path)).mapNotNull { row ->
                val name = row.fileName
                if (name == "." || name == "..") return@mapNotNull null
                val remote = RemoteFile(
                    path = joinPath(path, name),
                    name = name,
                    isDirectory = row.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                    size = row.endOfFile,
                    modifiedAtEpochMs = row.lastWriteTime.toEpochMillis(),
                )
                if (!remote.isDirectory) sizes[remote.path] = remote.size
                remote
            }
        }
    }

    override suspend fun read(path: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        open(path, 0, sizes[path]).use { handle ->
            val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (true) {
                if (output.size() == maxBytes) {
                    if (handle.read(buffer, 0, 1) < 0) break
                    throw IOException("SMB 文件超过读取上限")
                }
                val read = handle.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                if (read < 0) break
                if (read == 0 || output.size() + read > maxBytes) throw IOException("SMB 文件读取异常")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    override suspend fun resolve(file: RemoteFile): ResolvedResource {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(file.path.encodeToByteArray())
        return ResolvedResource("shadow-smb://${connection.id}/$encoded?length=${file.size}")
    }

    fun open(path: String, position: Long, knownLength: Long?): NetworkReadHandle {
        val endpoint = connection.smbEndpoint()
        val client = SMBClient()
        val networkConnection = client.connect(endpoint.first, endpoint.second)
        val auth = AuthenticationContext(
            connection.username,
            connection.password.toCharArray(),
            connection.domain,
        )
        val session = networkConnection.authenticate(auth)
        val share = session.connectShare(connection.share) as? DiskShare
            ?: throw IOException("SMB 共享不是磁盘目录")
        val file = share.openFile(
            smbPath(path),
            setOf(AccessMask.GENERIC_READ),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            setOf(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            SMB2CreateDisposition.FILE_OPEN,
            setOf(SMB2CreateOptions.FILE_RANDOM_ACCESS),
        )
        val input = file.inputStream
        input.skipFully(position)
        return object : NetworkReadHandle {
            private var remaining = knownLength?.minus(position)?.coerceAtLeast(0)
            override val remainingLength: Long? get() = remaining

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val allowed = remaining?.let { minOf(length.toLong(), it).toInt() } ?: length
                if (allowed <= 0) return -1
                val read = input.read(buffer, offset, allowed)
                if (read > 0) remaining = remaining?.minus(read)
                return read
            }

            override fun close() {
                runCatching { input.close() }
                runCatching { file.close() }
                runCatching { share.close() }
                runCatching { session.close() }
                runCatching { networkConnection.close() }
                runCatching { client.close() }
            }
        }
    }

    private inline fun <T> withShare(block: (DiskShare) -> T): T {
        val endpoint = connection.smbEndpoint()
        SMBClient().use { client ->
            client.connect(endpoint.first, endpoint.second).use { networkConnection ->
                val auth = AuthenticationContext(connection.username, connection.password.toCharArray(), connection.domain)
                networkConnection.authenticate(auth).use { session ->
                    (session.connectShare(connection.share) as DiskShare).use { share -> return block(share) }
                }
            }
        }
    }
}

private class NetworkStorageMediaProvider(
    private val storage: NetworkStorageConnection,
    private val backend: NetworkBackend,
    private val artworkCache: suspend (NetworkBackend, String) -> String?,
    private val playbackState: suspend (providerId: String, itemId: String) -> Pair<Long, Boolean>?,
) : MediaProvider {
    override val descriptor = ProviderDescriptor(
        id = "storage:${storage.id}",
        name = storage.name,
        kind = when (storage.kind) {
            NetworkStorageKind.OPENLIST -> ProviderKind.OPENLIST
            NetworkStorageKind.WEBDAV -> ProviderKind.WEBDAV
            NetworkStorageKind.SMB -> ProviderKind.SMB
        },
        capabilities = setOf(
            ProviderCapability.HOME,
            ProviderCapability.BROWSE,
            ProviderCapability.SEARCH,
            ProviderCapability.DETAIL,
            ProviderCapability.PLAYBACK,
            ProviderCapability.PROGRESS_SYNC,
            ProviderCapability.SUBTITLES,
        ),
    )
    private val files = ConcurrentHashMap<String, RemoteFile>()
    private val metadata = ConcurrentHashMap<String, NfoMetadata>()
    private val missingMetadata = ConcurrentHashMap.newKeySet<String>()
    private val metadataSemaphore = Semaphore(6)

    override suspend fun home(): List<ProviderSection> {
        val page = browse(ProviderBrowseRequest(pageSize = 60))
        return listOf(ProviderSection("${descriptor.id}:root", descriptor.name, page.items))
    }

    override suspend fun browse(request: ProviderBrowseRequest): UnifiedMediaPage {
        val path = requireWithinRoot(request.parentKey?.itemId ?: storage.normalizedRootPath())
        val all = backend.list(path).also { rows -> rows.forEach { files[it.path] = it } }
        val visible = all.filter { it.isDirectory || it.isPlayable() }
            .sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name.lowercase() })
        val offset = request.pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val size = request.pageSize.coerceIn(1, 200)
        val page = visible.drop(offset).take(size)
        return UnifiedMediaPage(
            items = page.toItems(all),
            nextPageToken = (offset + page.size).takeIf { it < visible.size }?.toString(),
            totalCount = visible.size,
        )
    }

    override suspend fun search(request: ProviderSearchRequest): UnifiedMediaPage {
        val query = request.query.trim()
        if (query.isEmpty()) return UnifiedMediaPage(emptyList())
        val results = mutableListOf<RemoteFile>()
        val queue = ArrayDeque<String>().apply { add(storage.normalizedRootPath()) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SEARCH_DIRECTORIES && results.size < MAX_SEARCH_RESULTS) {
            val path = queue.removeFirst()
            val rows = runCatching { backend.list(path) }.getOrDefault(emptyList())
            visited++
            rows.forEach { file ->
                files[file.path] = file
                if (file.isDirectory) queue.addLast(file.path)
                if ((file.isDirectory || file.isPlayable()) && file.name.contains(query, true)) results += file
            }
        }
        val offset = request.pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = results.drop(offset).take(request.pageSize.coerceIn(1, 200))
        return UnifiedMediaPage(
            items = page.toItems(emptyList()),
            nextPageToken = (offset + page.size).takeIf { it < results.size }?.toString(),
            totalCount = results.size,
        )
    }

    override suspend fun detail(key: MediaKey): MediaDetail {
        require(key.providerId == descriptor.id) { "媒体不属于这个网络存储" }
        val safePath = requireWithinRoot(key.itemId)
        val guessedName = fileName(safePath)
        val file = files[safePath] ?: RemoteFile(
            safePath,
            guessedName,
            isDirectory = guessedName.substringAfterLast('.', "").lowercase() !in PLAYABLE_EXTENSIONS,
        )
        if (!file.isDirectory) {
            val siblings = backend.list(parentPath(file.path)).also { rows -> rows.forEach { files[it.path] = it } }
            val item = toItem(file, siblings)
            val nfo = nfoFor(file, siblings)
            return MediaDetail(item, genres = nfo?.genres.orEmpty(), people = nfo?.actors.orEmpty())
        }
        val children = backend.list(file.path).also { rows -> rows.forEach { files[it.path] = it } }
        val folderNfo = folderNfo(file.path, children)
        val poster = companionArtwork(file.name, children)?.let { artworkCache(backend, it.path) }
        val item = UnifiedMediaItem(
            key = MediaKey(descriptor.id, file.path),
            title = folderNfo?.title ?: cleanTitle(file.name),
            type = "Folder",
            overview = folderNfo?.plot,
            posterUrl = poster,
            year = folderNfo?.year,
            rating = folderNfo?.rating,
            externalIds = folderNfo?.externalIds.orEmpty(),
        )
        return MediaDetail(
            item = item,
            children = children.filter { it.isDirectory || it.isPlayable() }.toItems(children),
            genres = folderNfo?.genres.orEmpty(),
            people = folderNfo?.actors.orEmpty(),
        )
    }

    override suspend fun resolve(request: UnifiedPlaybackRequest): List<PlaybackCandidate> {
        val safePath = requireWithinRoot(request.key.itemId)
        val file = files[safePath]
            ?: RemoteFile(safePath, fileName(safePath), false)
        require(!file.isDirectory) { "文件夹不能直接播放" }
        if (file.extension() == "strm" && storage.resolveStrm) {
            val line = backend.read(file.path, MAX_STRM_BYTES).decodeToString()
                .lineSequence().map { it.trim().removePrefix("\uFEFF") }
                .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
                ?: throw IOException("STRM 没有包含可播放地址")
            val (target, strmHeaders) = parseStrmLine(line)
            if (target.startsWith("http://") || target.startsWith("https://")) {
                val targetUrl = target.toHttpUrlOrNull() ?: throw IOException("STRM 播放地址无效")
                if (targetUrl.scheme == "http" && !storage.allowInsecureHttp) {
                    throw IOException("STRM 使用 HTTP，请在网络存储中明确允许明文连接")
                }
                return listOf(
                    PlaybackCandidate(
                        target,
                        PlayMethod.DIRECT_PLAY,
                        strmHeaders,
                        isDiscImage = targetUrl.encodedPath.substringAfterLast('.', "").equals("iso", true),
                    )
                )
            }
            val targetPath = requireWithinRoot(
                if (target.startsWith('/')) normalizePath(target) else joinPath(parentPath(file.path), target)
            )
            val nested = RemoteFile(targetPath, fileName(targetPath), false, files[targetPath]?.size ?: 0)
            val resolved = backend.resolve(withFileDetails(nested))
            return listOf(resolved.toCandidate(targetPath.substringAfterLast('.', "").equals("iso", true)))
        }
        return listOf(backend.resolve(withFileDetails(file)).toCandidate(file.extension() == "iso"))
    }

    private suspend fun toItem(file: RemoteFile, siblings: List<RemoteFile>): UnifiedMediaItem {
        if (file.isDirectory) {
            val children = runCatching { backend.list(file.path) }.getOrDefault(emptyList())
                .also { rows -> rows.forEach { files[it.path] = it } }
            val nfo = folderNfo(file.path, children)
            val poster = companionArtwork(file.name, siblings)?.let { artworkCache(backend, it.path) }
                ?: companionArtwork(file.name, children)?.let { artworkCache(backend, it.path) }
                ?: children.firstOrNull { it.name.equals("poster.jpg", true) || it.name.equals("folder.jpg", true) }
                    ?.let { artworkCache(backend, it.path) }
            return UnifiedMediaItem(
                key = MediaKey(descriptor.id, file.path),
                title = nfo?.title ?: cleanTitle(file.name),
                type = "Folder",
                subtitle = listOfNotNull(nfo?.year?.toString(), "文件夹").joinToString(" · "),
                overview = nfo?.plot,
                posterUrl = poster,
                year = nfo?.year,
                rating = nfo?.rating,
                externalIds = nfo?.externalIds.orEmpty(),
            )
        }
        val nfo = nfoFor(file, siblings)
        val state = playbackState(descriptor.id, file.path)
        val posterFile = companionArtwork(file.name, siblings)
        val poster = posterFile?.let { artworkCache(backend, it.path) } ?: file.thumbnailUrl
        val episode = listOfNotNull(nfo?.season?.let { "S${it.toString().padStart(2, '0')}" }, nfo?.episode?.let { "E${it.toString().padStart(2, '0')}" })
            .joinToString("")
        return UnifiedMediaItem(
            key = MediaKey(descriptor.id, file.path),
            title = nfo?.title ?: cleanTitle(file.name.substringBeforeLast('.')),
            type = when (top.cylunex.shadowmedia.model.contentKindForFile(file.name)) {
                top.cylunex.shadowmedia.model.ContentKind.BOOK -> "Book"
                top.cylunex.shadowmedia.model.ContentKind.COMIC -> "Comic"
                top.cylunex.shadowmedia.model.ContentKind.AUDIOBOOK -> "AudioBook"
                else -> if (file.extension() == "strm") "Strm" else "Video"
            },
            subtitle = listOfNotNull(episode.takeIf(String::isNotBlank), file.extension().uppercase()).joinToString(" · "),
            overview = nfo?.plot,
            posterUrl = poster,
            year = nfo?.year,
            rating = nfo?.rating,
            durationMs = nfo?.runtimeMinutes?.times(60_000L),
            progressMs = state?.first ?: 0,
            played = state?.second ?: false,
            externalIds = nfo?.externalIds.orEmpty(),
        )
    }

    private suspend fun nfoFor(file: RemoteFile, siblings: List<RemoteFile>): NfoMetadata? {
        if (!storage.readNfo) return null
        val base = file.name.substringBeforeLast('.').lowercase()
        val candidate = siblings.firstOrNull { !it.isDirectory && it.extension() == "nfo" && it.name.substringBeforeLast('.').lowercase() == base }
            ?: (if (siblings.count(RemoteFile::isPlayable) == 1) siblings.firstOrNull { it.name.equals("movie.nfo", true) } else null)
            ?: return null
        return parseCachedNfo(candidate)
    }

    private suspend fun folderNfo(path: String, children: List<RemoteFile>): NfoMetadata? {
        if (!storage.readNfo) return null
        val candidate = children.firstOrNull { it.name.equals("tvshow.nfo", true) || it.name.equals("movie.nfo", true) }
            ?: children.firstOrNull { it.extension() == "nfo" && it.name.substringBeforeLast('.').equals(fileName(path), true) }
            ?: return null
        return parseCachedNfo(candidate)
    }

    private suspend fun parseCachedNfo(file: RemoteFile): NfoMetadata? {
        metadata[file.path]?.let { return it }
        if (file.path in missingMetadata) return null
        return runCatching { parseNfoMetadata(backend.read(file.path, MAX_NFO_BYTES).decodeToString()) }
            .onSuccess { metadata[file.path] = it }
            .onFailure { missingMetadata += file.path }
            .getOrNull()
    }

    private suspend fun List<RemoteFile>.toItems(siblings: List<RemoteFile>): List<UnifiedMediaItem> = buildList(size) {
        this@toItems.chunked(METADATA_BATCH_SIZE).forEach { batch ->
            addAll(
                coroutineScope {
                    batch.map { file -> async { metadataSemaphore.withPermit { toItem(file, siblings) } } }.awaitAll()
                }
            )
        }
    }

    private fun companionArtwork(baseName: String, siblings: List<RemoteFile>): RemoteFile? {
        val base = baseName.substringBeforeLast('.').lowercase()
        val preferred = listOf("$base-poster", base, "poster", "folder", "$base-thumb", "thumb")
        return preferred.firstNotNullOfOrNull { wanted ->
            siblings.firstOrNull {
                !it.isDirectory && it.extension() in IMAGE_EXTENSIONS &&
                    normalizeArtworkStem(it.name.substringBeforeLast('.')) == normalizeArtworkStem(wanted)
            }
        }
    }

    private fun ResolvedResource.toCandidate(isDiscImage: Boolean = false) = PlaybackCandidate(
        url = url,
        method = PlayMethod.DIRECT_PLAY,
        requiredHeaders = headers,
        credentialOrigin = credentialOrigin,
        isDiscImage = isDiscImage,
    )

    private fun requireWithinRoot(path: String): String {
        val root = storage.normalizedRootPath()
        val normalized = normalizePath(path)
        require(root == "/" || normalized == root || normalized.startsWith("$root/")) {
            "媒体路径超出已配置根目录"
        }
        return normalized
    }

    private suspend fun withFileDetails(file: RemoteFile): RemoteFile {
        if (file.isDirectory || file.size > 0) return file
        return runCatching { backend.list(parentPath(file.path)).firstOrNull { it.path == file.path } }
            .getOrNull() ?: file
    }

    private companion object {
        const val MAX_NFO_BYTES = 2 * 1024 * 1024
        const val MAX_STRM_BYTES = 64 * 1024
        const val MAX_SEARCH_DIRECTORIES = 200
        const val MAX_SEARCH_RESULTS = 1_000
        const val METADATA_BATCH_SIZE = 120
    }
}

private fun NetworkStorageConnection.validatedHttpBase(): HttpUrl {
    val value = address.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("服务地址格式无效")
    require(value.username.isEmpty() && value.password.isEmpty()) { "服务地址不能内嵌账号密码" }
    require(value.scheme == "https" || (value.scheme == "http" && allowInsecureHttp)) {
        "HTTP 服务需要明确允许明文连接"
    }
    return value.newBuilder().apply { if (!value.encodedPath.endsWith('/')) addPathSegment("") }.build()
}

private fun NetworkStorageConnection.normalizedRootPath(): String = normalizePath(rootPath)

private fun NetworkStorageConnection.smbEndpoint(): Pair<String, Int> {
    val raw = address.trim().removePrefix("smb://").substringBefore('/')
    require(raw.isNotBlank()) { "SMB 主机不能为空" }
    val host = raw.substringBeforeLast(':', raw)
    val port = raw.substringAfterLast(':', "445").toIntOrNull() ?: 445
    require(share.isNotBlank()) { "SMB 共享名不能为空" }
    return host to port
}

private fun RemoteFile.isPlayable(): Boolean = !isDirectory && extension() in PLAYABLE_EXTENSIONS
private fun RemoteFile.extension(): String = name.substringAfterLast('.', "").lowercase()
private fun normalizePath(path: String): String = "/" + path.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() && it != "." }.fold(mutableListOf<String>()) { acc, part ->
    if (part == "..") { if (acc.isNotEmpty()) acc.removeAt(acc.lastIndex) } else acc += part
    acc
}.joinToString("/")
private fun joinPath(parent: String, child: String): String = normalizePath("${parent.trimEnd('/')}/$child")
private fun parentPath(path: String): String = normalizePath(path.substringBeforeLast('/', ""))
private fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifBlank { "/" }
private fun cleanTitle(value: String): String = value.replace('.', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
private fun normalizeArtworkStem(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
private fun parseStrmLine(line: String): Pair<String, Map<String, String>> {
    val target = line.substringBefore('|').trim()
    val headers = line.substringAfter('|', "").split('&').mapNotNull { token ->
        val key = token.substringBefore('=', "").trim()
        val value = token.substringAfter('=', "").trim()
        val canonical = SAFE_STRM_HEADERS.firstOrNull { it.equals(key, true) }
        if (canonical == null || value.isEmpty()) null
        else canonical to runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
    }.toMap()
    return target to headers
}
private fun smbPath(path: String): String = normalizePath(path).trim('/').replace('/', '\\')
private fun HttpUrl.origin(): String = newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
private fun HttpUrl.sameOriginAs(other: HttpUrl): Boolean = scheme == other.scheme && host == other.host && port == other.port
private fun OkHttpClient.scopedCredentials(origin: HttpUrl): OkHttpClient = newBuilder()
    .addNetworkInterceptor { chain ->
        val request = chain.request()
        val scoped = if (request.url.sameOriginAs(origin)) request else request.newBuilder()
            .removeHeader("Authorization")
            .removeHeader("Cookie")
            .build()
        chain.proceed(scoped)
    }
    .build()
private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
private fun JsonObject.boolean(key: String): Boolean = (get(key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
private fun String.toEpochMillisOrNull(): Long? = runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
private fun String.toHttpDateEpochMillisOrNull(): Long? = runCatching { ZonedDateTime.parse(this, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
private fun Element.firstText(localName: String): String? = getElementsByTagNameNS("*", localName).item(0)?.textContent?.trim()?.takeIf(String::isNotEmpty)

private fun okhttp3.ResponseBody.readLimited(maxBytes: Int): ByteArray {
    if (contentLength() > maxBytes) throw IOException("远程文件超过读取上限")
    return byteStream().use { input ->
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > maxBytes) throw IOException("远程文件超过读取上限")
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
}

private fun InputStream.skipFully(position: Long) {
    var remaining = position
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) remaining -= skipped else if (read() < 0) throw IOException("SMB 文件短于请求位置") else remaining--
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
private val PLAYABLE_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "m4v", "ts", "m2ts", "webm", "flv", "wmv", "mpg", "mpeg", "iso", "strm",
    "epub", "txt", "pdf", "cbz", "zip", "m4b", "m4a", "mp3", "aac", "ogg", "opus", "flac", "wav")
private val SAFE_STRM_HEADERS = setOf("User-Agent", "Referer", "Origin")
private const val WEBDAV_PROPFIND = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
