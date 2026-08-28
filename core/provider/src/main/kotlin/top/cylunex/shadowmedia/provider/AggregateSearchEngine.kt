package top.cylunex.shadowmedia.provider

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import top.cylunex.shadowmedia.model.ProviderCapability
import top.cylunex.shadowmedia.model.UnifiedMediaItem

data class ProviderSearchFailure(
    val providerId: String,
    val providerName: String,
    val message: String,
)

data class AggregateSearchResult(
    val items: List<UnifiedMediaItem>,
    val failures: List<ProviderSearchFailure>,
)

class AggregateSearchEngine {
    suspend fun search(
        providers: List<MediaProvider>,
        request: ProviderSearchRequest,
    ): AggregateSearchResult = supervisorScope {
        val normalizedQuery = request.query.trim()
        require(normalizedQuery.isNotEmpty()) { "搜索内容不能为空" }
        val searchable = providers.filter {
            it.descriptor.enabled && ProviderCapability.SEARCH in it.descriptor.capabilities
        }
        val results = searchable.map { provider ->
            async {
                provider to runCatching { provider.search(request.copy(query = normalizedQuery)) }
            }
        }.map { it.await() }

        val items = results.flatMap { (_, result) -> result.getOrNull()?.items.orEmpty() }
            .distinctBy { it.key.stableId }
            .sortedWith(
                compareByDescending<UnifiedMediaItem> { relevance(it, normalizedQuery) }
                    .thenByDescending { it.rating ?: 0.0 }
                    .thenByDescending { it.year ?: 0 }
                    .thenBy(UnifiedMediaItem::title)
            )
        val failures = results.mapNotNull { (provider, result) ->
            result.exceptionOrNull()?.let { error ->
                ProviderSearchFailure(
                    providerId = provider.descriptor.id,
                    providerName = provider.descriptor.name,
                    message = error.message ?: "搜索失败",
                )
            }
        }
        AggregateSearchResult(items, failures)
    }

    private fun relevance(item: UnifiedMediaItem, query: String): Int {
        val title = item.title.trim()
        val subtitle = item.subtitle.orEmpty()
        return when {
            title.equals(query, true) -> 100
            title.startsWith(query, true) -> 80
            title.contains(query, true) -> 60
            subtitle.contains(query, true) -> 30
            else -> 0
        } + if (!item.overview.isNullOrBlank()) 2 else 0
    }
}
