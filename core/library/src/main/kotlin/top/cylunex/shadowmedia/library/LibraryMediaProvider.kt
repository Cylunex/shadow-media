package top.cylunex.shadowmedia.library

import kotlinx.coroutines.flow.first
import top.cylunex.shadowmedia.model.*
import top.cylunex.shadowmedia.provider.*
import top.cylunex.shadowmedia.database.LibraryAssetEntity

/** Searches only the user's imported shelf, never scans storage or resolves media URLs. */
class LibraryMediaProvider(private val library: LibraryRepository) : MediaProvider {
    override val descriptor = ProviderDescriptor("library", "我的书库", ProviderKind.LOCAL_LIBRARY,
        setOf(ProviderCapability.HOME, ProviderCapability.BROWSE, ProviderCapability.SEARCH, ProviderCapability.DETAIL))
    private fun LibraryAssetEntity.item() = UnifiedMediaItem(MediaKey(descriptor.id, id), title,
        type = kind, subtitle = author, posterUrl = coverPath.takeIf { it.isNotBlank() }, favorite = favorite)
    override suspend fun home() = listOf(ProviderSection("shelf", "最近加入", library.dao.searchPage("", "%", 0, 20).map { it.item() }))
    override suspend fun browse(request: ProviderBrowseRequest) = page("", request.type, request.pageToken, request.pageSize)
    override suspend fun search(request: ProviderSearchRequest) = page(request.query, request.type, request.pageToken, request.pageSize)
    private suspend fun page(query: String, type: String?, token: String?, size: Int): UnifiedMediaPage {
        val kind = type?.let { contentKind(it).name }.orEmpty()
        val pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val offset = token?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val total = library.dao.searchCount(kind, pattern)
        val items = library.dao.searchPage(kind, pattern, offset, size.coerceIn(1, 200)).map { it.item() }
        return UnifiedMediaPage(items, nextPageToken = (offset + items.size).takeIf { it < total && items.isNotEmpty() }?.toString(), totalCount = total)
    }
    override suspend fun detail(key: MediaKey): MediaDetail {
        require(key.providerId == descriptor.id)
        return MediaDetail(requireNotNull(library.dao.asset(key.itemId)) { "图书已移除" }.item())
    }
    override suspend fun resolve(request: UnifiedPlaybackRequest): List<PlaybackCandidate> = error("书库由专用阅读与听书体验打开")
}
