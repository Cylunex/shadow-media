package com.shadow.media

import com.shadow.media.library.CatalogConnection
import com.shadow.media.model.*
import com.shadow.media.provider.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject

/** Dedicated catalog connection. Credentials never travel to a playback host. */
internal class MdcMediaProvider(
    val connection: CatalogConnection,
    client: OkHttpClient,
    private val accessToken: (suspend (String?) -> String)? = null,
) : MediaProvider {
    private val apiClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val base = connection.base()
    private val apiPrefix = base.newBuilder().addPathSegments("api/media/v1/").build().encodedPath

    private fun checkScope(request: Request) {
        if (request.url.scheme != base.scheme || request.url.host != base.host || request.url.port != base.port ||
            !request.url.encodedPath.startsWith(apiPrefix)) throw java.io.IOException("拒绝跨来源 MDC 请求")
    }

    private suspend fun token(rejected: String? = null): String = accessToken?.invoke(rejected) ?: connection.token

    private fun Request.authorized(token: String): Request = newBuilder()
        .header("Authorization", "Bearer $token")
        .header("X-Catalog-Owner", connection.username.ifBlank { connection.id }).build()

    internal val imageClient = apiClient.newBuilder().addInterceptor { chain ->
        val request = chain.request()
        checkScope(request)
        val first = runBlocking { token() }
        val response = chain.proceed(request.authorized(first))
        if (response.code != 401 || accessToken == null) response else {
            response.close()
            chain.proceed(request.authorized(runBlocking { token(first) }))
        }
    }.build()

    private suspend fun <T> execute(request: Request, consume: (okhttp3.Response) -> T): T {
        checkScope(request)
        return withContext(Dispatchers.IO) {
            var access = token()
            for (attempt in 0..1) {
                val call = apiClient.newCall(request.authorized(access))
                val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        if (response.code != 401 || accessToken == null || attempt == 1) {
                            return@withContext consume(response)
                        }
                    }
                } catch (e: java.io.IOException) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    throw e
                } finally { cancellation.cancel() }
                access = token(access)
            }
            error("MDC 请求未完成")
        }
    }

    suspend fun tvboxStream(path: String, mimeType: String): PlaybackCandidate {
        require(TVBOX_STREAM.matches(path)) { "MDC 返回的播放地址无效" }
        val base = connection.base()
        val url = base.newBuilder().addPathSegments(path.removePrefix("/")).build()
        return PlaybackCandidate(url.toString(), PlayMethod.DIRECT_PLAY, mapOf("Authorization" to "Bearer ${token()}"),
            credentialOrigin = base.toString(), mimeType = mimeType)
    }

    override val descriptor = ProviderDescriptor(
        "mdc:${connection.id}", connection.name, ProviderKind.DECLARATIVE_HTTP,
        setOf(ProviderCapability.BROWSE, ProviderCapability.SEARCH, ProviderCapability.PLAYBACK),
    )

    suspend fun api(path: String, query: Map<String, String> = emptyMap(), post: Boolean = false, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val url = connection.base().newBuilder().addPathSegments("api/media/v1/$path").apply {
                query.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
            val request = Request.Builder().url(url)
                .apply { if (body != null) post(body.toString().toRequestBody("application/json".toMediaType()))
                    else if (post) post(ByteArray(0).toRequestBody()) }.build()
            execute(request) { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) {
                    when (response.code) {
                        401 -> if (accessToken == null) "MDC 访问令牌无效" else "统一账号登录已失效，请在 Novel 重新登录"
                        409 -> runCatching { JSONObject(body).optString("detail") }.getOrDefault("尚未关联 Emby 条目")
                        404, 502 -> if (path.startsWith("tvbox/")) {
                            runCatching { JSONObject(body).getString("detail") }.getOrDefault("影视仓请求失败（${response.code}）")
                        } else "MDC 请求失败（${response.code}）"
                        else -> "MDC 请求失败（${response.code}）"
                    }
                }
                JSONObject(body)
            }
        }

    private companion object {
        val TVBOX_STREAM = Regex("/api/media/v1/tvbox/stream/[a-zA-Z0-9_-]{32}/[a-zA-Z0-9_-]{16}")
    }

    suspend fun checkConnection() {
        val capabilities = api("capabilities")
        check(capabilities.getInt("api_version") == 1) { "MDC 接口版本不兼容" }
        val values = capabilities.optJSONArray("features")
        val features = if (values == null) emptySet() else (0 until values.length()).map { values.getString(it) }.toSet()
        check("catalog" in features && "playback_plan" in features) { "请先更新 MDC 服务以使用统一片库" }
    }

    private fun item(row: JSONObject) = UnifiedMediaItem(
        key = MediaKey(descriptor.id, row.getString("id")),
        title = row.getString("title"), type = "Movie",
        subtitle = listOf(row.optString("code").takeUnless { it == "null" }.orEmpty(),
            row.optJSONArray("actors")?.let { values -> (0 until values.length()).joinToString(" / ") { values.getString(it) } }.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" · "),
        posterUrl = row.optString("poster").takeUnless { it.isBlank() || it == "null" }?.let {
            connection.base().newBuilder().addPathSegments(it.removePrefix("/")).build().toString()
        },
        overview = row.optString("overview").takeUnless { it == "null" },
        durationMs = if (row.isNull("duration_ms")) null else row.getLong("duration_ms"),
    )

    suspend fun people(): List<MdcPerson> = withContext(Dispatchers.IO) {
        val url = connection.base().newBuilder().addPathSegments("api/media/v1/catalog/people").build()
        execute(Request.Builder().url(url).build()) {
            check(it.isSuccessful) { "演员目录读取失败（${it.code}）" }
            val rows = org.json.JSONArray(it.body?.string().orEmpty())
            (0 until rows.length()).map { index ->
                personCard(rows.getJSONObject(index))
            }
        }
    }

    internal data class PeopleBatch(val items: List<MdcPerson>, val next: String?)
    private var peoplePaging: Boolean? = null

    suspend fun peoplePage(query: String, cursor: String? = null): PeopleBatch {
        val supported = peoplePaging ?: api("capabilities").optJSONArray("features")?.let { values ->
            (0 until values.length()).any { values.optString(it) == "people_cards_paged" }
        }.also { peoplePaging = it } ?: false
        if (!supported) return PeopleBatch(people().filter { person ->
            (listOf(person.name) + person.aliases).any { it.contains(query, ignoreCase = true) }
        }, null)
        val parameters = mutableMapOf("q" to query, "limit" to "48")
        if (cursor != null) parameters["cursor"] = cursor
        val page = api("catalog/people-cards", parameters)
        val items = page.getJSONArray("items")
        return PeopleBatch((0 until items.length()).map { personCard(items.getJSONObject(it)) },
            page.optString("next_cursor").takeUnless { it.isBlank() || it == "null" })
    }

    private fun personCard(row: JSONObject): MdcPerson {
        val id = row.getString("id")
        val aliases = row.getJSONArray("aliases")
        val url = connection.base().newBuilder().addPathSegments("api/media/v1/catalog/people/$id/image")
            .addQueryParameter("v", row.optInt("revision", 1).toString()).build().toString()
        return MdcPerson(id, row.getString("name"), (0 until aliases.length()).map { aliases.getString(it) },
            row.optInt("work_count", row.optJSONArray("work_ids")?.length() ?: 0), url)
    }

    suspend fun person(id: String): JSONObject = api("catalog/people/$id")

    private suspend fun array(path: String): org.json.JSONArray = withContext(Dispatchers.IO) {
        val url = connection.base().newBuilder().addPathSegments("api/media/v1/catalog/$path").build()
        execute(Request.Builder().url(url).build()) {
            check(it.isSuccessful) { "目录读取失败（${it.code}）" }
            org.json.JSONArray(it.body?.string().orEmpty())
        }
    }
    suspend fun playlists(): List<MdcPlaylist> {
        val rows = array("playlists")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            MdcPlaylist(row.getString("id"), row.getString("name"), row.getJSONArray("work_ids").length())
        }
    }
    suspend fun playlistWorks(id: String): List<Pair<String, String>> {
        val rows = array("playlists/$id/works")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            row.getString("id") to row.getString("title")
        }
    }

    suspend fun catalog(filters: JSONObject): Pair<UnifiedMediaPage, Int> {
        val result = api("catalog/works/query", body = filters)
        val rows = result.getJSONArray("items")
        return UnifiedMediaPage((0 until rows.length()).map { item(rows.getJSONObject(it)) },
            nextPageToken = result.optString("next_cursor").takeUnless { it.isBlank() || it == "null" }) to result.getInt("total")
    }
    suspend fun facets(filters: JSONObject): JSONObject = api("catalog/facets", body = filters)
    suspend fun plan(key: MediaKey, online: Boolean = false): JSONObject =
        api("works/${key.itemId}/plan", query = if (online) mapOf("online" to "true") else emptyMap(), post = true)

    override suspend fun home() = listOf(ProviderSection("recent", "最近入库", browse(ProviderBrowseRequest()).items))
    override suspend fun browse(request: ProviderBrowseRequest) = page("", request.pageToken, request.pageSize)
    override suspend fun search(request: ProviderSearchRequest) = page(request.query, request.pageToken, request.pageSize)
    private suspend fun page(query: String, cursor: String?, size: Int): UnifiedMediaPage {
        val result = api("works", buildMap {
            put("q", query); put("limit", size.coerceIn(1, 100).toString())
            cursor?.let { put("cursor", it) }
        })
        val rows = result.getJSONArray("items")
        return UnifiedMediaPage((0 until rows.length()).map { item(rows.getJSONObject(it)) },
            nextPageToken = result.optString("next_cursor").takeUnless { it.isBlank() || it == "null" })
    }
    override suspend fun detail(key: MediaKey): MediaDetail {
        require(key.providerId == descriptor.id)
        return MediaDetail(item(api("works/${key.itemId.substringBefore(":version:")}")).copy(key = key))
    }
    suspend fun embyTarget(key: MediaKey): Pair<String, String> {
        require(key.providerId == descriptor.id)
        val result = api("works/${key.itemId}/resolve", post = true)
        return result.getString("server_id") to result.getString("item_id")
    }
    suspend fun candidates(key: MediaKey, query: String): List<Pair<String, String>> {
        val result = withContext(Dispatchers.IO) {
            val url = connection.base().newBuilder().addPathSegments("api/media/v1/works/${key.itemId}/emby-candidates")
                .addQueryParameter("q", query).build()
            execute(Request.Builder().url(url).build()) {
                check(it.isSuccessful) { "无法读取 Emby 候选（${it.code}）" }
                org.json.JSONArray(it.body?.string().orEmpty())
            }
        }
        return (0 until result.length()).map { result.getJSONObject(it).let { row -> row.getString("id") to row.getString("title") } }
    }
    suspend fun bind(key: MediaKey, itemId: String) = withContext(Dispatchers.IO) {
        val url = connection.base().newBuilder().addPathSegments("api/media/v1/works/${key.itemId}/binding").build()
        val body = JSONObject().put("item_id", itemId).toString().toRequestBody("application/json".toMediaType())
        execute(Request.Builder().url(url).put(body).build()) {
            check(it.isSuccessful) { "无法保存 Emby 关联（${it.code}）" }
        }
    }
    override suspend fun resolve(request: UnifiedPlaybackRequest): List<PlaybackCandidate> {
        val workId = request.key.itemId.substringBefore(":version:")
        val versionId = request.key.itemId.substringAfter(":version:", "")
        val resolved = plan(MediaKey(descriptor.id, workId), online = true)
        val sources = resolved.optJSONArray("sources") ?: return emptyList()
        return (0 until sources.length()).map { sources.getJSONObject(it) }.filter {
            versionId.isBlank() || it.optString("version_id") == versionId
        }.map { PlaybackCandidate(it.getString("url"), PlayMethod.DIRECT_STREAM, emptyMap()) }
    }

}

internal data class MdcPerson(val id: String, val name: String, val aliases: List<String>, val workCount: Int, val imageUrl: String? = null)


internal fun selectMdcRecoverySource(
    plan: JSONObject, versionId: String, sourceId: String,
): JSONObject? {
    val sources = plan.optJSONArray("sources") ?: return null
    val matching = (0 until sources.length()).map { sources.getJSONObject(it) }
        .filter { it.optString("version_id") == versionId && it.optBoolean("playable_in_app") &&
            !it.isNull("url") && it.optString("url").isNotBlank() }
    return matching.firstOrNull { it.optString("source_id") == sourceId } ?: matching.firstOrNull()
}

internal data class MdcPlaylist(val id: String, val name: String, val workCount: Int)
