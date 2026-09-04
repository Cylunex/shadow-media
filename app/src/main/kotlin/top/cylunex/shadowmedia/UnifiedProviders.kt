package top.cylunex.shadowmedia

import java.util.concurrent.ConcurrentHashMap
import top.cylunex.shadowmedia.model.BrowseRequest
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.ExternalMediaEntry
import top.cylunex.shadowmedia.model.ExternalSourceSummary
import top.cylunex.shadowmedia.model.MediaDetail
import top.cylunex.shadowmedia.model.MediaFilter
import top.cylunex.shadowmedia.model.MediaItem
import top.cylunex.shadowmedia.model.MediaKey
import top.cylunex.shadowmedia.model.MediaSort
import top.cylunex.shadowmedia.model.PlayMethod
import top.cylunex.shadowmedia.model.PlaybackCandidate
import top.cylunex.shadowmedia.model.ProviderCapability
import top.cylunex.shadowmedia.model.ProviderDescriptor
import top.cylunex.shadowmedia.model.ProviderKind
import top.cylunex.shadowmedia.model.UnifiedMediaItem
import top.cylunex.shadowmedia.model.UnifiedMediaPage
import top.cylunex.shadowmedia.model.UnifiedPlaybackRequest
import top.cylunex.shadowmedia.model.toLiveChannels
import top.cylunex.shadowmedia.network.EmbyRepository
import top.cylunex.shadowmedia.provider.MediaProvider
import top.cylunex.shadowmedia.provider.ProviderBrowseRequest
import top.cylunex.shadowmedia.provider.ProviderSearchRequest
import top.cylunex.shadowmedia.provider.ProviderSection

internal class EmbyMediaProvider(
    val session: EmbySession,
    private val repository: EmbyRepository,
) : MediaProvider {
    override val descriptor = ProviderDescriptor(
        id = "emby:${session.serverId}:${session.userId}",
        name = "Emby · ${session.userName}",
        kind = ProviderKind.EMBY,
        capabilities = setOf(
            ProviderCapability.HOME,
            ProviderCapability.BROWSE,
            ProviderCapability.SEARCH,
            ProviderCapability.DETAIL,
            ProviderCapability.PLAYBACK,
            ProviderCapability.FAVORITE_SYNC,
            ProviderCapability.PROGRESS_SYNC,
            ProviderCapability.SUBTITLES,
        ),
    )
    private val cache = ConcurrentHashMap<String, MediaItem>()

    override suspend fun home(): List<ProviderSection> {
        val libraries = repository.libraries(session)
        return repository.home(session, libraries.map { it.id }).map { section ->
            section.items.forEach { cache[it.id] = it }
            ProviderSection(section.id, section.title, section.items.map(::toUnified))
        }
    }

    override suspend fun browse(request: ProviderBrowseRequest): UnifiedMediaPage {
        val offset = request.pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = repository.browse(
            session,
            BrowseRequest(
                parentId = request.parentKey?.itemId.orEmpty(),
                includeItemTypes = request.type?.let(::setOf)
                    ?: setOf("Movie", "Series", "Episode", "Video", "Audio", "MusicAlbum"),
                sort = runCatching { MediaSort.valueOf(request.sort.orEmpty()) }.getOrDefault(MediaSort.DATE_ADDED),
                startIndex = offset,
                limit = request.pageSize.coerceIn(1, 200),
            ),
        )
        page.items.forEach { cache[it.id] = it }
        return UnifiedMediaPage(
            items = page.items.map(::toUnified),
            nextPageToken = if (page.hasMore) (offset + page.items.size).toString() else null,
            totalCount = page.totalRecordCount,
        )
    }

    override suspend fun search(request: ProviderSearchRequest): UnifiedMediaPage {
        val offset = request.pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = repository.browse(
            session,
            BrowseRequest(
                parentId = "",
                includeItemTypes = request.type?.let(::setOf)
                    ?: setOf("Movie", "Series", "Episode", "Video", "Audio", "MusicAlbum"),
                searchTerm = request.query,
                sort = MediaSort.NAME,
                filter = MediaFilter.ALL,
                startIndex = offset,
                limit = request.pageSize.coerceIn(1, 200),
            ),
        )
        page.items.forEach { cache[it.id] = it }
        return UnifiedMediaPage(
            page.items.map(::toUnified),
            nextPageToken = if (page.hasMore) (offset + page.items.size).toString() else null,
            totalCount = page.totalRecordCount,
        )
    }

    override suspend fun detail(key: MediaKey): MediaDetail {
        require(key.providerId == descriptor.id) { "媒体不属于这个 Emby Provider" }
        val item = cache[key.itemId] ?: repository.item(session, key.itemId).also { cache[it.id] = it }
        val children = if (item.type.equals("MusicAlbum", true)) {
            repository.browse(session, BrowseRequest(parentId = item.id, includeItemTypes = setOf("Audio"), limit = 200))
                .items.also { values -> values.forEach { cache[it.id] = it } }
        } else if (item.type.equals("Series", true) || item.type.equals("BoxSet", true)) {
            repository.children(session, item.id).also { values -> values.forEach { cache[it.id] = it } }
        } else emptyList()
        return MediaDetail(item = toUnified(item), children = children.map(::toUnified))
    }

    override suspend fun resolve(request: UnifiedPlaybackRequest): List<PlaybackCandidate> =
        repository.playbackPlan(session, request.key.itemId).candidates

    fun mediaItem(key: MediaKey): MediaItem? = cache[key.itemId]

    private fun toUnified(item: MediaItem) = UnifiedMediaItem(
        key = MediaKey(descriptor.id, item.id),
        title = item.name,
        type = item.type,
        subtitle = item.seriesName,
        overview = item.overview,
        year = item.productionYear,
        rating = item.communityRating,
        durationMs = item.runTimeTicks?.div(10_000),
        progressMs = item.playbackPositionTicks / 10_000,
        played = item.played,
        favorite = item.favorite,
        externalIds = item.externalIds,
    )
}

internal class LiveMediaProvider(
    source: ExternalSourceSummary,
    entries: List<ExternalMediaEntry>,
) : MediaProvider {
    override val descriptor = ProviderDescriptor(
        id = "live:${source.id}",
        name = source.name,
        kind = ProviderKind.LIVE_PLAYLIST,
        capabilities = setOf(
            ProviderCapability.BROWSE,
            ProviderCapability.SEARCH,
            ProviderCapability.DETAIL,
            ProviderCapability.PLAYBACK,
            ProviderCapability.LIVE,
            ProviderCapability.EPG,
            ProviderCapability.CATCH_UP,
        ),
    )
    private val channels = entries.toLiveChannels()
    private val streams = channels.flatMap { channel ->
        channel.streams.mapIndexed { index, entry -> "${channel.id}::$index" to entry }
    }.toMap()

    override suspend fun home(): List<ProviderSection> = listOf(
        ProviderSection("${descriptor.id}:all", descriptor.name, channels.map(::toItem))
    )

    override suspend fun browse(request: ProviderBrowseRequest) = UnifiedMediaPage(channels.map(::toItem))

    override suspend fun search(request: ProviderSearchRequest) = UnifiedMediaPage(
        channels.filter {
            it.title.contains(request.query, true) || it.group.orEmpty().contains(request.query, true)
        }.map(::toItem)
    )

    override suspend fun detail(key: MediaKey): MediaDetail {
        val channel = channels.firstOrNull { it.id == key.itemId }
        if (channel == null) {
            val entry = streams[key.itemId] ?: throw IllegalArgumentException("频道不存在")
            return MediaDetail(streamItem(key.itemId, entry, 0))
        }
        return MediaDetail(
            item = toItem(channel),
            children = channel.streams.mapIndexed { index, entry ->
                streamItem("${channel.id}::$index", entry, index)
            },
        )
    }

    override suspend fun resolve(request: UnifiedPlaybackRequest): List<PlaybackCandidate> {
        val direct = streams[request.key.itemId]
        val entry = direct ?: channels.firstOrNull { it.id == request.key.itemId }?.streams?.firstOrNull()
            ?: throw IllegalArgumentException("频道不存在")
        return listOf(PlaybackCandidate(entry.url, PlayMethod.DIRECT_PLAY, entry.requestHeaders))
    }

    private fun toItem(channel: top.cylunex.shadowmedia.model.LiveChannel) = UnifiedMediaItem(
        key = MediaKey(descriptor.id, channel.id),
        title = channel.title,
        type = "LiveChannel",
        subtitle = channel.group,
        posterUrl = channel.logoUrl,
    )

    private fun streamItem(id: String, entry: ExternalMediaEntry, index: Int) = UnifiedMediaItem(
        key = MediaKey(descriptor.id, id),
        title = "${entry.title} · 线路 ${index + 1}",
        type = "LiveStream",
        subtitle = entry.group,
        posterUrl = entry.logoUrl,
    )
}
