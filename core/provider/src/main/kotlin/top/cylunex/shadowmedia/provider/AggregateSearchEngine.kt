package top.cylunex.shadowmedia.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    val pendingProviderIds: Set<String> = emptySet(),
    val nextPageTokens: Map<String, String> = emptyMap(),
)

class AggregateSearchEngine(private val timeoutMs: Long = 12_000, private val concurrency: Int = 4) {
    init { require(timeoutMs > 0); require(concurrency > 0) }

    suspend fun search(
        providers: List<MediaProvider>,
        request: ProviderSearchRequest,
    ): AggregateSearchResult = searchSnapshots(providers, request).last()

    fun searchSnapshots(
        providers: List<MediaProvider>,
        request: ProviderSearchRequest,
    ): Flow<AggregateSearchResult> = channelFlow {
        val normalizedQuery = request.query.trim()
        require(normalizedQuery.isNotEmpty()) { "搜索内容不能为空" }
        val searchable = providers.distinctBy { it.descriptor.id }.filter {
            it.descriptor.enabled && ProviderCapability.SEARCH in it.descriptor.capabilities
        }
        val pending = searchable.map { it.descriptor.id }.toMutableSet()
        val pages = linkedMapOf<String, top.cylunex.shadowmedia.model.UnifiedMediaPage>()
        val failures = mutableListOf<ProviderSearchFailure>()
        val mutex = Mutex()
        val semaphore = Semaphore(concurrency)
        suspend fun publish() {
            val items = pages.values.flatMap { it.items }
            .distinctBy { it.key.stableId }
            .sortedWith(
                compareByDescending<UnifiedMediaItem> { relevance(it, normalizedQuery) }
                    .thenByDescending { it.rating ?: 0.0 }
                    .thenByDescending { it.year ?: 0 }
                    .thenBy(UnifiedMediaItem::title)
            )
            send(AggregateSearchResult(items, failures.toList(), pending.toSet(), pages.mapNotNull { (id, page) ->
                page.nextPageToken?.let { id to it }
            }.toMap()))
        }
        publish()
        searchable.forEach { provider ->
            launch {
                val outcome = try {
                    val page = semaphore.withPermit {
                        withTimeoutOrNull(timeoutMs) { provider.search(request.copy(query = normalizedQuery)) }
                    }
                    page to if (page == null) "搜索超时" else null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null to "来源暂不可用（offline），请重试"
                }
                mutex.withLock {
                    val id = provider.descriptor.id
                    outcome.first?.let { pages[id] = it }
                    outcome.second?.let { failures += ProviderSearchFailure(id, provider.descriptor.name, it) }
                    pending.remove(id)
                    publish()
                }
            }
        }
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
