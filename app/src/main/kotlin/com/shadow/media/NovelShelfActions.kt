@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shadow.media.library.novelStrings
import com.shadow.media.library.novelObjects
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
    localOnly: Boolean = false, deleted: Boolean = false, onApply: suspend (String, String, List<String>) -> Unit) {
    var action by remember { mutableStateOf("group") }
    var group by remember { mutableStateOf<List<String>>(emptyList()) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("reading") }
    var booklists by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var listChoice by remember { mutableStateOf<List<String>>(emptyList()) }
    var listTotal by remember { mutableIntStateOf(0) }
    val artwork = LocalNovelArtwork.current
    LaunchedEffect(action) {
        if (action == "booklist" && artwork != null) {
            try { val page = artwork.repository.get(artwork.connection, listOf("booklists"), listOf("limit" to "100")); booklists = page.novelObjects("items"); listTotal = page.optInt("total") }
            catch (e: CancellationException) { throw e } catch (_: Exception) { booklists = emptyList() }
        }
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("整理 ${ids.size} 本", style = MaterialTheme.typography.titleLarge) }
            item { NovelOptionChips("操作", action, listOf("restore" to "恢复作品", "delete" to "删除作品", "shelf_add" to "加入书架", "download" to "下载全本", "booklist" to "加入书单", "pin" to "置顶", "unpin" to "取消置顶", "group" to "移动分组", "tags_add" to "添加标签", "tags_remove" to "移除标签", "status" to "阅读状态", "refresh_following" to "检查追更", "shelf_remove" to "移出书架").filter { if (deleted) it.first == "restore" else it.first != "restore" }.filterNot { (localOnly && it.first in setOf("download", "booklist", "refresh_following", "shelf_remove", "shelf_add", "delete", "restore")) || (it.first == "download" && ids.size != 1) }) { if (!busy) { action = it; confirmed = false } } }
            item {
                when (action) {
                    "booklist" -> {
                        NovelValuePicker("书单", booklists.map { it.optString("title") + " · " + it.getString("id").take(8) }, listChoice, single = true) { listChoice = it }
                        if (booklists.size < listTotal && artwork != null) TextButton(onClick = { scope.launch {
                            try { booklists = booklists + artwork.repository.get(artwork.connection, listOf("booklists"), listOf("limit" to "100", "offset" to booklists.size.toString())).novelObjects("items") }
                            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
                        } }) { Text("更多书单") }
                    }
                    "group" -> NovelValuePicker("分组", navigation.novelStrings("groups"), group, single = true, allowCreate = true) { group = it }
                    "tags_add", "tags_remove" -> NovelValuePicker("标签", navigation.novelStrings("tags"), tags, allowCreate = action == "tags_add") { tags = it }
                    "status" -> NovelOptionChips("状态", status, listOf("want" to "想读", "reading" to "在读", "read" to "已读", "paused" to "暂停")) { status = it }
                    "download" -> Text("下载所选作品当前版本的全本，仅 Wi-Fi。进度可从离线阅读查看。")
                    "refresh_following" -> Text("检查所选作品的追更来源，任务在后台继续。")
                    "delete" -> Text("从书城、搜索和书架移到已删除列表，可恢复。原文件、阅读进度与笔记保留。")
                    "restore" -> Text("恢复作品及其原有书架、阅读进度和笔记。")
                    "shelf_remove" -> Row { Checkbox(confirmed, { confirmed = it }); Text("移出所选 ${ids.size} 本并停止追更，保留作品和原文件") }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Button(enabled = !busy && (action != "shelf_remove" || confirmed) && (!action.startsWith("tags_") || tags.isNotEmpty()) && (action != "booklist" || listChoice.isNotEmpty()), onClick = {
                busy = true; error = null
                scope.launch {
                    try { onApply(action, if (action == "status") status else if (action == "booklist") booklists.first { it.optString("title") + " · " + it.getString("id").take(8) == listChoice.first() }.getString("id") else group.firstOrNull().orEmpty(), tags) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "整理失败，请重试" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "正在整理" else "应用") } }
        }
    }
}

@Composable internal fun NovelShelfGroupManager(repository: com.shadow.media.library.NovelRepository,
    connection: com.shadow.media.library.CatalogConnection, onChanged: () -> Unit) {
    val task = rememberNovelTask()
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var dissolving by remember { mutableStateOf<String?>(null) }
    suspend fun load() { rows = repository.live(connection, listOf("library", "shelf-groups")).novelObjects("items") }
    fun edit(action: String, target: String, replacement: String = "") = task.run {
        repository.mutate(connection, listOf("library", "shelf-groups"), "POST", JSONObject().put("action", action).put("name", target).put("new_name", replacement))
        editing = null; name = ""; load(); onChanged()
    }
    LaunchedEffect(connection.id) { task.run { load() } }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("书架分组", style = MaterialTheme.typography.titleLarge); NovelTaskStatus(task) }
        item { OutlinedTextField(name, { name = it.take(128) }, label = { Text(if (editing == null) "新分组" else "分组新名称") })
            Button(enabled = !task.busy && name.isNotBlank(), onClick = { edit(if (editing == null) "create" else "rename", editing ?: name.trim(), name.trim()) }) { Text(if (editing == null) "新建" else "保存名称") }
        }
        items(rows.size) { index -> val row = rows[index]; val group = row.optString("name")
            Text("$group · ${row.optInt("count")} 本")
            Row {
                TextButton(enabled = !task.busy, onClick = { editing = group; name = group }) { Text("重命名") }
                TextButton(enabled = !task.busy && index > 0, onClick = { edit("up", group) }) { Text("上移") }
                TextButton(enabled = !task.busy && index < rows.lastIndex, onClick = { edit("down", group) }) { Text("下移") }
                TextButton(enabled = !task.busy, onClick = { dissolving = group }) { Text("解散") }
            }
        }
    }
    dissolving?.let { group -> AlertDialog(onDismissRequest = { dissolving = null }, title = { Text("解散「$group」？") }, text = { Text("组内作品将进入未分组，阅读记录继续保留。") }, confirmButton = { TextButton(onClick = { dissolving = null; edit("dissolve", group) }) { Text("解散分组") } }, dismissButton = { TextButton(onClick = { dissolving = null }) { Text("取消") } }) }
}

@Composable internal fun NovelGenrePicker(options: List<String>, included: List<String>, excluded: List<String>,
    onChange: (List<String>, List<String>) -> Unit) {
    var query by remember { mutableStateOf("") }
    Text("题材", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(query, { query = it }, label = { Text("查找包含或排除的题材") }, singleLine = true)
    val selected = (included + excluded).distinct()
    val visible = (selected + options).distinct().filter { it in selected || it.contains(query.trim(), true) }
    LazyColumn(Modifier.heightIn(max = 320.dp)) {
        items(visible.size) { index -> val name = visible[index]
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f))
                FilterChip(name in included, onClick = { onChange(if (name in included) included - name else included + name, excluded - name) }, label = { Text("包含") })
                FilterChip(name in excluded, onClick = { onChange(included - name, if (name in excluded) excluded - name else excluded + name) }, label = { Text("排除") })
            }
        }
    }
}
