@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import com.shadow.media.database.LibraryAssetEntity
import com.shadow.media.database.NovelPublicationEntity
import com.shadow.media.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    var requested by remember { mutableIntStateOf(current.ref.ordinal) }
    var scrollRequest by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(current.total) }
    val start = nativeContentsOffset(requested, total)
    var entries by remember(start) { mutableStateOf<List<NativeChapterRef>>(emptyList()) }
    var downloaded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember(start) { mutableStateOf(true) }
    var notice by remember(start) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(start, retry) {
        loading = true
        notice = null
        try {
            if (publication == null) {
                val local = source.local(asset)
                total = local.size
                entries = local.drop(start).take(NATIVE_CONTENTS_PAGE_SIZE).map { it.ref }
                downloaded = entries.map { it.id }.toSet()
            } else {
                val cached = withContext(Dispatchers.IO) { novel.cachedContents(publication) }
                downloaded = cached.map { it.getString("id") }.toSet()
                try {
                    val page = novel.get(novel.connection(publication.scope), listOf("works", publication.workId, "contents"),
                        listOf("version_id" to publication.versionId, "offset" to start.toString(), "limit" to NATIVE_CONTENTS_PAGE_SIZE.toString()))
                    entries = page.novelObjects("items").map { json -> NativeChapterRef(json.getString("id"), json.optString("title"), json.getInt("ordinal"), "chapters/${json.getString("id")}.xhtml", json.optBoolean("readable", true)) }
                    total = page.optInt("total", current.total)
                    if (page.has("_cached_at")) notice = "当前为缓存目录"
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    entries = cached.filter { it.getInt("ordinal") in start until start + NATIVE_CONTENTS_PAGE_SIZE }
                        .map { json -> NativeChapterRef(json.getString("id"), json.optString("title"), json.getInt("ordinal"), json.getString("href")) }
                    notice = "暂时无法获取完整目录，显示本机已下载章节"
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { notice = e.message ?: "目录加载失败" }
        finally { loading = false }
    }
    val visible = remember(entries, filter) { entries.filter { filter.isBlank() || it.title.contains(filter.trim(), ignoreCase = true) } }
    LaunchedEffect(entries, requested, filter, scrollRequest) {
        val index = visible.indexOfFirst { it.ordinal == requested }.coerceAtLeast(0)
        listState.scrollToItem(index)
    }
    Text("共 $total 节 · 第 ${if (total == 0) 0 else start + 1}–${(start + NATIVE_CONTENTS_PAGE_SIZE).coerceAtMost(total)} 节", style = MaterialTheme.typography.bodySmall)
    FlowRow {
        TextButton(enabled = !loading, onClick = { requested = 0; filter = ""; scrollRequest++ }) { Text("开头") }
        TextButton(enabled = !loading, onClick = { filter = ""; requested = current.ref.ordinal; scrollRequest++ }) { Text("当前章") }
        TextButton(enabled = !loading, onClick = { requested = (total - 1).coerceAtLeast(0); filter = ""; scrollRequest++ }) { Text("末尾") }
        TextButton(enabled = !loading, onClick = { retry++ }) { Text("刷新") }
    }
    Row {
        OutlinedTextField(number, { number = it.filter(Char::isDigit).take(9) }, Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("目录序号（含卷标题）") })
        TextButton(enabled = !loading && number.toIntOrNull()?.let { it in 1..total } == true, onClick = { filter = ""; requested = number.toInt() - 1; scrollRequest++ }) { Text("定位") }
    }
    OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("筛选本页标题") })
    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 360.dp), state = listState) {
        if (!loading && visible.isEmpty()) item { Text(if (filter.isBlank()) "本页没有可显示的章节" else "本页没有匹配的标题", Modifier.padding(vertical = 16.dp)) }
        items(visible, key = { it.id }) { ref ->
            val active = ref.id == current.ref.id
            TextButton(enabled = enabled && !loading && ref.readable, modifier = Modifier.fillMaxWidth(), onClick = { onOpen(ref) },
                colors = ButtonDefaults.textButtonColors(contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)) {
                Column(Modifier.fillMaxWidth()) {
                    Text("${ref.ordinal + 1}. ${ref.title}")
                    val badges = listOfNotNull(if (active) "正在读" else null, if (ref.id in downloaded) "已下载" else null, if (!ref.readable) "卷标题" else null)
                    if (badges.isNotEmpty()) Text(badges.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(enabled = !loading && start > 0, onClick = { requested = start - NATIVE_CONTENTS_PAGE_SIZE }) { Text("上 100 节") }
        TextButton(enabled = !loading && start + NATIVE_CONTENTS_PAGE_SIZE < total, onClick = { requested = start + NATIVE_CONTENTS_PAGE_SIZE }) { Text("下 100 节") }
    }
}

@Composable
internal fun ColumnScope.NativeReaderSearchPanel(asset: LibraryAssetEntity, publication: NovelPublicationEntity?, source: NativeTextSource, enabled: Boolean, onOpen: (NativeSearchHit) -> Unit) {
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<NativeSearchResult?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    Text(if (publication == null) "搜索本书全文" else "仅搜索本机已下载章节", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(query, { generation++; job?.cancel(); query = it; searching = false; result = null; error = null }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("输入正文内容") })
    Row {
        TextButton(enabled = query.isNotBlank() && !searching, onClick = {
            val request = ++generation
            result = null; error = null; searching = true
            job = scope.launch {
                try {
                    searchNativeText(source.searchEntries(asset, publication), query).collect { if (request == generation) result = it }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (request == generation) error = e.message ?: "搜索失败，请重试" }
                finally { if (request == generation) searching = false }
            }
        }) { Text("搜索") }
        if (searching) TextButton(onClick = { generation++; job?.cancel(); searching = false }) { Text("停止") }
    }
    if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    result?.let { value ->
        if (!searching && !value.complete) Text("搜索已停止", style = MaterialTheme.typography.bodySmall)
        Text("${if (value.limited) "已显示前" else "找到"} ${value.hits.size} 处 · 已搜索 ${value.scanned}/${value.total} 章${if (value.skipped > 0) " · ${value.skipped} 章读取失败" else ""}", style = MaterialTheme.typography.bodySmall)
        if (value.complete && value.hits.isEmpty()) Text("没有找到匹配内容", Modifier.padding(vertical = 16.dp))
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 380.dp)) {
            items(value.hits) { hit -> TextButton(enabled = enabled, onClick = { onOpen(hit) }) { Text("${hit.title}：${hit.preview}", maxLines = 3) } }
        }
    }
}
