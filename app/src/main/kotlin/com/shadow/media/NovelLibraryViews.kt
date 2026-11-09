@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.shadow.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.shadow.media.library.*
import org.json.JSONObject

@Composable internal fun NovelSearchField(value: String, onValue: (String) -> Unit, enabled: Boolean, onSearch: () -> Unit, placeholder: String = "搜索书名、作者或题材", onClear: (() -> Unit)? = null) {
    val focus = LocalFocusManager.current
    fun submit() { if (enabled) { focus.clearFocus(); onSearch() } }
    OutlinedTextField(value, { onValue(it.take(512)) }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp),
        placeholder = { Text(placeholder) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (value.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = enabled, onClick = { onValue(""); onClear?.invoke() }) { Icon(Icons.Rounded.Close, "清除找书关键词") }
            TextButton(enabled = enabled, onClick = { submit() }) { Text("搜索") }
        } }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
}

@Composable internal fun NovelContinueCard(work: JSONObject, enabled: Boolean, onRead: () -> Unit, onDetail: () -> Unit, onBookmarks: (() -> Unit)? = null) {
    val progress = work.optJSONObject("progress")
    val ratio = progress?.optDouble("ratio", 0.0)?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            NovelBookCover(work, Modifier.width(64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("接着上次读", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(work.optString("title"), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (work.optString("last_chapter_title").isNotBlank()) Text(work.optString("last_chapter_title"), style = MaterialTheme.typography.bodySmall)
                var expanded by remember { mutableStateOf(false) }
                val quote = progress?.optJSONObject("anchor")?.optString("quote").orEmpty()
                if (quote.isNotBlank()) {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起上次片段" else "看看上次读到哪里") }
                    if (expanded) Text(quote, maxLines = 4, style = MaterialTheme.typography.bodySmall)
                }
                if (onBookmarks != null) TextButton(onClick = onBookmarks) { Text("书签与摘录") }
                if (progress != null) {
                    Text("本章已读 ${(ratio * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = enabled, onClick = onRead, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("继续阅读") }
                    TextButton(enabled = enabled, onClick = onDetail) { Text("详情") }
                }
            }
        }
    }
}

@Composable internal fun NovelHomeHeader(total: Int?, search: String, onSearch: (String) -> Unit, enabled: Boolean, onSubmit: () -> Unit, onBrowse: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("今天，读点什么", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(total?.let { "$it 部作品，找到下一段好故事" } ?: "从喜欢的故事开始", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NovelSearchField(search, onSearch, enabled, onSubmit)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Triple("分类", Icons.Rounded.Category, "category"), Triple("完本", Icons.Rounded.TaskAlt, "completed"),
                Triple("热门", Icons.Rounded.LocalFireDepartment, "popular"), Triple("作家", Icons.Rounded.PersonOutline, "authors")).forEach { (label, icon, key) ->
                Surface(onClick = { onBrowse(key) }, enabled = enabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

internal fun novelFilterLabels(query: NovelQuery, navigation: JSONObject): List<Pair<String, NovelQuery>> = buildList {
    val categories = navigation.optJSONObject("facets")?.novelObjects("categories").orEmpty().associate { it.optString("id") to it.optString("name") }
    query.categories.forEach { add((categories[it] ?: it) to query.copy(categories = query.categories - it)) }
    query.genres.forEach { add(it to query.copy(genres = query.genres - it)) }
    query.excluded.forEach { add("排除 $it" to query.copy(excluded = query.excluded - it)) }
    if (query.serialStatus.isNotBlank()) add(serialLabel(query.serialStatus).ifBlank { "状态未知" } to query.copy(serialStatus = ""))
    if (query.availability.isNotBlank()) add((mapOf("readable" to "可阅读", "local" to "本地版本", "online" to "在线版本", "cached" to "服务端已缓存", "metadata_only" to "仅资料")[query.availability] ?: query.availability) to query.copy(availability = ""))
    if (query.precise) add("精确搜索" to query.copy(precise = false))
    if (query.hasUpdates) add("有更新" to query.copy(hasUpdates = false))
    if (query.status.isNotBlank()) add((mapOf("want" to "想读", "reading" to "在读", "read" to "已读", "paused" to "暂停")[query.status] ?: query.status) to query.copy(status = ""))
    if (query.ungrouped) add("未分组" to query.copy(ungrouped = false))
    if (query.group.isNotBlank()) add("分组 ${query.group}" to query.copy(group = ""))
    query.personalTags.forEach { add("标签 $it" to query.copy(personalTags = query.personalTags - it)) }
    if (query.minWords.isNotBlank() || query.maxWords.isNotBlank() || query.unknownWords) add("字数筛选" to query.copy(minWords = "", maxWords = "", unknownWords = false))
    if (query.updatedDays.isNotBlank()) add("${query.updatedDays}天内更新" to query.copy(updatedDays = ""))
    if (query.minPopularity.isNotBlank()) add("热度 ≥ ${query.minPopularity}" to query.copy(minPopularity = ""))
}

internal fun novelSortOptions(view: String): List<Pair<String, String>> = buildList {
    if (view == "shelf") add("last_read" to "最近阅读")
    add("recent" to if (view == "history") "最近阅读" else "最近收录")
    add("title" to "书名")
    add("popularity" to "热度")
}

@Composable internal fun NovelWorksPage(
    view: String, query: NovelQuery, search: String, navigation: JSONObject, data: JSONObject?, busy: Boolean,
    onSearch: (String) -> Unit, onSubmit: () -> Unit, onQuery: (NovelQuery) -> Unit, onFilter: () -> Unit,
    onMore: () -> Unit, onWork: (String) -> Unit, onRead: (String) -> Unit,
    onUpdates: ((String) -> Unit)? = null,
    onBulk: (suspend (Set<String>, String, String, List<String>) -> Unit)? = null,
) {
    var managing by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var actions by remember { mutableStateOf(false) }
    var groupManager by remember { mutableStateOf(false) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf(false) }
    val names = remember { mutableStateMapOf<String, String>() }
    val artwork = LocalNovelArtwork.current
    val selectionScope = rememberCoroutineScope()
    if (groupManager && artwork != null) ModalBottomSheet(onDismissRequest = { groupManager = false }) {
        NovelShelfGroupManager(artwork.repository, artwork.connection) { groupManager = false; onQuery(query.copy(group = "", ungrouped = false)) }
    }
    var applying by remember { mutableStateOf(false) }
    fun select(id: String) { if (busy || selecting || applying) return; selected = if (id in selected) selected - id else (selected + id).take(500).toSet() }
    if (actions && onBulk != null) NovelShelfActions(selected, navigation, deleted = view == "trash", onDismiss = { actions = false }, onApply = { action, value, tags ->
        onBulk(selected, action, value, tags)
        selected = emptySet(); actions = false
    })
    val preferences = LocalContext.current.getSharedPreferences("novel_library_ui", android.content.Context.MODE_PRIVATE)
    var grid by rememberSaveable(query.group) { mutableStateOf(preferences.getBoolean("grid:${query.group}", preferences.getBoolean("grid", true))) }
    val items = data?.novelObjects("items").orEmpty()
    val total = data?.optInt("total") ?: 0
    LaunchedEffect(items) { items.forEach { names[it.getString("id")] = it.optString("title") } }
    val labels = novelFilterLabels(query, navigation)
    val isShelf = view == "shelf"
    val gridState = rememberLazyGridState()
    var previousQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) { if (query != previousQuery) gridState.scrollToItem(0); previousQuery = query }
    fun applyDirect(action: String) {
        if (busy || applying || selected.isEmpty() || onBulk == null) return
        val ids = selected.toSet(); applying = true; selectionError = null
        selectionScope.launch {
            try { onBulk(ids, action, "", emptyList()); selected = emptySet() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { selectionError = e.message ?: "操作失败，请重试" }
            finally { applying = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
    LazyVerticalGrid(modifier = Modifier.weight(1f), state = gridState, columns = GridCells.Fixed(if (grid) 3 else 1),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column {
            NovelSearchField(search, onSearch, !busy, onSubmit, if (isShelf) "搜索我的书架" else "搜索书名、作者或题材", onClear = { onQuery(query.copy(q = "")) })
            NovelSavedFilters(view, query, onQuery)
        } }
        item(span = { GridItemSpan(maxLineSpan) }) { Column {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isShelf) item { FilterChip(query.hasUpdates, enabled = !busy, onClick = { onQuery(query.copy(hasUpdates = !query.hasUpdates)) },
                    label = { Text("有更新${navigation.optJSONObject("counts")?.let { " ${it.optInt("updates")}" }.orEmpty()}") }) }
                item { FilterChip(query.availability == "readable", enabled = !busy, onClick = { onQuery(query.copy(availability = if (query.availability == "readable") "" else "readable")) }, label = { Text("可阅读") }) }
                item { FilterChip(query.serialStatus == "completed", enabled = !busy, onClick = { onQuery(query.copy(serialStatus = if (query.serialStatus == "completed") "" else "completed")) }, label = { Text("完结") }) }
                if (isShelf) item { FilterChip(query.status == "reading", enabled = !busy, onClick = { onQuery(query.copy(status = if (query.status == "reading") "" else "reading")) }, label = { Text("在读") }) }
            }
            if (isShelf) LazyRow {
                item { FilterChip(query.group.isBlank() && !query.ungrouped, { onQuery(query.copy(group = "", ungrouped = false)) }, label = { Text("全部分组") }) }
                item { FilterChip(query.ungrouped, { onQuery(query.copy(group = "", ungrouped = !query.ungrouped)) }, label = { Text("未分组") }) }
                items(navigation.novelStrings("groups")) { group -> FilterChip(query.group == group, { onQuery(query.copy(group = group, ungrouped = false)) }, label = { Text(group) }) }
                if (artwork != null) item { TextButton(onClick = { groupManager = true }) { Text("管理分组") } }
            }
            if (labels.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(labels) { (label, cleared) -> InputChip(selected = true, enabled = !busy, onClick = { onQuery(cleared) }, label = { Text(label) }, trailingIcon = { Icon(Icons.Rounded.Close, "移除$label", Modifier.size(14.dp)) }) }
                item { TextButton(enabled = !busy, onClick = { onQuery(NovelQuery(view = query.view, q = query.q, sort = query.sort)) }) { Text("清除筛选") } }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (data == null) { if (busy) "正在找书…" else "尚未载入" } else "$total 本书", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onBulk != null) TextButton(enabled = !busy && !applying && !selecting, onClick = { managing = !managing; selected = emptySet() }) { Text(if (managing) "完成管理" else "批量操作") }
                var sorting by remember { mutableStateOf(false) }
                Box {
                    TextButton(enabled = !busy, onClick = { sorting = true }) { Text(when (query.sort) { "last_read" -> "最近阅读"; "title" -> "书名"; "popularity" -> "热度"; else -> if (view == "history") "最近阅读" else "最近收录" }); Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) }
                    DropdownMenu(sorting, onDismissRequest = { sorting = false }) {
                        novelSortOptions(view).forEach { (key, text) ->
                            DropdownMenuItem(text = { Text(text) }, onClick = { sorting = false; onQuery(query.copy(sort = key)) })
                        }
                    }
                }
                IconButton(enabled = !busy, onClick = onFilter) { Icon(Icons.Rounded.Tune, "全部筛选") }
                IconButton(onClick = { grid = !grid; preferences.edit { putBoolean("grid:${query.group}", grid) } }) { Icon(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, if (grid) "切换列表视图" else "切换网格视图") }
            }
        } }
        if (!busy && data != null && items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (isShelf && labels.isEmpty() && query.q.isBlank()) "书架等你放进第一本书" else "没有找到符合条件的作品", style = MaterialTheme.typography.titleMedium)
                Text(if (isShelf && labels.isEmpty() && query.q.isBlank()) "在书城打开作品，点「加入书架」。" else "试试其他关键词，或减少筛选条件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(items, key = { it.getString("id") }) { work ->
            Column {
            val id = work.getString("id")
            if (managing) Checkbox(id in selected, { select(id) }, enabled = !busy && !applying && !selecting)
            val read = if (view == "trash") null else if (managing) ({ select(id) }) else if (view in setOf("shelf", "history") && novelCanRead(work)) ({ onRead(id) }) else null
            val detail = { if (managing) select(id) else onWork(id) }
            if (grid || view in setOf("shelf", "history")) {
                NovelShelfItem(work, grid, !busy, detail, read,
                    onManage = if (!managing && onBulk != null) ({ selected = setOf(id); actions = true }) else null,
                    onUpdates = if (!managing && view != "trash" && onUpdates != null) ({ onUpdates(id) }) else null,
                    showReadingInfo = !managing && isShelf, showActions = !managing && view != "trash")
            } else NovelWorkCard(work, !busy, detail)
            }
        }
        if (items.size < total) item(span = { GridItemSpan(maxLineSpan) }) { OutlinedButton(enabled = !busy, onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在加载…" else "加载更多 · 已显示 ${items.size} 本") } }
    }
    if (managing) Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("已选 ${selected.size} 本 · 跨页保留，最多 500 本", style = MaterialTheme.typography.labelMedium)
            FlowRow {
                TextButton(enabled = !busy && !applying && !selecting, onClick = { selected = (selected + items.map { it.getString("id") }).take(500).toSet() }) { Text("选择已显示") }
                if (artwork != null) TextButton(enabled = !busy && !selecting && !applying, onClick = {
                    selecting = true; selectionError = null
                    selectionScope.launch {
                        try { selected = artwork.repository.live(artwork.connection, listOf("library", "books", "selection"), query.copy(view = view).parameters()).novelStrings("work_ids").toSet() }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { selectionError = e.message ?: "选择失败" }
                        finally { selecting = false }
                    }
                }) { Text(if (selecting) "选择中…" else "选择全部匹配") }
                TextButton(enabled = !applying && !selecting, onClick = { selected = emptySet() }) { Text("清空选择") }
                TextButton(enabled = !applying, onClick = { review = !review }) { Text("查看已选") }
            }
            FlowRow {
                if (view != "trash") TextButton(enabled = selected.isNotEmpty() && !busy && !applying && !selecting,
                    onClick = { applyDirect(if (isShelf) "shelf_remove" else "shelf_add") }) { Text(if (isShelf) "移出书架" else "加入书架") }
                TextButton(enabled = selected.isNotEmpty() && !busy && !applying && !selecting,
                    onClick = { applyDirect(if (view == "trash") "restore" else "delete") },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(if (view == "trash") "恢复作品" else "删除作品") }
                TextButton(enabled = selected.isNotEmpty() && !busy && !applying && !selecting, onClick = { actions = true }) { Text("整理 ${selected.size} 本") }
            }
            if (review) androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 100.dp)) {
                items(selected.toList(), key = { it }) { id -> Text(names[id] ?: "已选作品 ${id.take(8)}", style = MaterialTheme.typography.bodySmall) }
            }
            selectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    }
}

internal fun novelCanRead(work: JSONObject) = work.novelStrings("availability").any { it in setOf("local", "online", "cached", "readable") }
