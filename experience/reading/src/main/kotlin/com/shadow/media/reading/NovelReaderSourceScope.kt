package com.shadow.media.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.shadow.media.library.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal fun novelReaderScopeLabel(filter: NovelSourceFilter, catalog: JSONObject?): String {
    val groups = filter.groups.map { id -> catalog?.novelObjects("groups")?.firstOrNull { it.optString("id") == id }?.optString("name") ?: "已选分组" }
    return (groups.map { "分组：$it" } + filter.tags.map { "标签：$it" }).joinToString(" · ").ifBlank { "全部可搜索书源" }
}

/** A draft only: changing scope never cancels a task, clears results or changes reading progress. */
@Composable
internal fun NovelReaderSourceScope(repository: NovelRepository, connection: CatalogConnection,
    initial: NovelSourceFilter, catalog: JSONObject?, onCatalog: (JSONObject) -> Unit,
    onDismiss: () -> Unit, onApply: (NovelSourceFilter) -> Unit) {
    var filter by remember { mutableStateOf(initial) }
    var data by remember { mutableStateOf(catalog) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry) {
        loading = true; error = null
        try { data = repository.live(connection, listOf("source-browser"), listOf("mode" to "search")).also(onCatalog) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "范围读取失败" }
        finally { loading = false }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(16.dp)) {
                Text("搜索范围", style = MaterialTheme.typography.titleLarge)
                Text("分组任选其一，标签同时满足；仅影响下一次搜索。", style = MaterialTheme.typography.bodySmall)
                Box(Modifier.fillMaxWidth().height(4.dp)) { if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text("重试读取范围") } }
                OutlinedTextField(query, { query = it.take(100) }, singleLine = true, label = { Text("查找标签或分组") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Text("分组 · 多选取并集", style = MaterialTheme.typography.titleSmall) }
                    items(data?.novelObjects("groups").orEmpty().filter { it.optString("name").contains(query, true) }, key = { "group:" + it.getString("id") }) { group ->
                        val id = group.getString("id")
                        FilterChip(id in filter.groups, { filter = filter.copy(groups = if (id in filter.groups) filter.groups - id else filter.groups + id) }, label = { Text(group.optString("name")) })
                    }
                    items(filter.groups.filter { id -> data?.novelObjects("groups").orEmpty().none { it.optString("id") == id } }, key = { "missing:$it" }) { id ->
                        FilterChip(true, { filter = filter.copy(groups = filter.groups - id) }, label = { Text("失效或未读取分组 · 点击移除") })
                    }
                    item { Text("标签 · 多选同时满足", style = MaterialTheme.typography.titleSmall) }
                    items((data?.novelStrings("tags").orEmpty() + filter.tags).distinct().filter { it.contains(query, true) }, key = { "tag:$it" }) { tag ->
                        FilterChip(tag in filter.tags, { filter = filter.copy(tags = if (tag in filter.tags) filter.tags - tag else filter.tags + tag) }, label = { Text(tag) })
                    }
                    if (data != null && data?.novelStrings("tags").isNullOrEmpty()) item { Text("暂无书源标签") }
                }
                Text(novelReaderScopeLabel(filter, data), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Row {
                    TextButton(onClick = { filter = initial.copy(groups = emptyList(), tags = emptyList()) }) { Text("清除范围") }
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = { onApply(filter) }) { Text("应用范围") }
                }
            }
        }
    }
}
