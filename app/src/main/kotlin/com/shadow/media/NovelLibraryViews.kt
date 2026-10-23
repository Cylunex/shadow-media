@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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

@Composable internal fun NovelContinueCard(work: JSONObject, enabled: Boolean, onRead: () -> Unit, onDetail: () -> Unit) {
    val progress = work.optJSONObject("progress")
    val ratio = progress?.optDouble("ratio", 0.0)?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            NovelBookCover(work, Modifier.width(64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("接着上次读", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(work.optString("title"), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onPrimaryContainer)
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
    if (query.availability.isNotBlank()) add((mapOf("readable" to "可阅读", "local" to "本地版本", "online" to "在线版本", "metadata_only" to "仅资料")[query.availability] ?: query.availability) to query.copy(availability = ""))
    if (query.precise) add("精确搜索" to query.copy(precise = false))
    if (query.hasUpdates) add("有更新" to query.copy(hasUpdates = false))
    if (query.status.isNotBlank()) add((mapOf("want" to "想读", "reading" to "在读", "read" to "已读", "paused" to "暂停")[query.status] ?: query.status) to query.copy(status = ""))
    if (query.group.isNotBlank()) add("分组 ${query.group}" to query.copy(group = ""))
    query.personalTags.forEach { add("标签 $it" to query.copy(personalTags = query.personalTags - it)) }
    if (query.minPopularity.isNotBlank()) add("热度 ≥ ${query.minPopularity}" to query.copy(minPopularity = ""))
}

@Composable internal fun NovelWorksPage(
    view: String, query: NovelQuery, search: String, navigation: JSONObject, data: JSONObject?, busy: Boolean,
    onSearch: (String) -> Unit, onSubmit: () -> Unit, onQuery: (NovelQuery) -> Unit, onFilter: () -> Unit,
    onMore: () -> Unit, onWork: (String) -> Unit, onRead: (String) -> Unit,
) {
    val preferences = LocalContext.current.getSharedPreferences("novel_library_ui", android.content.Context.MODE_PRIVATE)
    var grid by rememberSaveable { mutableStateOf(preferences.getBoolean("grid", true)) }
    val items = data?.novelObjects("items").orEmpty()
    val total = data?.optInt("total") ?: 0
    val labels = novelFilterLabels(query, navigation)
    val isShelf = view == "shelf"
    val gridState = rememberLazyGridState()
    var previousQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) { if (query != previousQuery) gridState.scrollToItem(0); previousQuery = query }
    LazyVerticalGrid(modifier = Modifier.fillMaxSize(), state = gridState, columns = if (grid) GridCells.Adaptive(96.dp) else GridCells.Fixed(1),
        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { NovelSearchField(search, onSearch, !busy, onSubmit, if (isShelf) "搜索我的书架" else "搜索书名、作者或题材", onClear = { onQuery(query.copy(q = "")) }) }
        item(span = { GridItemSpan(maxLineSpan) }) { Column {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isShelf) item { FilterChip(query.hasUpdates, enabled = !busy, onClick = { onQuery(query.copy(hasUpdates = !query.hasUpdates)) },
                    label = { Text("有更新${navigation.optJSONObject("counts")?.let { " ${it.optInt("updates")}" }.orEmpty()}") }) }
                item { FilterChip(query.availability == "readable", enabled = !busy, onClick = { onQuery(query.copy(availability = if (query.availability == "readable") "" else "readable")) }, label = { Text("可阅读") }) }
                item { FilterChip(query.serialStatus == "completed", enabled = !busy, onClick = { onQuery(query.copy(serialStatus = if (query.serialStatus == "completed") "" else "completed")) }, label = { Text("完结") }) }
                if (isShelf) item { FilterChip(query.status == "reading", enabled = !busy, onClick = { onQuery(query.copy(status = if (query.status == "reading") "" else "reading")) }, label = { Text("在读") }) }
            }
            if (labels.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(labels) { (label, cleared) -> InputChip(selected = true, enabled = !busy, onClick = { onQuery(cleared) }, label = { Text(label) }, trailingIcon = { Icon(Icons.Rounded.Close, "移除$label", Modifier.size(14.dp)) }) }
                item { TextButton(enabled = !busy, onClick = { onQuery(NovelQuery(view = query.view, q = query.q, sort = query.sort)) }) { Text("清除筛选") } }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (data == null) { if (busy) "正在找书…" else "尚未载入" } else "$total 本书", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var sorting by remember { mutableStateOf(false) }
                Box {
                    TextButton(enabled = !busy, onClick = { sorting = true }) { Text(when (query.sort) { "title" -> "书名"; "popularity" -> "热度"; else -> if (view == "history") "最近阅读" else "最近收录" }); Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) }
                    DropdownMenu(sorting, onDismissRequest = { sorting = false }) {
                        listOf("recent" to if (view == "history") "最近阅读" else "最近收录", "title" to "书名", "popularity" to "热度").forEach { (key, text) ->
                            DropdownMenuItem(text = { Text(text) }, onClick = { sorting = false; onQuery(query.copy(sort = key)) })
                        }
                    }
                }
                IconButton(enabled = !busy, onClick = onFilter) { Icon(Icons.Rounded.Tune, "全部筛选") }
                IconButton(onClick = { grid = !grid; preferences.edit { putBoolean("grid", grid) } }) { Icon(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, if (grid) "切换列表视图" else "切换网格视图") }
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
            if (!grid) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NovelWorkCard(work, !busy) { onWork(work.getString("id")) }
                if (view in setOf("shelf", "history") && novelCanRead(work)) TextButton(enabled = !busy, onClick = { onRead(work.getString("id")) }) { Text(if (work.optJSONObject("progress") == null) "开始阅读" else "继续阅读") }
            } else NovelShelfTile(work, !busy, { onWork(work.getString("id")) }, if (view in setOf("shelf", "history") && novelCanRead(work)) ({ onRead(work.getString("id")) }) else null)
        }
        if (items.size < total) item(span = { GridItemSpan(maxLineSpan) }) { OutlinedButton(enabled = !busy, onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在加载…" else "加载更多 · 已显示 ${items.size} 本") } }
    }
}

internal fun novelCanRead(work: JSONObject) = work.novelStrings("availability").any { it in setOf("local", "online", "cached", "readable") }

@Composable internal fun NovelShelfTile(work: JSONObject, enabled: Boolean, onDetail: () -> Unit, onRead: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(enabled = enabled, onClick = onDetail, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box {
                    NovelBookCover(work, Modifier.fillMaxWidth())
                    if (work.optInt("update_count") > 0) Surface(Modifier.align(Alignment.TopEnd).padding(5.dp), shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary) {
                        Text("+${work.optInt("update_count")}章", Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(work.optString("title"), maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(work.optString("author").ifBlank { "作者待补全" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (!novelCanRead(work)) "仅资料" else serialLabel(work.optString("serial_status")).ifBlank { "可阅读" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (onRead != null) TextButton(enabled = enabled, onClick = onRead, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (work.optJSONObject("progress") != null) "继续阅读" else "开始阅读") }
    }
}
