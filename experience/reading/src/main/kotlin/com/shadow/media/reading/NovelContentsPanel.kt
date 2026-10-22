package com.shadow.media.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shadow.media.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Shared by book details and the native reader. Browsing never opens or writes reading progress. */
@Composable
fun NovelContentsPanel(
    repository: NovelRepository, connection: CatalogConnection?, workId: String, versionId: String,
    enabled: Boolean, onOpen: (JSONObject) -> Unit, modifier: Modifier = Modifier,
    currentId: String? = null, currentOrdinal: Int? = null,
    downloadedChapters: suspend () -> List<JSONObject> = { emptyList() },
) {
    key(connection?.id, workId, versionId) {
        var term by rememberSaveable { mutableStateOf("") }
        var descending by rememberSaveable { mutableStateOf(false) }
        var offset by rememberSaveable { mutableIntStateOf((currentOrdinal ?: 0).coerceAtLeast(0) / 100 * 100) }
        val query = NovelContentsQuery(versionId, term, descending, offset)
        var page by remember(query) { mutableStateOf<JSONObject?>(null) }
        var downloaded by remember { mutableStateOf<Set<String>>(emptySet()) }
        var loading by remember(query) { mutableStateOf(true) }
        var error by remember(query) { mutableStateOf<String?>(null) }
        var retry by remember { mutableIntStateOf(0) }
        LaunchedEffect(query, retry) {
            loading = true; error = null
            try {
                val cached = withContext(Dispatchers.IO) { downloadedChapters() }
                downloaded = cached.map { it.getString("id") }.toSet()
                try {
                    requireNotNull(connection) { "连接已移除" }
                    page = repository.get(connection, listOf("works", workId, "contents"), query.parameters()).also {
                        require(it.optString("version_id") == versionId) { "目录版本已变化，请返回作品重新选择" }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (cached.isEmpty()) throw e
                    page = novelOfflineContents(cached, query)
                    error = if (e is NovelApiException && e.status == 401) "登录已失效，当前仅显示本机已下载目录" else "暂时无法获取完整目录，当前仅显示本机已下载目录"
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "目录加载失败，请重试" }
            finally { loading = false }
        }
        Column(modifier) {
            NovelContentsBody(query, page, loading, error, downloaded, currentId, currentOrdinal, enabled,
                onQuery = { term = it.q; descending = it.descending; offset = it.offset }, onRetry = { retry++ }, onOpen = { id ->
                    val chapter = page?.novelObjects("items")?.firstOrNull { it.optString("id") == id }
                        ?: page?.optJSONObject("latest")?.takeIf { it.optString("id") == id }
                    chapter?.let(onOpen)
                })
        }
    }
}

@Composable
internal fun ColumnScope.NovelContentsBody(
    query: NovelContentsQuery, page: JSONObject?, loading: Boolean, error: String?, downloaded: Set<String>,
    currentId: String?, currentOrdinal: Int?, enabled: Boolean,
    onQuery: (NovelContentsQuery) -> Unit, onRetry: () -> Unit, onOpen: (String) -> Unit,
) {
    var draft by rememberSaveable(query.q) { mutableStateOf(query.q) }
    var jump by rememberSaveable { mutableStateOf(false) }
    var number by rememberSaveable { mutableStateOf("") }
    var more by remember { mutableStateOf(false) }
    var locateRequest by remember { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val list = rememberLazyListState()
    val entries = page?.novelObjects("items").orEmpty()
    val total = page?.optInt("total") ?: 0
    val start = page?.optInt("offset") ?: query.offset
    val pages = ((total - 1).coerceAtLeast(0) / query.limit) + 1
    val target = number.toIntOrNull()?.takeIf { it in 1..pages }
    val next = page?.let(::novelContentsNext)
    val localOnly = page?.optBoolean("_downloaded_only") == true
    fun search() { focus.clearFocus(); onQuery(query.search(draft)) }
    fun go() { target?.let { focus.clearFocus(); onQuery(query.copy(offset = (it - 1) * query.limit)); jump = false } }
    LaunchedEffect(page, locateRequest) {
        val active = if (query.q.isBlank() && !query.descending) entries.indexOfFirst { it.optString("id") == currentId } else -1
        list.scrollToItem(active.coerceAtLeast(0))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (localOnly) "已下载 · $total 节" else if (query.q.isNotBlank()) "找到 $total 节" else "共 $total 节",
            Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (currentOrdinal != null) TextButton(onClick = { draft = ""; focus.clearFocus(); onQuery(query.locate(currentOrdinal)); locateRequest++ }) { Text("当前章") }
        TextButton(onClick = { focus.clearFocus(); onQuery(query.reverse()) }) { Text(if (query.descending) "倒序" else "正序") }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "更多目录操作") }
            DropdownMenu(more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text("跳转页码") }, onClick = { more = false; jump = true })
                DropdownMenuItem(text = { Text("目录首页") }, onClick = { more = false; onQuery(query.copy(offset = 0)) })
                DropdownMenuItem(text = { Text("目录末页") }, enabled = total > 0, onClick = { more = false; onQuery(query.copy(offset = (pages - 1) * query.limit)) })
                DropdownMenuItem(text = { Text("刷新目录") }, enabled = !loading, onClick = { more = false; onRetry() })
            }
        }
    }
    if (jump) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(number, { number = it.filter(Char::isDigit).take(7) }, Modifier.weight(1f), singleLine = true,
            label = { Text("页码（1–$pages）") }, isError = number.isNotEmpty() && target == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }),
            trailingIcon = { IconButton(onClick = { jump = false; focus.clearFocus() }) { Icon(Icons.Rounded.Close, "收起页码跳转") } })
        FilledTonalButton(enabled = !loading && target != null, onClick = { go() }) { Text("跳转") }
    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(draft, { draft = it.take(200) }, Modifier.weight(1f), singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text(if (localOnly) "搜索已下载章节标题" else "搜索全书章节标题") },
            trailingIcon = { if (draft.isNotEmpty() || query.q.isNotEmpty()) IconButton(onClick = { draft = ""; onQuery(query.search("")) }) { Icon(Icons.Rounded.Close, "清除章节搜索") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
        TextButton(onClick = { search() }) { Text("查找") }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    if (error != null) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(enabled = !loading, onClick = onRetry) { Text("重试") }
    } else if (page?.has("_cached_at") == true) Text("缓存目录 · 联网后可刷新", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!loading && page != null && entries.isEmpty()) item {
            ReaderEmptyState(Icons.Rounded.SearchOff, "没有匹配的章节", if (query.q.isBlank()) "此版本暂无目录，可返回选择其他版本。" else "试试更短的章节名，或清除关键词。")
        }
        items(entries, key = { it.getString("id") }) { entry ->
            val id = entry.getString("id")
            val active = id == currentId
            val readable = entry.optBoolean("readable")
            Surface(onClick = { focus.clearFocus(); onOpen(id) }, enabled = enabled && !loading && readable,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                Row(Modifier.heightIn(min = 60.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${entry.optInt("ordinal") + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.optString("title"), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        if (active || !readable) Text(if (active) "正在阅读" else "卷标题", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (id in downloaded) Icon(Icons.Rounded.OfflinePin, "已下载", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(enabled = !loading && start > 0, onClick = { onQuery(query.copy(offset = (start - query.limit).coerceAtLeast(0))) }) { Icon(Icons.Rounded.ChevronLeft, "上一页目录") }
        TextButton(enabled = page != null, onClick = { jump = !jump }) { Text("${if (total == 0) 0 else start / query.limit + 1} / ${if (total == 0) 0 else pages} 页") }
        page?.optJSONObject("latest")?.let { latest ->
            TextButton(enabled = enabled && !loading && latest.optBoolean("readable"), onClick = { focus.clearFocus(); onOpen(latest.getString("id")) }) { Text("读末章") }
        }
        IconButton(enabled = !loading && next != null, onClick = { next?.let { onQuery(query.copy(offset = it)) } }) { Icon(Icons.Rounded.ChevronRight, "下一页目录") }
    }
}
