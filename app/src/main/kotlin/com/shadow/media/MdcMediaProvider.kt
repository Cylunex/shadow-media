package com.shadow.media

import com.shadow.media.library.CatalogConnection
import com.shadow.media.model.*
import com.shadow.media.provider.*
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
) : MediaProvider {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
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
                .header("Authorization", "Bearer ${connection.token}")
                .apply { if (body != null) post(body.toString().toRequestBody("application/json".toMediaType()))
                    else if (post) post(ByteArray(0).toRequestBody()) }.build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) {
                    when (response.code) {
                        401 -> "MDC 访问令牌无效"
                        409 -> runCatching { JSONObject(body).optString("detail") }.getOrDefault("尚未关联 Emby 条目")
                        else -> "MDC 请求失败（${response.code}）"
                    }
                }
                JSONObject(body)
            }
        }

    suspend fun checkConnection() { check(api("capabilities").getInt("api_version") == 1) { "MDC 接口版本不兼容" } }

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
        client.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${connection.token}").build()).execute().use {
            check(it.isSuccessful) { "演员目录读取失败（${it.code}）" }
            val rows = org.json.JSONArray(it.body?.string().orEmpty())
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                MdcPerson(row.getString("id"), row.getString("name"),
                    row.getJSONArray("aliases").let { aliases ->
                        (0 until aliases.length()).map { value -> aliases.getString(value) }
                    }, row.getJSONArray("work_ids").length())
            }
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
        return MediaDetail(item(api("works/${key.itemId}")))
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
            client.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${connection.token}").build()).execute().use {
                check(it.isSuccessful) { "无法读取 Emby 候选（${it.code}）" }
                org.json.JSONArray(it.body?.string().orEmpty())
            }
        }
        return (0 until result.length()).map { result.getJSONObject(it).let { row -> row.getString("id") to row.getString("title") } }
    }
    suspend fun bind(key: MediaKey, itemId: String) = withContext(Dispatchers.IO) {
        val url = connection.base().newBuilder().addPathSegments("api/media/v1/works/${key.itemId}/binding").build()
        val body = JSONObject().put("item_id", itemId).toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${connection.token}").put(body).build()).execute().use {
            check(it.isSuccessful) { "无法保存 Emby 关联（${it.code}）" }
        }
    }
    override suspend fun resolve(request: UnifiedPlaybackRequest): List<PlaybackCandidate> =
        error("请使用已连接的 Emby 账号播放此作品")
}

internal data class MdcPerson(val id: String, val name: String, val aliases: List<String>, val workCount: Int)
