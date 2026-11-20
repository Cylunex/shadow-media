package com.shadow.media

import com.shadow.media.model.MediaDetail
import com.shadow.media.model.MediaKey
import com.shadow.media.model.PlayMethod
import com.shadow.media.model.PlaybackCandidate
import com.shadow.media.model.ProviderCapability
import com.shadow.media.model.ProviderDescriptor
import com.shadow.media.model.ProviderKind
import com.shadow.media.model.UnifiedMediaItem
import com.shadow.media.model.UnifiedMediaPage
import com.shadow.media.model.UnifiedPlaybackRequest
import com.shadow.media.provider.MediaProvider
import com.shadow.media.provider.ProviderBrowseRequest
import com.shadow.media.provider.ProviderSearchRequest
import com.shadow.media.provider.ProviderSection
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class ResolvedEpisode(val key: MediaKey, val candidate: PlaybackCandidate, val failed: Set<String>)
internal data class MdcTvBoxChoice(val id: String, val name: String)
internal data class MdcTvBoxGroup(val id: String, val name: String, val sites: List<String>)

internal class MdcTvBoxProvider(val mdc: MdcMediaProvider) : MediaProvider {
    override val descriptor = ProviderDescriptor(
        id = "mdc-tvbox:${mdc.connection.id}",
        name = "${mdc.connection.name} · TVBox",
        kind = ProviderKind.DECLARATIVE_HTTP,
        capabilities = setOf(ProviderCapability.BROWSE, ProviderCapability.SEARCH,
            ProviderCapability.DETAIL, ProviderCapability.PLAYBACK),
    )
    val connection get() = mdc.connection
    val includeInSearch get() = mdc.connection.includeInSearch

    private var features: Set<String>? = null

    private suspend fun features(): Set<String> {
        features?.let { return it }
        val values = mdc.api("capabilities").getJSONArray("features")
        return (0 until values.length()).map { values.optString(it) }.toSet().also { features = it }
    }

    internal suspend fun supported(): Boolean = "tvbox_v1" in features()

    suspend fun groups(): List<MdcTvBoxGroup> {
        if ("tvbox_groups" !in features()) return emptyList()
        val choices = mdc.api("tvbox/groups").getJSONArray("items")
        return (0 until choices.length()).map { index ->
            val row = choices.getJSONObject(index)
            val members = row.getJSONArray("site_ids")
            MdcTvBoxGroup(row.getString("id"), row.getString("name"),
                (0 until members.length()).map(members::getString))
        }
    }

    suspend fun sites(): List<MdcTvBoxChoice> = choices(mdc.api("tvbox/sites"))

    suspend fun categories(site: String): List<MdcTvBoxChoice> = choices(mdc.api("tvbox/sites/$site/categories"))

    suspend fun items(site: String, category: String, page: String, query: String = ""): UnifiedMediaPage =
        parsePage(mdc.api("tvbox/sites/$site/items", mapOf("category" to category, "page" to page, "q" to query)
            .filterValues(String::isNotBlank)))

    override suspend fun home(): List<ProviderSection> = emptyList()

    override suspend fun browse(request: ProviderBrowseRequest): UnifiedMediaPage {
        val site = sites().firstOrNull() ?: return UnifiedMediaPage(emptyList())
        return items(site.id, "", request.pageToken ?: "1")
    }

    override suspend fun search(request: ProviderSearchRequest): UnifiedMediaPage {
        if (!supported()) return UnifiedMediaPage(emptyList())
        return searchGroup(request.query, request.pageToken ?: "1", "")
    }

    suspend fun searchGroup(query: String, page: String, group: String): UnifiedMediaPage =
        parsePage(mdc.api("tvbox/search", mapOf("q" to query, "page" to page, "group_id" to group)
            .filterValues(String::isNotBlank)))

    suspend fun searchSites(sites: List<MdcTvBoxChoice>, query: String, page: String,
        onPartial: (UnifiedMediaPage) -> Unit): UnifiedMediaPage = coroutineScope {
        val limit = Semaphore(4)
        val completed = Channel<Pair<MdcTvBoxChoice, UnifiedMediaPage?>>(Channel.UNLIMITED)
        sites.forEach { site -> launch {
            val result = try { limit.withPermit { withTimeoutOrNull(12000) { items(site.id, "", page, query) } } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            completed.send(site to result)
        } }
        val hits = linkedMapOf<MediaKey, UnifiedMediaItem>()
        val failed = mutableListOf<String>()
        var next: Int? = null
        fun snapshot() = UnifiedMediaPage(hits.values.toList(), next?.toString(), failedSources = failed.toList())
        repeat(sites.size) {
            val (site, result) = completed.receive()
            if (result == null) failed += site.name else {
                result.items.forEach { hits[it.key] = it }
                result.nextPageToken?.toIntOrNull()?.let { next = minOf(next ?: it, it) }
            }
            onPartial(snapshot())
        }
        snapshot()
    }

    override suspend fun detail(key: MediaKey): MediaDetail {
        val target = target(key)
        val body = mdc.api(target.path)
        val parent = card(body)
        val episodes = body.getJSONArray("episodes")
        val children = (0 until episodes.length()).map { index ->
            val episode = episodes.getJSONObject(index)
            UnifiedMediaItem(
                MediaKey(descriptor.id, "${parent.key.itemId}:${episode.getString("id")}"),
                "${parent.title} · ${episode.getString("title")}",
                "Episode",
                subtitle = episode.optString("line"),
            )
        }
        if (target.episode != null) return MediaDetail(
            requireNotNull(children.firstOrNull { it.key == key }) { "分集或线路已变更" },
        )
        return MediaDetail(parent, children = children)
    }

    suspend fun alternateEpisodes(key: MediaKey): List<MediaKey> {
        val parent = MediaKey(descriptor.id, key.itemId.substringBeforeLast(':'))
        val children = detail(parent).children
        val current = children.firstOrNull { it.key == key } ?: return emptyList()
        val lines = children.filter { it.title == current.title && !it.subtitle.isNullOrBlank() }.groupBy { it.subtitle }
        if (lines[current.subtitle]?.size != 1) return emptyList()
        return lines.values.mapNotNull { it.singleOrNull()?.key }.filter { it != key }
    }

    suspend fun resolveEpisode(request: UnifiedPlaybackRequest, failedItems: Set<String>, switchLines: Boolean,
        retryCurrent: Boolean = false): ResolvedEpisode {
        val failed = failedItems.toMutableSet()
        var lastError: Exception? = null
        suspend fun tryLine(key: MediaKey): ResolvedEpisode? = try {
            val candidate = resolve(request.copy(key = key)).first()
            ResolvedEpisode(key, candidate, failed.toSet())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failed += key.itemId; lastError = e; null }

        if (request.key.itemId !in failed) tryLine(request.key)?.let { return it }
        if (switchLines) {
            val alternatives = try { alternateEpisodes(request.key) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { lastError = e; emptyList() }
            alternatives.filter { it.itemId !in failed }.forEach { tryLine(it)?.let { line -> return line } }
        }
        if (retryCurrent) tryLine(request.key)?.let { return it }
        throw lastError ?: IllegalStateException("本集可用线路已尝试完毕")
    }

    override suspend fun resolve(request: UnifiedPlaybackRequest): List<PlaybackCandidate> {
        val target = target(request.key)
        val episode = requireNotNull(target.episode) { "请先选择分集" }
        val selection = JSONObject().put("episode_id", episode)
        if ("tvbox_stream" in features()) {
            val stream = mdc.api(target.path + "/watch", body = selection)
            return listOf(mdc.tvboxStream(stream.getString("url"), stream.getString("mime_type")))
        }
        val body = mdc.api(target.path + "/resolve", body = selection)
        val url = body.getString("url").toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty()) { "播放地址格式无效" }
        val values = body.getJSONObject("headers")
        val headers = listOf("Referer", "Origin", "User-Agent").filter(values::has).associateWith(values::getString)
        return listOf(PlaybackCandidate(
            url.toString(), PlayMethod.DIRECT_PLAY, headers, mimeType = body.getString("mime_type"),
        ))
    }

    private fun target(key: MediaKey): Target {
        require(key.providerId == descriptor.id)
        val segments = key.itemId.split(':')
        require(segments.size in 2..3 && segments.all { SAFE_ID.matches(it) })
        return Target("tvbox/sites/${segments[0]}/items/${segments[1]}", segments.getOrNull(2))
    }

    private fun choices(body: JSONObject): List<MdcTvBoxChoice> {
        val items = body.getJSONArray("items")
        return (0 until items.length()).map {
            val row = items.getJSONObject(it)
            MdcTvBoxChoice(row.getString("id"), row.getString("name"))
        }
    }

    private fun parsePage(body: JSONObject): UnifiedMediaPage {
        val rows = body.getJSONArray("items")
        val failures = body.optJSONArray("failures")?.let { names ->
            (0 until names.length()).map(names::getString).distinct()
        }.orEmpty()
        return UnifiedMediaPage(
            failedSources = failures,
            items = (0 until rows.length()).map { card(rows.getJSONObject(it)) },
            nextPageToken = body.optInt("next_page").takeIf { it > 0 }?.toString(),
        )
    }

    private fun card(body: JSONObject): UnifiedMediaItem = UnifiedMediaItem(
        MediaKey(descriptor.id, "${body.getString("site_id")}:${body.getString("id")}"),
        body.getString("title"), "Series", overview = body.optString("overview"),
        year = body.optString("year").toIntOrNull(),
        subtitle = body.optString("remarks").takeIf { it.isNotBlank() },
        posterUrl = if (body.optBoolean("image")) connection.base().newBuilder()
            .addPathSegments("api/media/v1/tvbox/sites").addPathSegment(body.getString("site_id"))
            .addPathSegment("items").addPathSegment(body.getString("id")).addPathSegment("image").build().toString() else null,
    )

    private data class Target(val path: String, val episode: String?)

    private companion object {
        val SAFE_ID = Regex("[a-zA-Z0-9_-]+")
    }
}
