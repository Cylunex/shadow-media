package top.cylunex.shadowmedia.library

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import top.cylunex.shadowmedia.database.LibraryAssetEntity
import top.cylunex.shadowmedia.model.*

class NativeCatalogRepository(private val context: Context, private val library: LibraryRepository) {
    val store = CatalogConnectionStore(context)
    private val syncLock = Mutex()
    private val nativeMusic = java.util.concurrent.ConcurrentHashMap<String, NativeMusicProvider>()
    fun musicProvider(c: CatalogConnection): NativeMusicProvider {
        require(c.kind.isNativeMusic())
        return nativeMusic.compute(c.id) { _, old -> if (old?.connection == c) old else NativeMusicProvider(c) }!!
    }
    fun musicProviders(): List<NativeMusicProvider> {
        val connections = store.load().filter { it.kind.isNativeMusic() }
        nativeMusic.keys.retainAll(connections.map { it.id }.toSet())
        return connections.map(::musicProvider)
    }
    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().addInterceptor(top.cylunex.shadowmedia.network.OfflineModeInterceptor()).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    fun providerId(connection: CatalogConnection) = "catalog:${connection.kind.name}:${connection.id}"
    fun connection(providerId: String) = requireNotNull(store.load().firstOrNull { providerId(it) == providerId }) { "来源已移除，请重新连接" }
    private fun api(c: CatalogConnection, vararg segments: String): HttpUrl = c.base().newBuilder().apply {
        if (!c.base().encodedPath.endsWith('/')) addPathSegment("")
        segments.forEach(::addPathSegment)
    }.build()
    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    private fun credentials(c: CatalogConnection, url: HttpUrl): Map<String, String> {
        if (!sameOrigin(c.base(), url)) return emptyMap()
        return when {
            c.token.isNotBlank() -> if (c.kind == CatalogKind.KOMGA) mapOf("X-API-Key" to c.token) else mapOf("Authorization" to "Bearer ${c.token}")
            c.username.isNotBlank() -> mapOf("Authorization" to Credentials.basic(c.username, c.password))
            else -> emptyMap()
        }
    }
    private fun candidate(c: CatalogConnection, url: String) = PlaybackCandidate(url, PlayMethod.DIRECT_PLAY, credentials(c, url.toHttpUrl()), credentialOrigin = c.base().toString())
    private suspend fun request(c: CatalogConnection, url: HttpUrl, body: JSONObject? = null): ByteArray = withContext(Dispatchers.IO) {
        require(url.isHttps || c.allowHttp) { "来源包含未经允许的 HTTP 地址" }
        require(url.username.isBlank() && url.password.isBlank())
        val scoped = client.newBuilder().addNetworkInterceptor { chain ->
            val req = chain.request(); val builder = req.newBuilder()
            if (body != null && !sameOrigin(c.base(), req.url)) throw java.io.IOException("拒绝跨域发送阅读进度")
            if (!sameOrigin(c.base(), req.url)) listOf("Authorization", "Cookie", "X-API-Key").forEach(builder::removeHeader)
            if (!req.url.isHttps && !c.allowHttp) throw java.io.IOException("重定向使用未经允许的 HTTP")
            chain.proceed(builder.build())
        }.build()
        val call = scoped.newCall(Request.Builder().url(url).apply {
            credentials(c, url).forEach { (k, v) -> header(k, v) }
            if (body != null) { require(sameOrigin(c.base(), url)); patch(body.toString().toRequestBody("application/json".toMediaType())) }
        }.build())
        val cancellation = launch { try { awaitCancellation() } finally { call.cancel() } }
        try { call.execute().use { response ->
            require(response.isSuccessful) { "来源请求失败 HTTP ${response.code}" }
            response.body?.byteStream()?.use { SafeArchives.readBounded(it, 8L * 1024 * 1024) } ?: byteArrayOf()
        } } finally { cancellation.cancel() }
    }
    private suspend fun json(c: CatalogConnection, url: HttpUrl) = JSONObject(request(c, url).toString(Charsets.UTF_8))
    private fun JSONArray?.objects() = this?.let { (0 until length()).mapNotNull { optJSONObject(it) } }.orEmpty()

    private val snapshotDao = top.cylunex.shadowmedia.database.ShadowMediaDatabase.create(context).libraryStateDao()
    private fun pageKey(c: CatalogConnection, node: String?, next: String?, query: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(scopedContentId(providerId(c), node.orEmpty(), next.orEmpty(), query).toByteArray()).joinToString("") { "%02x".format(it) }
    suspend fun cachedPage(c: CatalogConnection, node: String? = null, next: String? = null, query: String = ""): CatalogPage? =
        snapshotDao.catalogPage(pageKey(c, node, next, query))?.let { runCatching { CatalogSnapshotCodec.decode(it.payload).copy(updatedAt = it.updatedAt) }.getOrNull() }
    suspend fun browse(c: CatalogConnection, node: String? = null, next: String? = null, query: String = ""): CatalogPage {
        if (LibraryResources.offlineOnly) return cachedPage(c, node, next, query) ?: error("此目录尚未缓存在本机")
        return try {
            val page = browseRemote(c, node, next, query)
            if (page.entries.isEmpty()) cachedPage(c, node, next, query)?.takeIf { it.entries.isNotEmpty() }?.let { return it }
            val now = System.currentTimeMillis()
            val payload = CatalogSnapshotCodec.encode(page, c.kind)
            if (payload.toByteArray().size <= 256 * 1024) {
                snapshotDao.cacheCatalogPage(top.cylunex.shadowmedia.database.CatalogPageEntity(pageKey(c, node, next, query), payload, now))
            }
            page.copy(updatedAt = now)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { cachedPage(c, node, next, query) ?: throw e }
    }
    private suspend fun browseRemote(c: CatalogConnection, node: String? = null, next: String? = null, query: String = ""): CatalogPage = when (c.kind) {
        CatalogKind.JELLYFIN, CatalogKind.OPENSUBSONIC -> {
            val provider = musicProvider(c)
            val page = if (query.isNotBlank()) provider.search(top.cylunex.shadowmedia.provider.ProviderSearchRequest(query, pageToken = next))
                else provider.browse(top.cylunex.shadowmedia.provider.ProviderBrowseRequest(node?.let { MediaKey(provider.descriptor.id, it) }, pageToken = next))
            CatalogPage(c.name, page.items.map { item ->
                val folder = contentKind(item.type) in setOf(ContentKind.FOLDER, ContentKind.SERIES)
                CatalogEntry(item.key.itemId, item.title, item.subtitle.orEmpty(), format = if (folder) "" else "audio", navigation = item.key.itemId.takeIf { folder })
            }, page.nextPageToken)
        }
        CatalogKind.OPDS -> {
            val url = (next ?: node ?: c.url).toHttpUrl()
            OpdsCatalog.parse(request(c, url), url.toString())
        }
        CatalogKind.KOMGA -> {
            val page = next?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val url = api(c, "api", "v1", "books").newBuilder().addQueryParameter("page", page.toString()).addQueryParameter("size", "60").apply { if (query.isNotBlank()) addQueryParameter("search", query) }.build()
            val data = json(c, url)
            CatalogPage(c.name, data.optJSONArray("content").objects().map { book ->
                val metadata = book.optJSONObject("metadata"); val id = book.getString("id")
                CatalogEntry(id, metadata?.optString("title")?.ifBlank { null } ?: book.optString("name"),
                    metadata?.optJSONArray("authors").objects().joinToString("、") { it.optString("name") }, format = "komga",
                    cover = api(c, "api", "v1", "books", id, "thumbnail").toString())
            }, if (!data.optBoolean("last", true)) (page + 1).toString() else null)
        }
        CatalogKind.AUDIOBOOKSHELF -> {
            when {
                node == null -> {
                    val data = json(c, api(c, "api", "libraries"))
                    CatalogPage(c.name, data.optJSONArray("libraries").objects().filter { it.optString("mediaType") == "book" }.map { CatalogEntry(it.getString("id"), it.optString("name"), navigation = "library:${it.getString("id")}") })
                }
                node.startsWith("library:") -> {
                    val page = next?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val data = json(c, api(c, "api", "libraries", node.removePrefix("library:"), "items").newBuilder().addQueryParameter("limit", "60").addQueryParameter("page", page.toString()).build())
                    CatalogPage(c.name, data.optJSONArray("results").objects().map { book ->
                        val meta = book.optJSONObject("media")?.optJSONObject("metadata")
                        CatalogEntry(book.getString("id"), meta?.optString("title").orEmpty(), meta?.optString("authorName").orEmpty(), navigation = "item:${book.getString("id")}")
                    }, if ((page + 1) * 60 < data.optInt("total")) (page + 1).toString() else null)
                }
                node.startsWith("item:") -> {
                    val id = node.removePrefix("item:"); val data = absItem(c, id); val media = data.getJSONObject("media")
                    val meta = media.getJSONObject("metadata")
                    val tracks = media.optJSONArray("tracks").objects().ifEmpty { media.optJSONArray("audioFiles").objects().filterNot { it.optBoolean("exclude") } }
                    CatalogPage(meta.optString("title"), tracks.map { track ->
                        val metadata = track.optJSONObject("metadata")
                        CatalogEntry("$id::${track.getString("ino")}", track.optString("title").ifBlank { metadata?.optString("filename").orEmpty() }, meta.optString("title"),
                            OpdsCatalog.format(track.optString("mimeType"), metadata?.optString("filename").orEmpty()).ifBlank { "m4b" }, cover = api(c, "api", "items", id, "cover").toString())
                    })
                }
                else -> error("未知的听书目录")
            }
        }
    }
    private suspend fun absItem(c: CatalogConnection, id: String) = json(c, api(c, "api", "items", id).newBuilder().addQueryParameter("expanded", "1").addQueryParameter("include", "progress").build())

    suspend fun addQueue(c: CatalogConnection, entries: List<CatalogEntry>): List<LibraryAssetEntity> {
        require(c.kind == CatalogKind.AUDIOBOOKSHELF && entries.isNotEmpty() && entries.size <= 2000)
        val bookId = entries.first().locator.substringBefore("::")
        require(entries.all { it.locator.substringBefore("::") == bookId }) { "队列必须来自同一本书" }
        val metadata = absItem(c, bookId)
        return entries.mapIndexed { index, entry -> add(c, entry, metadata, artwork = index == 0) }
    }

    suspend fun add(c: CatalogConnection, entry: CatalogEntry, metadata: JSONObject? = null, artwork: Boolean = true): LibraryAssetEntity {
        if (c.kind.isNativeMusic()) {
            val item = musicProvider(c).detail(MediaKey(providerId(c), entry.locator)).item
            return MusicRepository(context, library).importRemote(item)
        }
        val kind = if (entry.format == "komga") ContentKind.COMIC else contentKindForFile("file.${entry.format}")
        require(kind in setOf(ContentKind.BOOK, ContentKind.COMIC, ContentKind.AUDIOBOOK)) { "尚不支持这个文件格式" }
        val asset = library.addRemote(UnifiedMediaItem(MediaKey(providerId(c), entry.locator), entry.title, kind.name, subtitle = entry.author), entry.format)
        if (c.kind == CatalogKind.AUDIOBOOKSHELF) {
            val book = metadata ?: absItem(c, asset.itemId.substringBefore("::"))
            val media = book.getJSONObject("media")
            val track = media.optJSONArray("tracks").objects().firstOrNull { it.optString("ino") == asset.itemId.substringAfter("::") }
            if (track != null) {
                val offset = track.optDouble("startOffset", 0.0)
                val duration = track.optDouble("duration")
                if (offset.isFinite() && offset >= 0 && duration.isFinite() && duration > 0) {
                    val chapters = media.optJSONArray("chapters").objects().mapNotNull { chapter ->
                        val start = chapter.optDouble("start"); val end = chapter.optDouble("end")
                        if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) null
                        else BookChapter(chapter.optString("id"), chapter.optString("title"), (start * 1000).toLong(), (end * 1000).toLong())
                    }
                    val mapped = trackChapters(chapters, asset.itemId, asset.revision, (offset * 1000).toLong(), (duration * 1000).toLong())
                    top.cylunex.shadowmedia.database.ShadowMediaDatabase.create(context).chapterDao().replace(asset.id,
                        mapped.map { top.cylunex.shadowmedia.database.AudioChapterEntity(asset.id, it.chapterId, it.resourceRevision, it.trackId, it.title, it.startMs, it.endMs) }.distinctBy { it.chapterId })
                }
            }
        }
        if (library.dao.progress(asset.id) == null) {
            try { pullInitialProgress(c, asset, metadata) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Missing progress capability must not prevent consumption. */ }
        }
        if (artwork && entry.cover != null && asset.coverPath.isBlank()) {
            artworkScope.launch {
                var target: File? = null
                try {
                    if (library.dao.asset(asset.id) == null) return@launch
                    target = File.createTempFile("artwork-", ".img", library.folder(asset.id))
                    ResourceDownloader(client).download(candidate(c, entry.cover), target, 12L * 1024 * 1024)
                    // Updating only this column cannot resurrect a removed asset or overwrite a
                    // concurrently downloaded copy, favorite, or metadata with a stale snapshot.
                    if (library.dao.fillCover(asset.id, target.path) > 0) target = null
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Artwork is optional and never delays opening. */ }
                finally { target?.delete() }
            }
        }
        return asset
    }

    /** Only seeds a never-opened resource. Existing local progress/edits are never overwritten. */
    private suspend fun pullInitialProgress(c: CatalogConnection, asset: LibraryAssetEntity, metadata: JSONObject? = null) {
        val record = when (c.kind) {
            CatalogKind.KOMGA -> {
                val book = json(c, api(c, "api", "v1", "books", asset.itemId))
                val read = book.optJSONObject("readProgress") ?: return
                val page = (read.optInt("page", 1) - 1).coerceAtLeast(0)
                val count = book.optJSONObject("media")?.optInt("pagesCount") ?: 0
                top.cylunex.shadowmedia.database.ContentProgressEntity(asset.id, locatorType = "page",
                    locatorJson = JSONObject().put("chapterId", asset.id).put("pageIndex", page).put("offset", 0).toString(),
                    progression = if (count > 0) (page.toDouble() / count).coerceIn(0.0, 1.0) else null,
                    completed = read.optBoolean("completed"), updatedAt = System.currentTimeMillis())
            }
            CatalogKind.AUDIOBOOKSHELF -> {
                val book = metadata ?: absItem(c, asset.itemId.substringBefore("::"))
                val read = book.optJSONObject("userMediaProgress") ?: return
                val tracks = book.getJSONObject("media").optJSONArray("tracks").objects()
                val track = tracks.firstOrNull { it.optString("ino") == asset.itemId.substringAfter("::") } ?: return
                val duration = track.optDouble("duration")
                val start = track.optDouble("startOffset", 0.0)
                val absolute = read.optDouble("currentTime", 0.0)
                if (!duration.isFinite() || duration <= 0 || !start.isFinite() || !absolute.isFinite()) return
                val position = (absolute - start).coerceIn(0.0, duration)
                if (position <= 0 && !read.optBoolean("isFinished")) return
                top.cylunex.shadowmedia.database.ContentProgressEntity(asset.id, locatorType = "time",
                    locatorJson = JSONObject().put("trackId", asset.id).put("positionMs", (position * 1000).toLong()).put("durationMs", (duration * 1000).toLong()).toString(),
                    progression = position / duration, completed = read.optBoolean("isFinished") || absolute >= start + duration,
                    updatedAt = System.currentTimeMillis())
            }
            else -> return
        }
        library.dao.seedProgress(record)
    }
    suspend fun resolve(asset: LibraryAssetEntity): PlaybackCandidate {
        val c = connection(asset.providerId)
        return when (c.kind) {
            CatalogKind.JELLYFIN, CatalogKind.OPENSUBSONIC -> musicProvider(c).resolve(UnifiedPlaybackRequest(MediaKey(providerId(c), asset.itemId))).first()
            CatalogKind.OPDS -> {
                val (catalog, id) = OpdsCatalog.decodeLocator(asset.itemId)
                val entry = browse(c, catalog).entries.firstOrNull { it.id == id && it.format == asset.format } ?: error("目录条目已变更，请重新加入")
                candidate(c, requireNotNull(entry.acquisition))
            }
            CatalogKind.KOMGA -> candidate(c, api(c, "api", "v1", "books", asset.itemId, "file").toString())
            CatalogKind.AUDIOBOOKSHELF -> {
                val book = asset.itemId.substringBefore("::"); val ino = asset.itemId.substringAfter("::")
                val media = absItem(c, book).getJSONObject("media")
                require(media.optJSONArray("audioFiles").objects().any { it.optString("ino") == ino && !it.optBoolean("exclude") }) { "音频文件已替换，请刷新本书目录" }
                candidate(c, api(c, "api", "items", book, "file", ino).toString())
            }
        }
    }
    suspend fun pages(asset: LibraryAssetEntity): List<String> {
        val c = connection(asset.providerId); require(c.kind == CatalogKind.KOMGA)
        val data = JSONArray(request(c, api(c, "api", "v1", "books", asset.itemId, "pages")).toString(Charsets.UTF_8))
        require(data.length() in 1..30000)
        return (0 until data.length()).map { data.getJSONObject(it).optInt("number", it + 1).toString() }
    }
    suspend fun page(asset: LibraryAssetEntity, number: String, target: File) {
        val c = connection(asset.providerId); require(c.kind == CatalogKind.KOMGA)
        require(number.toInt() > 0)
        ResourceDownloader(client).download(candidate(c, api(c, "api", "v1", "books", asset.itemId, "pages", number).toString()), target, SafeArchives.MAX_ENTRY_BYTES)
    }

    suspend fun flush() = syncLock.withLock {
        for (c in store.load().filter { it.kind != CatalogKind.OPDS }) {
            val pending = library.dao.pending(providerId(c))
            // Normalize legacy per-track targets before retrying; ABS only stores one book position.
            val targets = if (c.kind == CatalogKind.AUDIOBOOKSHELF) pending.mapNotNull { operation ->
                val payload = runCatching { JSONObject(operation.payload) }.getOrNull()
                library.dao.asset(payload?.optString("assetId", operation.target) ?: operation.target)?.let {
                    operation.id to "abs-book:${ProgressPolicy.absBookId(it.itemId)}"
                }
            }.toMap() else emptyMap()
            val latest = ProgressPolicy.latestOperations(pending, targets)
            for (operation in pending) {
                if (operation.id !in latest) { library.dao.acknowledge(operation.id); continue }
                if (operation.nextAttemptAt > System.currentTimeMillis()) continue
                try {
                    val progress = JSONObject(operation.payload)
                    val asset = library.dao.asset(progress.optString("assetId", operation.target))
                    if (asset == null) { library.dao.acknowledge(operation.id); continue }
                    when (c.kind) {
                        CatalogKind.KOMGA -> request(c, api(c, "api", "v1", "books", asset.itemId, "read-progress"), JSONObject().put("page", progress.optInt("pageIndex") + 1).put("completed", progress.optBoolean("completed")))
                        CatalogKind.AUDIOBOOKSHELF -> {
                            val id = asset.itemId.substringBefore("::"); val ino = asset.itemId.substringAfter("::")
                            val media = absItem(c, id).getJSONObject("media")
                            val track = media.optJSONArray("tracks").objects().firstOrNull { it.optString("ino") == ino } ?: error("远端轨道已更换，保留本地进度")
                            val duration = media.optDouble("duration")
                            val seconds = ProgressPolicy.absPosition(track.optDouble("startOffset", 0.0), track.optDouble("duration"), duration, progress.optLong("positionMs"))
                            request(c, api(c, "api", "me", "progress", id), JSONObject().put("currentTime", seconds).put("duration", duration).put("progress", (seconds / duration).coerceIn(0.0, 1.0))
                                .put("isFinished", progress.optBoolean("completed") && seconds >= duration - 0.5))
                        }
                        else -> Unit
                    }
                    library.dao.acknowledge(operation.id)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { library.dao.retry(operation.id, System.currentTimeMillis() + 30_000L * (operation.attempts + 1).coerceAtMost(10)) }
            }
        }
    }
}
