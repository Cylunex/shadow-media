package top.cylunex.shadowmedia.library

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import top.cylunex.shadowmedia.model.*
import top.cylunex.shadowmedia.provider.*
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Native account-scoped protocols. Catalog results contain stable ids, never signed stream URLs. */
class NativeMusicProvider(val connection: CatalogConnection, client: OkHttpClient = OkHttpClient()) : MediaProvider {
    private val c = connection
    private val authLock = Mutex()
    private var jellyfinAuth: Pair<String, String>? = null
    private val http = client.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(top.cylunex.shadowmedia.network.OfflineModeInterceptor())
        .addNetworkInterceptor { chain ->
            // API redirects have no reason to send passwords or query authentication elsewhere.
            if (!sameOrigin(c.base(), chain.request().url)) throw IOException("来源接口发生跨域重定向")
            chain.proceed(chain.request())
        }.build()
    override val descriptor = ProviderDescriptor("catalog:${c.kind.name}:${c.id}", c.name,
        if (c.kind == CatalogKind.JELLYFIN) ProviderKind.JELLYFIN else ProviderKind.OPENSUBSONIC,
        setOf(ProviderCapability.HOME, ProviderCapability.BROWSE, ProviderCapability.SEARCH, ProviderCapability.DETAIL, ProviderCapability.PLAYBACK, ProviderCapability.DOWNLOAD))
    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    private fun endpoint(vararg path: String): HttpUrl = c.base().newBuilder().apply {
        if (!c.base().encodedPath.endsWith('/')) addPathSegment("")
        path.forEach(::addPathSegment)
    }.build()
    private fun HttpUrl.params(values: List<Pair<String, String>>) = newBuilder().apply { values.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
    private suspend fun json(url: HttpUrl, headers: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        require(sameOrigin(c.base(), url))
        val call = http.newCall(Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> header(key, value) }
            if (body != null) post(body.toString().toRequestBody("application/json".toMediaType()))
        }.build())
        val cancellation = launch { try { awaitCancellation() } finally { call.cancel() } }
        try { call.execute().use { response ->
            if (!response.isSuccessful) throw NativeHttpFailure(response.code)
            JSONObject(requireNotNull(response.body).byteStream().use { SafeArchives.readBounded(it, 8L * 1024 * 1024) }.toString(Charsets.UTF_8))
        } } finally { cancellation.cancel() }
    }
    private fun authorization(token: String = ""): Map<String, String> {
        require(token.none { it == '"' || it == '\\' || it.code < 32 }) { "来源令牌格式无效" }
        return mapOf("Authorization" to "MediaBrowser Client=\"Shadow Media\", Device=\"Android\", DeviceId=\"${c.id}\", Version=\"1.1\"" + if (token.isNotBlank()) ", Token=\"$token\"" else "")
    }
    private suspend fun auth(): Pair<String, String> = authLock.withLock {
        jellyfinAuth?.let { return@withLock it }
        val value = if (c.token.isNotBlank()) c.token to json(endpoint("Users", "Me"), authorization(c.token)).getString("Id")
        else {
            require(c.username.isNotBlank()) { "请填写 Jellyfin 用户名和密码，或用户访问令牌" }
            val response = json(endpoint("Users", "AuthenticateByName"), authorization(), JSONObject().put("Username", c.username).put("Pw", c.password))
            response.getString("AccessToken") to response.getJSONObject("User").getString("Id")
        }
        jellyfinAuth = value
        value
    }
    private suspend fun jelly(path: Array<String>, params: List<Pair<String, String>> = emptyList(), body: JSONObject? = null): JSONObject {
        val (token, user) = auth()
        try { return json(endpoint(*path).params(params + ("userId" to user)), authorization(token), body) }
        catch (e: NativeHttpFailure) {
            if (e.status != 401 || c.token.isNotBlank()) throw e
            authLock.withLock { jellyfinAuth = null }
            val fresh = auth()
            return json(endpoint(*path).params(params + ("userId" to fresh.second)), authorization(fresh.first), body)
        }
    }
    internal fun subsonicUrl(method: String, params: List<Pair<String, String>> = emptyList()): HttpUrl {
        val auth = if (c.token.isNotBlank()) listOf("apiKey" to c.token) else {
            require(c.username.isNotBlank() && c.password.isNotEmpty()) { "请填写 OpenSubsonic API Key 或用户名和密码" }
            val salt = UUID.randomUUID().toString().replace("-", "")
            val hash = MessageDigest.getInstance("MD5").digest((c.password + salt).toByteArray()).joinToString("") { "%02x".format(it) }
            listOf("u" to c.username, "s" to salt, "t" to hash)
        }
        return endpoint("rest", "$method.view").params(listOf("v" to "1.16.1", "c" to "ShadowMedia", "f" to "json") + auth + params)
    }
    private suspend fun sub(method: String, vararg params: Pair<String, String>): JSONObject {
        val data = json(subsonicUrl(method, params.toList())).getJSONObject("subsonic-response")
        if (data.optString("status") != "ok") throw IOException("OpenSubsonic 接口失败：${data.optJSONObject("error")?.optInt("code") ?: 0}")
        return data
    }
    override suspend fun home() = listOf(ProviderSection("albums", "专辑", browse(ProviderBrowseRequest(pageSize = 20)).items))
    override suspend fun browse(request: ProviderBrowseRequest): UnifiedMediaPage {
        request.parentKey?.let { require(it.providerId == descriptor.id) }
        val parent = request.parentKey?.itemId.orEmpty()
        val size = request.pageSize.coerceIn(1, 200)
        if (c.kind == CatalogKind.JELLYFIN) {
            val offset = request.pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val type = when { request.type.equals("Audio", true) || parent == "songs" -> "Audio"; parent == "videos" -> "Movie,Series"; parent.isBlank() -> "MusicAlbum"; else -> "Audio,Episode,Season" }
            val params = mutableListOf("includeItemTypes" to type, "recursive" to "true", "startIndex" to "$offset", "limit" to "$size", "sortBy" to "ParentIndexNumber,IndexNumber,SortName", "sortOrder" to "Ascending", "fields" to "Genres,MediaSources")
            if (parent.isNotBlank() && parent !in setOf("songs", "videos")) params += "parentId" to parent
            val data = jelly(arrayOf("Items"), params)
            val items = data.optJSONArray("Items").objects().map(::jellyItem)
            val total = data.optInt("TotalRecordCount", offset + items.size)
            return UnifiedMediaPage(items, (offset + items.size).takeIf { it < total && items.isNotEmpty() }?.toString(), total)
        }
        if (parent == "songs") return subsonicSongs(request.pageToken, size)
        if (parent.startsWith("playlist:")) {
            val songs = sub("getPlaylist", "id" to parent.removePrefix("playlist:")).getJSONObject("playlist").optJSONArray("entry").objects().map { subItem(it) }
            return slice(songs, request.pageToken, size)
        }
        if (parent == "playlists") return slice(sub("getPlaylists").optJSONObject("playlists")?.optJSONArray("playlist").objects().map {
            UnifiedMediaItem(MediaKey(descriptor.id, "playlist:${it.getString("id")}"), it.getString("name"), "Playlist")
        }, request.pageToken, size)
        if (parent.isNotBlank()) return slice(sub("getAlbum", "id" to parent.removePrefix("album:")).getJSONObject("album").optJSONArray("song").objects().map { subItem(it) }, request.pageToken, size)
        val offset = request.pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val albums = sub("getAlbumList2", "type" to "alphabeticalByName", "size" to "$size", "offset" to "$offset").optJSONObject("albumList2")?.optJSONArray("album").objects().map { subItem(it, true) }
        return UnifiedMediaPage(albums, (offset + albums.size).takeIf { albums.size == size }?.toString())
    }
    /** Album lookahead makes the final nonempty page authoritative without an extra empty-page refresh. */
    private suspend fun subsonicSongs(token: String?, size: Int): UnifiedMediaPage {
        val parts = token?.split(':').orEmpty()
        val albumOffset = parts.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val trackOffset = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val albums = sub("getAlbumList2", "type" to "alphabeticalByName", "size" to "2", "offset" to "$albumOffset").optJSONObject("albumList2")?.optJSONArray("album").objects()
        if (albums.isEmpty()) return UnifiedMediaPage(emptyList())
        val tracks = sub("getAlbum", "id" to albums.first().getString("id")).getJSONObject("album").optJSONArray("song").objects().map { subItem(it) }
        val page = tracks.drop(trackOffset).take(size)
        val next = if (trackOffset + page.size < tracks.size) "$albumOffset:${trackOffset + page.size}" else if (albums.size > 1) "${albumOffset + 1}:0" else null
        return UnifiedMediaPage(page, next)
    }
    override suspend fun search(request: ProviderSearchRequest): UnifiedMediaPage {
        val offset = request.pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0; val size = request.pageSize.coerceIn(1, 200)
        if (c.kind == CatalogKind.JELLYFIN) {
            val data = jelly(arrayOf("Items"), listOf("searchTerm" to request.query, "includeItemTypes" to "Audio,MusicAlbum,Movie,Series", "recursive" to "true", "startIndex" to "$offset", "limit" to "$size"))
            val items = data.optJSONArray("Items").objects().map(::jellyItem); val total = data.optInt("TotalRecordCount")
            return UnifiedMediaPage(items, (offset + items.size).takeIf { items.isNotEmpty() && it < total }?.toString(), total)
        }
        val data = sub("search3", "query" to request.query, "songOffset" to "$offset", "songCount" to "$size", "artistCount" to "0", "albumCount" to "0")
        val items = data.optJSONObject("searchResult3")?.optJSONArray("song").objects().map { subItem(it) }
        return UnifiedMediaPage(items, (offset + items.size).takeIf { items.size == size }?.toString())
    }
    override suspend fun detail(key: MediaKey): MediaDetail {
        require(key.providerId == descriptor.id)
        if (c.kind == CatalogKind.JELLYFIN) {
            val user = auth().second
            val item = jellyItem(jelly(arrayOf("Users", user, "Items", key.itemId)))
            return MediaDetail(item, if (contentKind(item.type) in setOf(ContentKind.SERIES, ContentKind.FOLDER)) allChildren(key) else emptyList())
        }
        if (key.itemId.startsWith("playlist:")) return MediaDetail(UnifiedMediaItem(key, "歌单", "Playlist"), allChildren(key))
        if (key.itemId.startsWith("album:")) {
            val album = sub("getAlbum", "id" to key.itemId.removePrefix("album:")).getJSONObject("album")
            return MediaDetail(subItem(album, true), album.optJSONArray("song").objects().map { subItem(it) })
        }
        val song = sub("getSong", "id" to key.itemId).getJSONObject("song")
        return MediaDetail(subItem(song))
    }
    private suspend fun allChildren(key: MediaKey): List<UnifiedMediaItem> {
        val result = mutableListOf<UnifiedMediaItem>(); var token: String? = null; val seen = mutableSetOf<String?>()
        do {
            check(seen.add(token)) { "来源分页重复" }
            val page = browse(ProviderBrowseRequest(key, pageToken = token, pageSize = 200)); result += page.items
            check(result.size <= 10000) { "目录过大，请使用分页浏览" }; token = page.nextPageToken
        } while (token != null)
        return result
    }
    override suspend fun resolve(request: UnifiedPlaybackRequest): List<PlaybackCandidate> {
        require(request.key.providerId == descriptor.id)
        if (c.kind == CatalogKind.OPENSUBSONIC) return listOf(
            PlaybackCandidate(subsonicUrl("stream", listOf("id" to request.key.itemId, "format" to "raw")).toString(), PlayMethod.DIRECT_PLAY, emptyMap(), credentialOrigin = c.base().toString()),
            PlaybackCandidate(subsonicUrl("stream", listOf("id" to request.key.itemId, "format" to "mp3", "maxBitRate" to "192")).toString(), PlayMethod.TRANSCODE, emptyMap(), credentialOrigin = c.base().toString()))
        val (token, user) = auth()
        val metadata = jelly(arrayOf("Users", user, "Items", request.key.itemId))
        val audio = metadata.optString("MediaType").equals("Audio", true) || metadata.optString("Type").equals("Audio", true)
        val path = if (audio) "Audio" else "Videos"
        val profile = JSONObject().put("Name", "Shadow Media Native").put("MaxStreamingBitrate", 120000000)
            .put("DirectPlayProfiles", JSONArray().put(JSONObject().put("Type", "Audio").put("Container", "mp3,m4a,m4b,flac,ogg,opus,wav,aac"))
                .put(JSONObject().put("Type", "Video").put("Container", "mp4,mkv,webm,ts,m2ts").put("VideoCodec", "h264,hevc,vp9,av1").put("AudioCodec", "aac,mp3,flac,opus,vorbis,ac3,eac3")))
            .put("TranscodingProfiles", JSONArray().put(JSONObject().put("Type", "Audio").put("Container", "mp3").put("AudioCodec", "mp3").put("Protocol", "http").put("Context", "Streaming"))
                .put(JSONObject().put("Type", "Video").put("Container", "ts").put("VideoCodec", "h264").put("AudioCodec", "aac").put("Protocol", "hls").put("Context", "Streaming")))
        val info = jelly(arrayOf("Items", request.key.itemId, "PlaybackInfo"), body = JSONObject().put("UserId", user).put("DeviceProfile", profile).put("StartTimeTicks", request.startPositionMs.coerceIn(0, Long.MAX_VALUE / 10000) * 10000))
        val candidates = mutableListOf<PlaybackCandidate>()
        fun add(url: HttpUrl, method: PlayMethod, sourceId: String) {
            if (!url.isHttps && !c.allowHttp) return
            if (url.username.isNotBlank() || url.password.isNotBlank()) return
            candidates += PlaybackCandidate(url.toString(), method, if (sameOrigin(c.base(), url)) authorization(jellyfinAuth?.first ?: token) else emptyMap(), mediaSourceId = sourceId, credentialOrigin = c.base().toString())
        }
        for (source in info.optJSONArray("MediaSources").objects().take(16)) {
            val sourceId = source.optString("Id")
            if (source.optBoolean("SupportsDirectPlay")) add(endpoint(path, request.key.itemId, "stream").params(listOf("static" to "true", "mediaSourceId" to sourceId)), PlayMethod.DIRECT_PLAY, sourceId)
            source.optString("DirectStreamUrl").takeIf { it.isNotBlank() && source.optBoolean("SupportsDirectStream") }?.let { url -> c.base().resolve(url)?.let { add(it, PlayMethod.DIRECT_STREAM, sourceId) } }
            source.optString("TranscodingUrl").takeIf { it.isNotBlank() && source.optBoolean("SupportsTranscoding") }?.let { url -> c.base().resolve(url)?.let { add(it, PlayMethod.TRANSCODE, sourceId) } }
        }
        if (candidates.isEmpty()) add(endpoint(path, request.key.itemId, "stream").params(listOf("static" to "true")), PlayMethod.DIRECT_PLAY, "")
        return candidates.distinctBy { it.url }.take(16)
    }
    suspend fun lyrics(id: String): String? {
        if (c.kind == CatalogKind.JELLYFIN) {
            val data = jelly(arrayOf("Audio", id, "Lyrics"))
            return data.optJSONArray("Lyrics").objects().joinToString("\n") { line ->
                val ticks = line.optLong("Start", -1)
                (if (ticks >= 0) timestamp(ticks / 10000) else "") + line.optString("Text")
            }.takeIf(String::isNotBlank)
        }
        val extensions = sub("getOpenSubsonicExtensions").optJSONArray("openSubsonicExtensions").objects()
        if (extensions.none { it.optString("name") == "songLyrics" }) return null
        val lyrics = sub("getLyricsBySongId", "id" to id).optJSONObject("lyricsList")?.optJSONArray("structuredLyrics").objects().firstOrNull() ?: return null
        return "[offset:${lyrics.optLong("offset")}]\n" + lyrics.optJSONArray("line").objects().joinToString("\n") { line ->
            (if (lyrics.optBoolean("synced") && line.has("start")) timestamp(line.optLong("start").coerceAtLeast(0)) else "") + line.optString("value")
        }
    }
    private fun jellyItem(o: JSONObject): UnifiedMediaItem {
        val id = o.getString("Id"); val type = o.optString("Type")
        val artist = o.optJSONArray("Artists")?.let { array -> (0 until array.length()).joinToString("、") { array.optString(it) } }.orEmpty()
        return UnifiedMediaItem(MediaKey(descriptor.id, id), o.optString("Name"), if (type == "Audio") "MUSIC" else type, subtitle = artist,
            durationMs = o.optLong("RunTimeTicks").takeIf { it > 0 }?.div(10000), music = if (type == "Audio") MusicMetadata(o.optString("Album"), o.optString("AlbumId"), artist, o.optString("AlbumArtist"), o.optInt("ParentIndexNumber"), o.optInt("IndexNumber")) else null)
    }
    private fun subItem(o: JSONObject, album: Boolean = false) = UnifiedMediaItem(MediaKey(descriptor.id, (if (album) "album:" else "") + o.getString("id")), o.optString(if (album) "name" else "title"), if (album) "MusicAlbum" else "MUSIC",
        subtitle = o.optString("artist"), durationMs = o.optLong("duration").takeIf { it > 0 }?.times(1000), music = if (album) null else MusicMetadata(o.optString("album"), o.optString("albumId"), o.optString("artist"), o.optString("albumArtist"), o.optInt("discNumber"), o.optInt("track")))
    private fun slice(items: List<UnifiedMediaItem>, token: String?, size: Int): UnifiedMediaPage {
        val offset = token?.toIntOrNull()?.coerceAtLeast(0) ?: 0; val page = items.drop(offset).take(size)
        return UnifiedMediaPage(page, (offset + page.size).takeIf { it < items.size && page.isNotEmpty() }?.toString(), items.size)
    }
    private fun JSONArray?.objects(): List<JSONObject> = this?.let { (0 until length()).mapNotNull { optJSONObject(it) } }.orEmpty()
    private fun timestamp(ms: Long) = "[%02d:%02d.%03d]".format(java.util.Locale.ROOT, ms / 60000, ms / 1000 % 60, ms % 1000)
}

private class NativeHttpFailure(val status: Int) : IOException("来源请求失败 HTTP $status")
