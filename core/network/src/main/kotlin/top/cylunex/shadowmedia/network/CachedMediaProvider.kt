package top.cylunex.shadowmedia.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.cylunex.shadowmedia.database.*
import top.cylunex.shadowmedia.model.*
import top.cylunex.shadowmedia.provider.*
import java.security.MessageDigest

/** Metadata-only snapshots for file storage and stable-id HTTP catalogs. Resolve always delegates. */
class CachedMediaProvider(private val remote: MediaProvider, private val dao: LibraryStateDao) : MediaProvider by remote {
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(operation: String, argument: String) = "provider-page:" + MessageDigest.getInstance("SHA-256")
        .digest(scopedContentId(descriptor.id, operation, argument).toByteArray()).joinToString("") { "%02x".format(it) }
    private suspend fun read(key: String) = withOptionalCatalogCache { dao.catalogPage(key)?.let { json.decodeFromString<Snapshot>(it.payload) } }
    private suspend fun fetch(key: String, request: suspend () -> Snapshot): Pair<Snapshot, Boolean> = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
        val cached = read(key)
        if (NetworkPolicy.offlineOnly) return@withLock requireNotNull(cached) { "此目录尚未缓存在本机" } to true
        try {
            val fresh = request()
            if (fresh.rows.isEmpty() && cached?.rows?.isNotEmpty() == true) return@withLock cached to true
            withOptionalCatalogCache { if (fresh.safe()) {
                val payload = json.encodeToString(fresh)
                if (payload.toByteArray().size <= 2 * 1024 * 1024) dao.cacheCatalogPage(CatalogPageEntity(key, payload, System.currentTimeMillis()))
            } }
            fresh to false
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { (cached ?: throw e) to true }
    }
    override suspend fun cachedDetail(key: MediaKey): MediaDetail? {
        require(key.providerId == descriptor.id)
        return read(key("detail", key.itemId))?.detail(descriptor.id, true)
    }
    override suspend fun detail(key: MediaKey): MediaDetail {
        require(key.providerId == descriptor.id)
        var fresh: MediaDetail? = null
        val (value, cached) = fetch(key("detail", key.itemId)) { remote.detail(key).also { fresh = it }.let(Snapshot::from) }
        return if (!cached) requireNotNull(fresh) else value.detail(descriptor.id, true)
    }
    override suspend fun cachedBrowse(request: ProviderBrowseRequest): UnifiedMediaPage? {
        require(request.parentKey?.let { it.providerId == descriptor.id } != false)
        return read(key("browse", request.toString()))?.page(descriptor.id, true)
    }
    override suspend fun browse(request: ProviderBrowseRequest): UnifiedMediaPage {
        require(request.parentKey?.let { it.providerId == descriptor.id } != false)
        var fresh: UnifiedMediaPage? = null
        val (value, cached) = fetch(key("browse", request.toString())) { remote.browse(request).also { fresh = it }.let(Snapshot::from) }
        return if (!cached) requireNotNull(fresh) else value.page(descriptor.id, true)
    }
    override suspend fun search(request: ProviderSearchRequest): UnifiedMediaPage {
        var fresh: UnifiedMediaPage? = null
        val (value, cached) = fetch(key("search", request.toString())) { remote.search(request).also { fresh = it }.let(Snapshot::from) }
        return if (!cached) requireNotNull(fresh) else value.page(descriptor.id, true)
    }
    @Serializable private data class Row(val id: String, val title: String, val type: String, val subtitle: String?, val overview: String?, val year: Int?, val duration: Long?, val progress: Long, val played: Boolean, val favorite: Boolean) {
        fun item(provider: String) = UnifiedMediaItem(MediaKey(provider, id), title, type, subtitle, overview, year = year, durationMs = duration, progressMs = progress, played = played, favorite = favorite)
        companion object { fun from(item: UnifiedMediaItem) = Row(item.key.itemId, item.title, item.type, item.subtitle, item.overview?.take(16000), item.year, item.durationMs, item.progressMs, item.played, item.favorite) }
    }
    @Serializable private data class Snapshot(val rows: List<Row>, val next: String? = null, val total: Int? = null, val item: Row? = null, val genres: List<String> = emptyList(), val people: List<String> = emptyList()) {
        fun safe() = (rows.map { it.id } + listOfNotNull(item?.id, next)).none { it.contains("://") || it.contains('?') || it.contains('#') }
        fun page(provider: String, cached: Boolean) = UnifiedMediaPage(rows.map { it.item(provider) }, next, total, cached)
        fun detail(provider: String, cached: Boolean) = MediaDetail(requireNotNull(item).item(provider), rows.map { it.item(provider) }, genres = genres, people = people, childrenNextPageToken = next, cached = cached)
        companion object {
            fun from(page: UnifiedMediaPage) = Snapshot(page.items.map(Row::from), page.nextPageToken, page.totalCount)
            fun from(detail: MediaDetail) = Snapshot(detail.children.map(Row::from), detail.childrenNextPageToken, item = Row.from(detail.item), genres = detail.genres, people = detail.people)
        }
    }
    companion object { private val locks = Array(64) { Mutex() } }
}
