@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shadow.media.library.novelStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Shared searchable choices preserve selected values that are outside current facets. */
@Composable internal fun NovelValuePicker(title: String, options: List<String>, selected: List<String>,
    single: Boolean = false, allowCreate: Boolean = false, onChange: (List<String>) -> Unit) {
    var query by remember(title) { mutableStateOf("") }
    Text(title, style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(query, { query = it.take(64) }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(if (allowCreate) "查找或新建$title" else "查找$title") })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (selected + options).filter(String::isNotBlank).distinct().filter { it in selected || it.contains(query.trim(), true) }.forEach { name ->
            FilterChip(name in selected, onClick = {
                onChange(if (name in selected) selected - name else if (single) listOf(name) else selected + name)
            }, label = { Text(name) })
        }
    }
    val name = query.trim()
    if (allowCreate && name.isNotEmpty() && name !in options && name !in selected) TextButton(onClick = {
        onChange(if (single) listOf(name) else selected + name); query = ""
    }) { Text("新建并选择「$name」") }
    if (selected.isNotEmpty()) TextButton(onClick = { onChange(emptyList()) }) { Text("清除$title") }
}

@Composable internal fun NovelShelfActions(ids: Set<String>, navigation: JSONObject, onDismiss: () -> Unit,
    onApply: suspend (String, String, List<String>) -> Unit) {
    var action by remember { mutableStateOf("group") }
    var group by remember { mutableStateOf<List<String>>(emptyList()) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("reading") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("整理 ${ids.size} 本", style = MaterialTheme.typography.titleLarge) }
            item { NovelOptionChips("操作", action, listOf("group" to "移动分组", "tags_add" to "添加标签", "tags_remove" to "移除标签", "status" to "阅读状态", "refresh_following" to "检查追更", "shelf_remove" to "移出书架")) { if (!busy) { action = it; confirmed = false } } }
            item {
                when (action) {
                    "group" -> NovelValuePicker("分组", navigation.novelStrings("groups"), group, single = true, allowCreate = true) { group = it }
                    "tags_add", "tags_remove" -> NovelValuePicker("标签", navigation.novelStrings("tags"), tags, allowCreate = action == "tags_add") { tags = it }
                    "status" -> NovelOptionChips("状态", status, listOf("want" to "想读", "reading" to "在读", "read" to "已读", "paused" to "暂停")) { status = it }
                    "refresh_following" -> Text("检查所选作品的追更来源，任务在后台继续。")
                    "shelf_remove" -> Row { Checkbox(confirmed, { confirmed = it }); Text("移出所选 ${ids.size} 本并停止追更，保留作品和原文件") }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Button(enabled = !busy && (action != "shelf_remove" || confirmed) && (!action.startsWith("tags_") || tags.isNotEmpty()), onClick = {
                busy = true; error = null
                scope.launch {
                    try { onApply(action, if (action == "status") status else group.firstOrNull().orEmpty(), tags) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "整理失败，请重试" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "正在整理" else "应用") } }
        }
    }
}
