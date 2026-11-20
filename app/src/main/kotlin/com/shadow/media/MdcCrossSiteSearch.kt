package com.shadow.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shadow.media.model.UnifiedMediaItem
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private data class SiteMatch(val item: UnifiedMediaItem, val site: String, val name: String)

@Composable internal fun MdcCrossSiteSearch(work: UnifiedMediaItem, vm: MainViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember(work.key) { mutableStateOf(work.externalIds["code"] ?: work.title) }
    var submitted by remember(work.key) { mutableStateOf(query) }
    var attempt by remember { mutableIntStateOf(0) }
    val hits = remember(submitted, attempt) { mutableStateListOf<SiteMatch>() }
    val failed = remember(submitted, attempt) { mutableStateListOf<String>() }
    var searching by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf(emptyList<UnifiedMediaItem>()) }
    var resolving by remember { mutableStateOf(false) }
    val health by vm.sourceHealth.collectAsState()
    LaunchedEffect(work.key, submitted, attempt) {
        searching = true; error = null; choices = emptyList()
        val limit = Semaphore(4)
        try {
            val discovery = vm.discoverMdcConnections()
            failed.addAll(discovery.failedAccounts)
            coroutineScope {
                discovery.connections.forEach { c -> launch {
                    val p = MdcTvBoxProvider(vm.mdcProvider(c))
                    try {
                        val sites = limit.withPermit { withTimeoutOrNull(12000) { if (p.supported()) p.sites() else emptyList() } }
                        if (sites == null) { failed += c.name; return@launch }
                        sites.forEach { site -> launch {
                            try {
                                val result = limit.withPermit { withTimeoutOrNull(12000) { p.items(site.id, "", "1", submitted) } }
                                if (result == null) failed += site.name
                                else hits.addAll(result.items.map { SiteMatch(it, site.id, c.name + " · " + site.name) })
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { failed += site.name }
                        } }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed += c.name }
                } }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { searching = false }
    }
    val metrics = health.associateBy { it.sourceKey }
    fun metric(row: SiteMatch) = metrics[row.item.key.providerId + ":site:" + row.site]
    val ordered = hits.distinctBy { it.item.key }.sortedWith(compareBy<SiteMatch>(
        { !it.item.title.equals(submitted, true) && !it.item.title.equals(work.title, true) },
        { metric(it)?.consecutiveFailures ?: 0 }, { metric(it)?.averageFirstFrameMs ?: Long.MAX_VALUE }, { vm.siteResolveMs(it.item.key.providerId, it.site) ?: Long.MAX_VALUE }, { it.item.title }))
    AlertDialog(onDismissRequest = onClose, title = { Text("影视仓跨站找片") }, text = {
        Column {
            OutlinedTextField(query, { query = it.take(200) }, label = { Text("番号或片名") }, singleLine = true)
            Row {
                TextButton(enabled = query.isNotBlank() && !resolving, onClick = { submitted = query.trim(); attempt++ }) { Text("搜索") }
                if (work.title != submitted) TextButton(onClick = { query = work.title; submitted = query; attempt++ }) { Text("按片名搜索") }
            }
            if (searching || resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it) }
            if (failed.isNotEmpty()) Text("未响应：" + failed.distinct().joinToString("、"))
            if (!searching && ordered.isEmpty() && failed.isEmpty()) Text("没有匹配的影片")
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (choices.isNotEmpty()) {
                    item { TextButton(onClick = { choices = emptyList() }) { Text("返回匹配影片") } }
                    items(choices, key = { it.key.stableId }) { episode ->
                        TextButton(onClick = { onClose(); vm.playUnifiedItem(episode) }) { Text(episode.title + " · " + episode.subtitle.orEmpty()) }
                    }
                } else items(ordered, key = { it.item.key.stableId }) { row ->
                    TextButton(enabled = !resolving, onClick = { scope.launch {
                        resolving = true
                        try {
                            val provider = requireNotNull(vm.artworkProvider(row.item.key.providerId)) { "影视仓连接已移除" }
                            val episodes = MdcTvBoxProvider(provider).detail(row.item.key).children
                            if (episodes.size == 1) { onClose(); vm.playUnifiedItem(episodes.single()) }
                            else if (episodes.isNotEmpty()) choices = episodes else error = "该站点未提供分集"
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
                        finally { resolving = false }
                    } }) { Column {
                        Text(row.item.title); Text(row.name, style = MaterialTheme.typography.bodySmall)
                        vm.siteResolveMs(row.item.key.providerId, row.site)?.let { Text("解析 " + it + " ms", style = MaterialTheme.typography.bodySmall) }
                        metric(row)?.let { Text("播放成功 " + it.successes + " 次 · 失败 " + it.failures + " 次", style = MaterialTheme.typography.bodySmall) }
                    } }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("关闭") } })
}
