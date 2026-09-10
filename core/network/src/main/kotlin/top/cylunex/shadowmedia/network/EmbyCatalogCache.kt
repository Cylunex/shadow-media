package top.cylunex.shadowmedia.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.cylunex.shadowmedia.database.CatalogPageEntity
import top.cylunex.shadowmedia.database.LibraryStateDao
import top.cylunex.shadowmedia.model.*
import java.security.MessageDigest

@Serializable internal data class EmbyCatalogSnapshot(
    val items: List<BaseItemDto> = emptyList(), val total: Int = items.size,
    val sections: List<EmbySectionSnapshot> = emptyList(),
)
@Serializable internal data class EmbySectionSnapshot(val id: String, val title: String, val kind: String, val items: List<BaseItemDto>)

/** Whitelist DTOs contain metadata and image tags, never PlaybackInfo, URLs or credentials. */
internal fun MediaItem.snapshotDto() = BaseItemDto(id, name, type, music?.album.orEmpty(), music?.albumId.orEmpty(),
    music?.artist?.let(::listOf).orEmpty(), music?.albumArtist.orEmpty(), seriesName = seriesName,
    parentIndexNumber = music?.disc ?: seasonNumber, indexNumber = music?.track ?: episodeNumber,
    runTimeTicks = runTimeTicks, overview = overview?.take(16000), productionYear = productionYear,
    communityRating = communityRating, seriesId = seriesId, imageTags = imageTag?.let { mapOf("Primary" to it) }.orEmpty(),
    backdropImageTags = listOfNotNull(backdropImageTag), providerIds = externalIds,
    userData = UserDataDto(playbackPositionTicks, played, favorite))

class EmbyCatalogCache(internal val dao: LibraryStateDao) {
    private val locks = Array(64) { Mutex() }
    private val json = Json { ignoreUnknownKeys = true }
    internal fun key(session: EmbySession, operation: String, argument: String = ""): String = "emby-catalog:" +
        MessageDigest.getInstance("SHA-256").digest(scopedContentId(session.providerId, operation, argument).toByteArray()).joinToString("") { "%02x".format(it) }
    internal suspend fun read(key: String): EmbyCatalogSnapshot? = withOptionalCatalogCache { dao.catalogPage(key)?.let { json.decodeFromString<EmbyCatalogSnapshot>(it.payload) } }
    internal suspend fun write(key: String, value: EmbyCatalogSnapshot): Unit = withOptionalCatalogCache {
        val payload = json.encodeToString(value)
        if (payload.toByteArray().size <= 2 * 1024 * 1024) {
            dao.cacheCatalogPage(CatalogPageEntity(key, payload, System.currentTimeMillis()))
        }
    } ?: Unit
    internal suspend fun fetch(key: String, request: suspend () -> EmbyCatalogSnapshot): Pair<EmbyCatalogSnapshot, Boolean> =
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock { fetchLocked(key, request) }
    private suspend fun fetchLocked(key: String, request: suspend () -> EmbyCatalogSnapshot): Pair<EmbyCatalogSnapshot, Boolean> {
        val cached = read(key)
        if (NetworkPolicy.offlineOnly) return requireNotNull(cached) { "本机还没有这个目录的副本" } to true
        try {
            val fresh = request()
            if (fresh.items.isEmpty() && fresh.sections.isEmpty() && cached != null && (cached.items.isNotEmpty() || cached.sections.isNotEmpty())) return cached to true
            write(key, fresh)
            return fresh to false
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { return (cached ?: throw e) to true }
    }
}

/** Direct playback and mutations still use the original canonical network repository. */
class CachedEmbyRepository(private val remote: EmbyRepository, private val cache: EmbyCatalogCache) : EmbyRepository by remote {
    private val userState = EmbyUserState(cache.dao, remote)
    override fun favoriteStates(session: EmbySession) = userState.observe(session)
    override suspend fun setFavorite(session: EmbySession, itemId: String, favorite: Boolean) = userState.setFavorite(session, itemId, favorite)
    override suspend fun flushUserStates(session: EmbySession) = userState.flush(session)
    override suspend fun cachedLibraries(session: EmbySession) = cache.read(cache.key(session, "libraries"))?.items?.map { MediaLibrary(it.id, it.name, it.collectionType) }
    override suspend fun libraries(session: EmbySession) = cache.fetch(cache.key(session, "libraries")) {
        EmbyCatalogSnapshot(remote.libraries(session).map { BaseItemDto(it.id, it.name, collectionType = it.collectionType) })
    }.first.items.map { MediaLibrary(it.id, it.name, it.collectionType) }
    private suspend fun sections(session: EmbySession, snapshot: EmbyCatalogSnapshot): List<MediaSection> {
        val local = cache.dao.favoriteAssets(session.providerId).map { asset ->
            cache.read(cache.key(session, "item", asset.itemId))?.items?.firstOrNull()?.toModel()?.copy(favorite = true)
                ?: MediaItem(asset.itemId, asset.title, asset.kind, null, null, null, null, 0, false, true)
        }
        val sections = snapshot.sections.map {
            MediaSection(it.id, it.title, MediaSectionKind.valueOf(it.kind), userState.overlay(session, it.items.map(BaseItemDto::toModel)))
        }.toMutableList()
        val index = sections.indexOfFirst { it.kind == MediaSectionKind.FAVORITES }
        val favorites = (sections.getOrNull(index)?.items.orEmpty() + local).distinctBy { it.id }.filter { it.favorite }
        if (index >= 0) sections[index] = sections[index].copy(items = favorites)
        else if (favorites.isNotEmpty()) sections += MediaSection("favorites", "我的收藏", MediaSectionKind.FAVORITES, favorites)
        return sections.filter { it.items.isNotEmpty() }
    }
    override suspend fun cachedHome(session: EmbySession, libraryIds: List<String>) = cache.read(cache.key(session, "home", libraryIds.sorted().joinToString("\u0000")))?.let { sections(session, it) }
    override suspend fun home(session: EmbySession, libraryIds: List<String>) = sections(session, cache.fetch(cache.key(session, "home", libraryIds.sorted().joinToString("\u0000"))) {
        EmbyCatalogSnapshot(sections = remote.home(session, libraryIds).map { EmbySectionSnapshot(it.id, it.title, it.kind.name, it.items.map(MediaItem::snapshotDto)) })
    }.first)
    private fun browseKey(session: EmbySession, request: BrowseRequest) = cache.key(session, "browse", request.copy(includeItemTypes = request.includeItemTypes.toSortedSet()).toString())
    override suspend fun cachedBrowse(session: EmbySession, request: BrowseRequest) = cache.read(browseKey(session, request))?.let { MediaPage(userState.overlay(session, it.items.map(BaseItemDto::toModel)), request.startIndex, it.total, cached = true) }
    override suspend fun browse(session: EmbySession, request: BrowseRequest): MediaPage {
        val (snapshot, cached) = cache.fetch(browseKey(session, request)) { remote.browse(session, request).let { EmbyCatalogSnapshot(it.items.map(MediaItem::snapshotDto), it.totalRecordCount) } }
        return MediaPage(userState.overlay(session, snapshot.items.map(BaseItemDto::toModel)), request.startIndex, snapshot.total, cached)
    }
    override suspend fun cachedChildren(session: EmbySession, parentId: String) = cache.read(cache.key(session, "children", parentId))?.items?.map(BaseItemDto::toModel)?.let { userState.overlay(session, it) }
    override suspend fun children(session: EmbySession, parentId: String) = cache.fetch(cache.key(session, "children", parentId)) { EmbyCatalogSnapshot(remote.children(session, parentId).map(MediaItem::snapshotDto)) }.first.items.map(BaseItemDto::toModel).let { userState.overlay(session, it) }
    override suspend fun item(session: EmbySession, itemId: String) = cache.fetch(cache.key(session, "item", itemId)) { EmbyCatalogSnapshot(listOf(remote.item(session, itemId).snapshotDto())) }.first.items.map(BaseItemDto::toModel).let { userState.overlay(session, it) }.single()
    override suspend fun recentVideos(session: EmbySession, libraryId: String) = cache.fetch(cache.key(session, "feed", libraryId)) { EmbyCatalogSnapshot(remote.recentVideos(session, libraryId).take(2000).map(MediaItem::snapshotDto)) }.first.items.map(BaseItemDto::toModel).let { userState.overlay(session, it) }
    override suspend fun cachedRecentVideos(session: EmbySession, libraryId: String) = cache.read(cache.key(session, "feed", libraryId))?.items?.map(BaseItemDto::toModel)?.let { userState.overlay(session, it) }
}
