package com.shadow.media

import androidx.core.content.edit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shadow.media.library.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable internal fun NovelDiscoveryHome(repository: NovelRepository, connection: CatalogConnection,
    onWork: (String) -> Unit, onList: (String) -> Unit, onBrowse: (JSONObject) -> Unit = {}, onSection: (String) -> Unit) {
    val preferences = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("novel_discovery_columns", android.content.Context.MODE_PRIVATE)
    var hidden by remember(connection.id) { mutableStateOf(preferences.getStringSet(connection.id, emptySet()).orEmpty().toSet()) }
    fun hide(ids: Set<String>) { hidden = ids; preferences.edit { putStringSet(connection.id, ids) } }
    var page by remember(connection.id) { mutableStateOf<JSONObject?>(null) }
    var lists by remember(connection.id) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var expanded by rememberSaveable(connection.id) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(connection.id, retry) {
        error = null; loading = true
        try { page = repository.get(connection, listOf("city", "home")) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
        try { lists = repository.get(connection, listOf("booklists"), listOf("limit" to "3")).novelObjects("items") }
        catch (e: CancellationException) { throw e } catch (_: Exception) { }
        loading = false
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { LazyRow { items(listOf("排行榜", "书源栏目", "主题书单", "作家", "分类")) { name -> TextButton(onClick = { onSection(name) }) { Text(name) } } } }
        item { TextButton(enabled = !loading, onClick = { retry++ }) { Text(if (loading) "更新发现中…" else "刷新发现") } }
        error?.let { item { Text(it); TextButton(onClick = { retry++ }) { Text("重新加载") } } }
        items(page?.novelObjects("sections").orEmpty().filterNot { it.optString("id") in hidden }.take(if (expanded) Int.MAX_VALUE else 3), key = { it.optString("id") }) { section ->
            Column {
                Row { Text(section.optString("title"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { onBrowse(section) }) { Text("更多 ${section.optInt("total")}") }
                    TextButton(onClick = { hide(hidden + section.optString("id")) }) { Text("隐藏") }
                }
                NovelBookStrip(section.novelObjects("items").take(6), true, onWork)
            }
        }
        if (page?.novelObjects("sections").orEmpty().count { it.optString("id") !in hidden } > 3) item {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起更多栏目" else "显示全部发现栏目") }
        }
        items(lists, key = { it.getString("id") }) { row -> NovelBooklistCard(row, true, { onList(row.getString("id")) }) }
        if (hidden.isNotEmpty()) item { TextButton(onClick = { hide(emptySet()) }) { Text("恢复隐藏栏目") } }
        if (page != null && page!!.novelObjects("sections").isEmpty() && lists.isEmpty()) item { Text("从书源栏目和排行榜试读，再把喜欢的书加入书架。") }
    }
}
