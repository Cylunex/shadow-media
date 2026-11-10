@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import com.shadow.media.database.LibraryAssetEntity
import com.shadow.media.database.NovelPublicationEntity
import com.shadow.media.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun ColumnScope.NativeReaderContentsPanel(
    asset: LibraryAssetEntity,
    publication: NovelPublicationEntity?,
    current: NativeTextChapter,
    source: NativeTextSource,
    novel: NovelRepository,
    enabled: Boolean,
    onOpen: (NativeChapterRef) -> Unit,
) {
    if (publication != null) {
        NovelContentsPanel(novel, runCatching { novel.connection(publication.scope) }.getOrNull(), publication.workId, publication.versionId,
            enabled, onOpen = { row ->
                val id = row.getString("id")
                onOpen(NativeChapterRef(id, row.optString("title"), row.getInt("ordinal"), "chapters/$id.xhtml", row.optBoolean("readable")))
            },
            modifier = Modifier.weight(1f).fillMaxWidth(), currentId = current.ref.id, currentOrdinal = current.ref.ordinal,
            downloadedChapters = { novel.cachedContents(publication) }, contentScope = publication.scope)
        return
    }
    var requested by rememberSaveable(asset.id, asset.revision) { mutableIntStateOf(current.ref.ordinal) }
    var total by remember { mutableIntStateOf(current.total) }
    val start = nativeContentsOffset(requested, total)
    var allEntries by remember { mutableStateOf<List<NativeChapterRef>>(emptyList()) }
    var entries by remember(start) { mutableStateOf<List<NativeChapterRef>>(emptyList()) }
    var downloaded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember(start) { mutableStateOf(true) }
    var notice by remember(start) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(start, retry) {
        loading = true
        notice = null
        try {
            val local = source.local(asset)
            total = local.size
            allEntries = local.map { it.ref }
            entries = local.drop(start).take(NATIVE_CONTENTS_PAGE_SIZE).map { it.ref }
            downloaded = allEntries.map { it.id }.toSet()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { notice = e.message ?: "目录加载失败" }
        finally { loading = false }
    }
    NativeContentsBody(entries, downloaded, current.ref.id, current.ref.ordinal, requested, total, start, loading, notice, enabled,
        onRequest = { requested = it }, onRetry = { retry++ }, onOpen = onOpen, allEntries = allEntries)
}

@Composable
internal fun ColumnScope.NativeContentsBody(
    entries: List<NativeChapterRef>, downloaded: Set<String>, currentId: String, currentOrdinal: Int, requested: Int,
    total: Int, start: Int, loading: Boolean, notice: String?, enabled: Boolean,
    onRequest: (Int) -> Unit, onRetry: () -> Unit, onOpen: (NativeChapterRef) -> Unit, allEntries: List<NativeChapterRef> = entries,
) {
    var filter by rememberSaveable { mutableStateOf("") }
    var number by rememberSaveable { mutableStateOf("") }
    var showJump by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    val visible = remember(entries, allEntries, filter) { if (filter.isBlank()) entries else allEntries.filter { SearchText.contains(it.title, filter) } }
    val target = number.toIntOrNull()?.takeIf { it in 1..total }
    var needsPosition by rememberSaveable(requested, filter) { mutableStateOf(true) }
    fun locate(ordinal: Int) { filter = ""; focus.clearFocus(); onRequest(ordinal); needsPosition = true }
    fun jump() { target?.let { locate(it - 1); showJump = false } }
    LaunchedEffect(requested, filter, needsPosition, loading) {
        if (loading || !needsPosition || visible.isEmpty()) return@LaunchedEffect
        val index = if (filter.isBlank()) visible.indexOfFirst { it.ordinal == requested }.coerceAtLeast(0) else 0
        listState.scrollToItem(index)
        needsPosition = false
    }
    if (!showJump) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("共 $total 节", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(enabled = !loading, onClick = {
            locate(currentOrdinal)
        }) { Icon(Icons.Rounded.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("当前章") }
        TextButton(onClick = { showJump = !showJump }) { Text("跳章") }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "更多目录操作") }
            DropdownMenu(more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text("目录开头") }, onClick = { more = false; locate(0) })
                DropdownMenuItem(text = { Text("目录末尾") }, onClick = { more = false; locate((total - 1).coerceAtLeast(0)) })
                DropdownMenuItem(text = { Text("刷新目录") }, onClick = { more = false; onRetry() }, enabled = !loading)
            }
        }
    }
    if (showJump) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(number, { number = it.filter(Char::isDigit).take(9) }, Modifier.weight(1f), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { jump() }), label = { Text("目录序号") },
            trailingIcon = { IconButton(onClick = { showJump = false; focus.clearFocus() }) { Icon(Icons.Rounded.Close, "收起跳章") } },
            supportingText = { Text("1–$total，包含卷标题") }, isError = number.isNotEmpty() && target == null)
        FilledTonalButton(enabled = !loading && target != null, onClick = { jump() }) { Text("定位") }
    }
    if (!showJump) OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text("搜索全书章节标题") },
        trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Rounded.Close, "清除目录筛选") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
    notice?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, overscrollEffect = null, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!loading && visible.isEmpty()) item {
            ReaderEmptyState(Icons.Rounded.SearchOff, if (filter.isBlank()) "本页暂无章节" else "没有匹配的标题", if (filter.isBlank()) "试试其他分页，或刷新目录。" else "全书没有匹配章节，清除关键词可返回当前分页。")
        }
        items(visible, key = { it.id }) { ref ->
            val active = ref.id == currentId
            val background = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
            Surface(onClick = { focus.clearFocus(); onOpen(ref) }, enabled = enabled && !loading && ref.readable,
                shape = RoundedCornerShape(14.dp), color = background, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${ref.ordinal + 1}", Modifier.widthIn(min = 28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(ref.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        if (active || !ref.readable) Text(if (active) "正在阅读" else "卷标题", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (ref.id in downloaded) Icon(Icons.Rounded.OfflinePin, "已下载", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    HorizontalDivider()
    if (filter.isNotBlank()) Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Text("找到 ${visible.size} 节", style = MaterialTheme.typography.labelMedium)
    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(enabled = !loading && start > 0, onClick = { locate(start - NATIVE_CONTENTS_PAGE_SIZE) }) { Icon(Icons.Rounded.ChevronLeft, "上一页目录") }
        Text("${if (total == 0) 0 else start + 1}–${(start + NATIVE_CONTENTS_PAGE_SIZE).coerceAtMost(total)} / $total", style = MaterialTheme.typography.labelMedium)
        IconButton(enabled = !loading && start + NATIVE_CONTENTS_PAGE_SIZE < total, onClick = { locate(start + NATIVE_CONTENTS_PAGE_SIZE) }) { Icon(Icons.Rounded.ChevronRight, "下一页目录") }
    }
}

internal class NativeSearchSession(index: Int = 0, pixels: Int = 0) {
    val query = mutableStateOf("")
    val includeFuture = mutableStateOf(false)
    val result = mutableStateOf<NativeSearchResult?>(null)
    val list = androidx.compose.foundation.lazy.LazyListState(index, pixels)
    var executed = false
}

@Composable
internal fun ColumnScope.NativeReaderSearchPanel(asset: LibraryAssetEntity, publication: NovelPublicationEntity?, source: NativeTextSource, enabled: Boolean, before: Pair<Int, Int>? = null, session: NativeSearchSession? = null, onOpen: (NativeSearchHit) -> Unit) {
    val state = session ?: remember { NativeSearchSession() }
    var includeFuture by state.includeFuture
    var preview by remember { mutableStateOf<NativeSearchHit?>(null) }
    var query by state.query
    var result by state.result
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    if (before != null) Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(includeFuture, onCheckedChange = { includeFuture = it; generation++; job?.cancel(); result = null; searching = false })
        Text("包括后文")
    }
    preview?.let { hit -> AlertDialog(onDismissRequest = { preview = null }, title = { Text(hit.title) },
        text = { Text(hit.preview) }, confirmButton = { TextButton(onClick = { preview = null; onOpen(hit) }) { Text("到原文") } },
        dismissButton = { TextButton(onClick = { preview = null }) { Text("返回结果") } }) }
    val runSearch = {
        if (query.isNotBlank() && !searching) {
            val request = ++generation
            result = null; error = null; searching = true; state.executed = true
            job = scope.launch {
                try {
                    searchNativeText(source.searchEntries(asset, publication), query,
                        before = before.takeUnless { includeFuture }).collect { if (request == generation) result = it }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (request == generation) error = e.message ?: "搜索失败，请重试" }
                finally { if (request == generation) searching = false }
            }
        }
    }
    LaunchedEffect(Unit) { if (state.executed && result == null) runSearch() }
    NativeSearchBody(query, result, searching, error, publication != null, enabled,
        onQuery = { generation++; job?.cancel(); query = it; state.executed = false; searching = false; result = null; error = null },
        onSearch = runSearch, onStop = { generation++; job?.cancel(); searching = false }, onOpen = { preview = it }, listState = state.list)
}

@Composable
internal fun ColumnScope.NativeSearchBody(query: String, result: NativeSearchResult?, searching: Boolean, error: String?, downloadedOnly: Boolean, enabled: Boolean,
    onQuery: (String) -> Unit, onSearch: () -> Unit, onStop: () -> Unit, onOpen: (NativeSearchHit) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()) {
    val focus = LocalFocusManager.current
    fun search() { if (query.isNotBlank() && !searching) { focus.clearFocus(); onSearch() } }
    OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("搜索正文内容") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "清除搜索关键词") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (downloadedOnly) "仅搜索已下载章节" else "搜索本书全文", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (searching) TextButton(onClick = onStop) { Text("停止搜索") }
        else FilledTonalButton(enabled = query.isNotBlank(), onClick = { search() }, modifier = Modifier.padding(vertical = 8.dp)) { Text("搜索") }
    }
    if (searching) {
        if (result != null && result.total > 0) LinearProgressIndicator(progress = { result.scanned.toFloat() / result.total }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
    if (result == null && !searching && error == null) ReaderEmptyState(Icons.AutoMirrored.Rounded.ManageSearch, "查找故事里的细节", "输入人物、地点或原文片段，点击结果即可跳到原文。")
    result?.let { value ->
        Text("${if (value.limited) "前" else "共"} ${value.hits.size} 处匹配 · ${value.scanned}/${value.total} 章${if (!searching && !value.complete) " · 已停止" else ""}",
            Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value.skipped > 0) Text("${value.skipped} 章读取失败，其余结果已保留", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (value.complete && value.hits.isEmpty()) ReaderEmptyState(Icons.Rounded.SearchOff, "没有找到匹配内容", if (downloadedOnly) "试试更短的关键词，或确认目标章节已下载。" else "试试更短的关键词或其他原文片段。")
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(value.hits, key = { it.assetId + it.locator }) { hit ->
                Surface(onClick = { focus.clearFocus(); onOpen(hit) }, enabled = enabled, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(hit.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(buildAnnotatedString {
                            var from = 0
                            val term = SearchText.fold(query.trim())
                            val folded = SearchText.fold(hit.preview)
                            if (term.isNotEmpty()) while (from < hit.preview.length) {
                                val match = folded.indexOf(term, from, ignoreCase = true)
                                if (match < 0) break
                                append(hit.preview.substring(from, match))
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) { append(hit.preview.substring(match, match + term.length)) }
                                from = match + term.length
                            }
                            append(hit.preview.substring(from))
                        }, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
