package top.cylunex.shadowmedia.network

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import top.cylunex.shadowmedia.model.ExternalCatalogSite
import top.cylunex.shadowmedia.model.MediaDetail
import top.cylunex.shadowmedia.model.MediaKey
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

class TvBoxHttpMediaProvider(
    private val site: ExternalCatalogSite,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MediaProvider {
    override val descriptor = ProviderDescriptor(
        id = "tvbox:${site.id}",
        name = site.name,
        kind = ProviderKind.DECLARATIVE_HTTP,
        capabilities = setOf(
            ProviderCapability.HOME,
            ProviderCapability.BROWSE,
            ProviderCapability.SEARCH,
            ProviderCapability.DETAIL,
            ProviderCapability.PLAYBACK,
        ),
    )

    private val itemCache = ConcurrentHashMap<String, VodRecord>()
    private val candidateCache = ConcurrentHashMap<String, List<PlaybackCandidate>>()

    override suspend fun home(): List<ProviderSection> {
        val page = fetchPage(endpoint("ac" to "detail", "pg" to "1"))
        return page.items.takeIf(List<*>::isNotEmpty)?.let {
            listOf(ProviderSection("${descriptor.id}:latest", "${site.name} · 最近更新", it))
        }.orEmpty()
    }

    override suspend fun browse(request: ProviderBrowseRequest): UnifiedMediaPage = fetchPage(
        endpoint(
            "ac" to "detail",
            "pg" to (request.pageToken?.toIntOrNull() ?: 1).coerceAtLeast(1).toString(),
            "t" to request.type.orEmpty(),
        )
    )

    override suspend fun search(request: ProviderSearchRequest): UnifiedMediaPage = fetchPage(
        endpoint(
            "ac" to "detail",
            "wd" to request.query.trim(),
            "pg" to (request.pageToken?.toIntOrNull() ?: 1).coerceAtLeast(1).toString(),
        )
    )

    override suspend fun detail(key: MediaKey): MediaDetail {
        require(key.providerId == descriptor.id) { "媒体不属于这个 Provider" }
        if (candidateCache.containsKey(key.itemId)) {
            val child = itemCache.values.asSequence()
                .flatMap { record -> record.episodes().asSequence() }
                .firstOrNull { it.key == key }
                ?: UnifiedMediaItem(key, key.itemId, "Episode")
            return MediaDetail(child)
        }
        val record = fetchRecords(endpoint("ac" to "detail", "ids" to key.itemId)).firstOrNull()
            ?: itemCache[key.itemId]
            ?: throw IOException("站点没有返回详情")
        itemCache[record.id] = record
        val children = record.episodes()
        return MediaDetail(
            item = record.toItem(),
            children = children,
            genres = record.genre.split(',', '/', ' ').filter(String::isNotBlank).distinct(),
            people = listOf(record.director, record.actor).flatMap { value ->
                value.split(',', '/', ' ').filter(String::isNotBlank)
            }.distinct(),
        )
    }

    override suspend fun resolve(request: UnifiedPlaybackRequest): List<PlaybackCandidate> {
        require(request.key.providerId == descriptor.id) { "媒体不属于这个 Provider" }
        candidateCache[request.key.itemId]?.let { return it }
        val detail = detail(request.key)
        val firstChild = detail.children.firstOrNull()
            ?: throw IOException("这个条目没有可播放分集")
        return candidateCache[firstChild.key.itemId]
            ?: throw IOException("站点没有返回可播放地址")
    }

    private suspend fun fetchPage(url: HttpUrl): UnifiedMediaPage {
        val root = fetch(url)
        val records = records(root)
        records.forEach { itemCache[it.id] = it }
        val page = root.string("page")?.toIntOrNull() ?: 1
        val pageCount = root.string("pagecount")?.toIntOrNull()
        return UnifiedMediaPage(
            items = records.map { it.toItem() },
            nextPageToken = if (pageCount != null && page < pageCount) (page + 1).toString() else null,
            totalCount = root.string("total")?.toIntOrNull(),
        )
    }

    private suspend fun fetchRecords(url: HttpUrl): List<VodRecord> = records(fetch(url)).also { records ->
        records.forEach { itemCache[it.id] = it }
    }

    private suspend fun fetch(url: HttpUrl): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain;q=0.8")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("${site.name} 请求失败（HTTP ${response.code}）")
            val finalUrl = response.request.url
            if (finalUrl.host.isLoopbackHost()) throw IOException("站点重定向到了本机地址")
            if (url.scheme == "https" && finalUrl.scheme != "https") throw IOException("已拒绝 HTTPS 降级重定向")
            val body = response.body
            if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("站点响应超过安全上限")
            val text = body.byteStream().use { input ->
                val output = ByteArrayOutputStream(8 * 1024)
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) throw IOException("站点响应超过安全上限")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            if (text.size > MAX_RESPONSE_BYTES) throw IOException("站点响应超过安全上限")
            val decoded = text.decodeToString().removePrefix("\uFEFF").trim()
            json.parseToJsonElement(decoded) as? JsonObject ?: throw IOException("站点返回格式无效")
        }
    }

    private fun records(root: JsonObject): List<VodRecord> = (root["list"] as? JsonArray)
        .orEmpty()
        .mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("vod_id", "id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = item.string("vod_name", "name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            VodRecord(
                id = id,
                title = title,
                type = item.string("type_name", "vod_class").orEmpty(),
                remark = item.string("vod_remarks", "remarks"),
                poster = item.string("vod_pic", "pic"),
                overview = item.string("vod_content", "content")?.replace(Regex("<[^>]+>"), "")?.trim(),
                year = item.string("vod_year", "year")?.toIntOrNull(),
                rating = item.string("vod_score", "score")?.toDoubleOrNull(),
                actor = item.string("vod_actor", "actor").orEmpty(),
                director = item.string("vod_director", "director").orEmpty(),
                genre = item.string("vod_class", "type_name").orEmpty(),
                playFrom = item.string("vod_play_from").orEmpty(),
                playUrl = item.string("vod_play_url").orEmpty(),
            )
        }
        .take(MAX_PAGE_ITEMS)

    private fun VodRecord.toItem() = UnifiedMediaItem(
        key = MediaKey(descriptor.id, id),
        title = title,
        type = if (playUrl.contains('#')) "Series" else "Movie",
        subtitle = remark ?: type.takeIf(String::isNotBlank),
        overview = overview,
        posterUrl = poster?.validatedAssetUrl(),
        year = year,
        rating = rating,
    )

    private fun VodRecord.episodes(): List<UnifiedMediaItem> {
        val sourceNames = playFrom.split("\$\$\$")
        return playUrl.split("\$\$\$").flatMapIndexed { sourceIndex, sourcePayload ->
            sourcePayload.split('#').mapIndexedNotNull { episodeIndex, raw ->
                val separator = raw.lastIndexOf('$')
                if (separator <= 0 || separator >= raw.lastIndex) return@mapIndexedNotNull null
                val title = raw.substring(0, separator).trim().ifEmpty { "第 ${episodeIndex + 1} 集" }
                val candidate = raw.substring(separator + 1).trim().toCandidate() ?: return@mapIndexedNotNull null
                val childId = "$id::$sourceIndex::$episodeIndex"
                candidateCache[childId] = listOf(candidate)
                UnifiedMediaItem(
                    key = MediaKey(descriptor.id, childId),
                    title = title,
                    type = "Episode",
                    subtitle = sourceNames.getOrNull(sourceIndex)?.takeIf(String::isNotBlank) ?: site.name,
                    posterUrl = poster?.validatedAssetUrl(),
                )
            }
        }.distinctBy { it.key.itemId }
    }

    private fun String.toCandidate(): PlaybackCandidate? {
        val parts = split('|', limit = 2)
        val rawUrl = parts.first().trim()
        val url = site.apiUrl.toHttpUrl().resolve(rawUrl) ?: rawUrl.toHttpUrlOrNull() ?: return null
        if (url.host.isLoopbackHost()) return null
        if (url.scheme != "https" && !(url.scheme == "http" && site.allowInsecureHttp)) return null
        val headers = parts.getOrNull(1).orEmpty().split('&').mapNotNull { header ->
            val name = header.substringBefore('=').trim().lowercase()
            val rawValue = header.substringAfter('=', "").trim()
            val canonical = when (name) {
                "user-agent", "http-user-agent" -> "User-Agent"
                "referer", "referrer" -> "Referer"
                "origin" -> "Origin"
                else -> null
            }
            canonical?.let {
                it to runCatching { URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name()) }
                    .getOrDefault(rawValue)
            }
        }.toMap()
        return PlaybackCandidate(url.toString(), PlayMethod.DIRECT_PLAY, headers)
    }

    private fun String.validatedAssetUrl(): String? {
        val url = site.apiUrl.toHttpUrl().resolve(this) ?: toHttpUrlOrNull() ?: return null
        if (url.host.isLoopbackHost()) return null
        if (url.scheme != "https" && !(url.scheme == "http" && site.allowInsecureHttp)) return null
        return url.toString()
    }

    private fun endpoint(vararg query: Pair<String, String>): HttpUrl = site.apiUrl.toHttpUrl().newBuilder().apply {
        query.forEach { (name, value) ->
            removeAllQueryParameters(name)
            if (value.isNotEmpty()) addQueryParameter(name, value)
        }
    }.build()

    private fun JsonObject.string(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        val primitive = this[name] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
        primitive.contentOrNull ?: primitive.intOrNull?.toString() ?: primitive.doubleOrNull?.toString()
    }

    private fun String.isLoopbackHost() = equals("localhost", true) || this == "127.0.0.1" || this == "::1"

    private data class VodRecord(
        val id: String,
        val title: String,
        val type: String,
        val remark: String?,
        val poster: String?,
        val overview: String?,
        val year: Int?,
        val rating: Double?,
        val actor: String,
        val director: String,
        val genre: String,
        val playFrom: String,
        val playUrl: String,
    )

    private companion object {
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val MAX_PAGE_ITEMS = 120
    }
}
