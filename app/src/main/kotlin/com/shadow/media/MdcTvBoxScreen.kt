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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
    LaunchedEffect(viewModel) {
        try {
            providers = viewModel.discoverMdcConnections().map {
                MdcTvBoxProvider(viewModel.mdcProvider(it))
            }.filter { provider ->
                try { provider.supported() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { providers = emptyList() }
        finally { loading = false }
    }
    val provider = providers.firstOrNull { it.descriptor.id == selected } ?: providers.firstOrNull()
    when {
        loading && !importing -> {
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
            onBrowseMdc = provider?.let { { importing = false } },
        )
        else -> androidx.compose.runtime.key(provider.descriptor.id) {
            MdcTvBoxScreen(provider, viewModel, onBack, onImport = { importing = true }) {
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
    val scroll = rememberLazyListState()
    var sites by remember { mutableStateOf(emptyList<MdcTvBoxChoice>()) }
    var groups by remember { mutableStateOf(emptyList<MdcTvBoxGroup>()) }
    var warehouse by rememberSaveable(provider.descriptor.id) { mutableStateOf("") }
    var everywhere by rememberSaveable(provider.descriptor.id) { mutableStateOf(false) }
    var categories by remember { mutableStateOf(emptyList<MdcTvBoxChoice>()) }
    var site by rememberSaveable(provider.descriptor.id) { mutableStateOf("") }
    var category by rememberSaveable(provider.descriptor.id) { mutableStateOf("") }
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableStateOf("1") }
    var reload by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf<UnifiedMediaPage?>(null) }
    var sitesLoading by remember { mutableStateOf(true) }
    var itemsLoading by remember { mutableStateOf(false) }
    val loading = sitesLoading || itemsLoading
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
            page = "1"
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

    LaunchedEffect(provider, site, category, query, page, reload, warehouse, everywhere) {
        if (site.isEmpty()) return@LaunchedEffect
        itemsLoading = true
        loaded = null
        error = null
        try {
            val result = if (everywhere && query.isNotBlank()) provider.searchGroup(query, page, warehouse)
            else provider.items(site, category, page, query)
            ensureActive()
            loaded = result
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { itemsLoading = false }
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
        LazyColumn(
            state = scroll, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { connectionSelector() }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (sites.isNotEmpty()) item {
                if (groups.isNotEmpty()) ChoiceMenu(currentGroup?.name ?: "全部仓库",
                    listOf(MdcTvBoxChoice("", "全部仓库")) + groups.map { MdcTvBoxChoice(it.id, it.name) }) {
                    warehouse = it; category = ""; page = "1"
                }
                Row {
                    ChoiceMenu(visibleSites.firstOrNull { it.id == site }?.name ?: "站点", visibleSites) {
                        site = it; category = ""; categories = emptyList(); page = "1"
                    }
                    ChoiceMenu(categories.firstOrNull { it.id == category }?.name ?: "全部分类",
                        listOf(MdcTvBoxChoice("", "全部分类")) + categories) { category = it; page = "1" }
                }
                OutlinedTextField(input, { input = it.take(200) }, label = { Text("片名") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    Button(onClick = { everywhere = false; query = input.trim(); page = "1" }) { Text("搜索本站") }
                    TextButton(onClick = { everywhere = true; query = input.trim(); page = "1" }) { Text("搜索当前仓库") }
                }
            }
            if (!loading && error == null && sites.isEmpty()) item { Text("还没有站点。在 MDC 网页导入后刷新。") }
            if (!loading && sites.isNotEmpty() && visibleSites.isEmpty()) item { Text("这个仓库暂无可用站点。") }
            if (loaded?.items?.isEmpty() == true) item { Text("没有找到影片") }
            items(loaded?.items.orEmpty().distinctBy { it.key }, key = { it.key.itemId }) { item ->
                TextButton(onClick = { viewModel.openUnifiedItem(item) }, modifier = Modifier.fillMaxWidth()) {
                    Text(item.title)
                }
            }
            if (loaded != null) item {
                Row {
                    TextButton(enabled = page != "1", onClick = { page = (page.toInt() - 1).toString() }) { Text("上一页") }
                    TextButton(enabled = loaded?.nextPageToken != null, onClick = {
                        loaded?.nextPageToken?.let { page = it }
                    }) { Text("下一页") }
                }
            }
        }
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
