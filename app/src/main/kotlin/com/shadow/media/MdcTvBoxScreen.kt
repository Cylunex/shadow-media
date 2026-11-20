@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.shadow.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shadow.media.model.UnifiedMediaPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

@Composable
internal fun VideoWarehouseScreen(state: MainUiState, viewModel: MainViewModel, onBack: () -> Unit) {
    var providers by remember { mutableStateOf(emptyList<MdcTvBoxProvider>()) }
    var loading by remember { mutableStateOf(true) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf("") }
    var discoveryAttempt by remember { mutableStateOf(0) }
    var discoveryError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel, discoveryAttempt) {
        loading = true
        discoveryError = null
        val checked = mutableSetOf<com.shadow.media.library.CatalogConnection>()
        val available = mutableListOf<MdcTvBoxProvider>()
        val failures = mutableListOf<String>()
        suspend fun checkConnections(connections: List<com.shadow.media.library.CatalogConnection>) {
            for (connection in connections.filter(checked::add)) {
                val candidate = MdcTvBoxProvider(viewModel.mdcProvider(connection))
                try {
                    if (candidate.supported()) {
                        available += candidate
                        providers = available.toList()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failures += connection.name }
            }
        }
        try {
            providers = emptyList()
            checkConnections(viewModel.knownMdcConnections().connections)
            val discovery = viewModel.discoverMdcConnections()
            val active = discovery.connections.map { it.id }.toSet()
            available.removeAll { it.connection.id !in active }
            providers = available.toList()
            failures += discovery.failedAccounts
            checkConnections(discovery.connections)
            discoveryError = failures.distinct().takeIf { it.isNotEmpty() }?.joinToString(prefix = "未能连接：")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { discoveryError = e.message ?: "服务发现失败" }
        finally { loading = false }
    }
    val discoveryStatus: @Composable () -> Unit = {
        discoveryError?.let { TextButton(onClick = { discoveryAttempt++ }) { Text("$it · 重试") } }
    }
    val provider = providers.firstOrNull { it.descriptor.id == selected } ?: providers.firstOrNull()
    when {
        loading && provider == null && !importing -> {
            BackHandler(onBack = onBack)
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                TopAppBar(
                    title = { Text("影视仓") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                    actions = { TextButton(onClick = { importing = true }) { Text("导入片源") } },
                )
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        provider == null || importing -> ExternalSourcesScreen(
            state, viewModel,
            onBack = if (provider == null) onBack else { { importing = false } },
            discoveryStatus = discoveryStatus,
            onBrowseMdc = provider?.let { { importing = false } },
        )
        else -> androidx.compose.runtime.key(provider.descriptor.id) {
            MdcTvBoxScreen(provider, viewModel, onBack, onImport = { importing = true }) {
                discoveryStatus()
                if (providers.size > 1) ChoiceMenu(
                    provider.descriptor.name,
                    providers.map { MdcTvBoxChoice(it.descriptor.id, it.descriptor.name) },
                ) { selected = it }
            }
        }
    }
}

@Composable
internal fun MdcTvBoxScreen(
    provider: MdcTvBoxProvider,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onImport: (() -> Unit)? = null,
    connectionSelector: @Composable () -> Unit = {},
) {
    val loader = rememberMdcImageLoader(provider.mdc)
    val scroll = rememberLazyGridState()
    var sites by remember { mutableStateOf(emptyList<MdcTvBoxChoice>()) }
    var groups by remember { mutableStateOf(emptyList<MdcTvBoxGroup>()) }
    var warehouse by rememberSaveable(provider.descriptor.id) { mutableStateOf("") }
    var everywhere by rememberSaveable(provider.descriptor.id) { mutableStateOf(false) }
    var categories by remember { mutableStateOf(emptyList<MdcTvBoxChoice>()) }
    var site by rememberSaveable(provider.descriptor.id) { mutableStateOf("") }
    var category by rememberSaveable(provider.descriptor.id) { mutableStateOf("") }
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    val pages = remember(provider, site, category, query, warehouse, everywhere, reload) { TvBoxPages() }
    var request by remember(pages) { mutableStateOf<String?>("1") }
    var pageError by remember(pages) { mutableStateOf<String?>(null) }
    var sitesLoading by remember { mutableStateOf(true) }
    val loading = sitesLoading || (site.isNotEmpty() && request != null && pageError == null)
    val currentGroup = groups.firstOrNull { it.id == warehouse }
    val visibleSites = currentGroup?.sites?.mapNotNull { id -> sites.firstOrNull { it.id == id } } ?: sites
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(provider, reload) {
        sitesLoading = true
        error = null
        try {
            val result = provider.sites()
            ensureActive()
            sites = result
            groups = provider.groups()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { sitesLoading = false }
    }

    LaunchedEffect(visibleSites, sitesLoading) {
        if (!sitesLoading && visibleSites.none { it.id == site }) {
            site = visibleSites.firstOrNull()?.id.orEmpty()
            category = ""
        }
    }

    LaunchedEffect(provider, site, reload) {
        categories = emptyList()
        if (site.isEmpty()) return@LaunchedEffect
        try {
            val result = provider.categories(site)
            ensureActive()
            categories = result
            if (category.isNotEmpty() && result.none { it.id == category }) category = ""
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
    }

    LaunchedEffect(pages) { scroll.scrollToItem(0) }
    LaunchedEffect(pages, request) {
        val token = request ?: return@LaunchedEffect
        if (site.isEmpty()) return@LaunchedEffect
        pageError = null
        pages.lastRequested = token
        try {
            val result = if (everywhere && query.isNotBlank()) provider.searchGroup(query, token, warehouse)
            else provider.items(site, category, token, query)
            ensureActive()
            pages.record(token, result)
            request = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { pageError = e.message ?: "影片加载失败"; request = null }
    }
    val next = pages.next
    LaunchedEffect(pages, next, request, pageError) {
        if (next == null || request != null || pageError != null || !pages.allowAutoLoad) return@LaunchedEffect
        snapshotFlow { scroll.layoutInfo.visibleItemsInfo.any { it.key == "warehouse-footer" } }.first { it }
        request = next
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text("影视仓") },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            actions = {
                TextButton(onClick = { reload++ }) { Text("刷新") }
                if (onImport != null) TextButton(onClick = onImport) { Text("导入片源") }
            },
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyVerticalGrid(
            columns = GridCells.Adaptive(136.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            state = scroll, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Column { connectionSelector() } }
            error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (sites.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Column {
                if (groups.isNotEmpty()) ChoiceMenu(currentGroup?.name ?: "全部仓库",
                    listOf(MdcTvBoxChoice("", "全部仓库")) + groups.map { MdcTvBoxChoice(it.id, it.name) }) {
                    warehouse = it; category = ""
                }
                Row {
                    ChoiceMenu(visibleSites.firstOrNull { it.id == site }?.name ?: "站点", visibleSites) {
                        site = it; category = ""; categories = emptyList()
                    }
                    ChoiceMenu(categories.firstOrNull { it.id == category }?.name ?: "全部分类",
                        listOf(MdcTvBoxChoice("", "全部分类")) + categories) { category = it }
                }
                OutlinedTextField(input, { input = it.take(200) }, label = { Text("片名") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    Button(onClick = { everywhere = false; query = input.trim() }) { Text("搜索本站") }
                    TextButton(onClick = { everywhere = true; query = input.trim() }) { Text("搜索当前仓库") }
                }
            } }
            if (!loading && error == null && sites.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("还没有站点。在 MDC 网页导入后刷新。") }
            if (!loading && sites.isNotEmpty() && visibleSites.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("这个仓库暂无可用站点。") }
            pages.failed.forEach { (token, sites) -> item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(enabled = request == null, onClick = { request = token }) {
                    Text("第 $token 页未响应站点：${sites.joinToString("、")} · 重试")
                }
            } }
            if (!loading && pageError == null && pages.isEmptyResult) item(span = { GridItemSpan(maxLineSpan) }) { Text("没有找到影片") }
            items(pages.items, key = { it.key.stableId }) { item ->
                androidx.compose.material3.Card(onClick = { viewModel.openUnifiedItem(item) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (loader != null) MdcArtwork(item.posterUrl, item.title, loader, Modifier.fillMaxWidth().aspectRatio(.68f))
                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        listOfNotNull(item.year?.toString(), item.subtitle).joinToString(" · ")
                            .takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    }
                }
            }
            if (site.isNotEmpty()) item(key = "warehouse-footer", span = { GridItemSpan(maxLineSpan) }) {
                val failure = pageError
                when {
                    failure != null -> TextButton(onClick = { request = pages.lastRequested }) { Text("$failure · 重试") }
                    pages.lastPage != null -> TextButton(enabled = next != null && request == null, onClick = { request = next }) {
                        Text(if (next == null) "已到第 ${pages.lastPage} 页 · 全部加载完成" else "已到第 ${pages.lastPage} 页 · 加载更多")
                    }
                }
            }
        }
    }
}

internal class TvBoxPages {
    private var results by mutableStateOf<Map<String, UnifiedMediaPage>>(emptyMap())
    var lastRequested = "1"
    val lastPage get() = results.keys.lastOrNull()
    val next get() = results.values.lastOrNull()?.nextPageToken?.takeUnless(results::containsKey)
    val items by androidx.compose.runtime.derivedStateOf { results.values.flatMap { it.items }.distinctBy { it.key } }
    val failed by androidx.compose.runtime.derivedStateOf { results.filterValues { it.failedSources.isNotEmpty() }.mapValues { it.value.failedSources } }
    val isEmptyResult get() = results.isNotEmpty() && items.isEmpty() && failed.isEmpty()
    var allowAutoLoad by mutableStateOf(true)
        private set

    fun record(token: String, page: UnifiedMediaPage) {
        val old = results[token]
        val rows = if (old != null && page.failedSources.isNotEmpty()) (page.items + old.items).distinctBy { it.key } else page.items
        val previousKeys = results.filterKeys { it != token }.values.flatMap { it.items }.mapTo(hashSetOf()) { it.key }
        results = results + (token to page.copy(items = rows))
        if (token == lastPage) allowAutoLoad = page.failedSources.isEmpty() && rows.any { it.key !in previousKeys }
    }
}

@Composable
internal fun ChoiceMenu(label: String, choices: List<MdcTvBoxChoice>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) { Text(label) }
    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
        choices.forEach { choice ->
            DropdownMenuItem(text = { Text(choice.name) }, onClick = { expanded = false; select(choice.id) })
        }
    }
}
