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

internal data class MdcTvBoxChoice(val id: String, val name: String)
internal data class MdcTvBoxGroup(val id: String, val name: String, val sites: List<String>)

internal class MdcTvBoxProvider(private val mdc: MdcMediaProvider) : MediaProvider {
    override val descriptor = ProviderDescriptor(
        id = "mdc-tvbox:${mdc.connection.id}",
        name = "${mdc.connection.name} · TVBox",
        kind = ProviderKind.DECLARATIVE_HTTP,
        capabilities = setOf(ProviderCapability.BROWSE, ProviderCapability.SEARCH,
            ProviderCapability.DETAIL, ProviderCapability.PLAYBACK),
    )
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
        return UnifiedMediaPage(
            items = (0 until rows.length()).map { card(rows.getJSONObject(it)) },
            nextPageToken = body.optInt("next_page").takeIf { it > 0 }?.toString(),
        )
    }

    private fun card(body: JSONObject): UnifiedMediaItem = UnifiedMediaItem(
        MediaKey(descriptor.id, "${body.getString("site_id")}:${body.getString("id")}"),
        body.getString("title"), "Series", overview = body.optString("overview"),
    )

    private data class Target(val path: String, val episode: String?)

    private companion object {
        val SAFE_ID = Regex("[a-zA-Z0-9_-]+")
    }
}
