package com.shadow.media

import androidx.core.content.edit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.shadow.media.library.*
import org.json.JSONArray
import org.json.JSONObject

internal fun novelFilterSnapshot(query: NovelQuery): JSONObject = JSONObject().apply {
    query.parameters().forEach { (key, value) -> put(key, (optJSONArray(key) ?: JSONArray()).put(value)) }
}
internal fun novelRestoreFilter(json: JSONObject): NovelQuery {
    fun value(key: String) = json.novelStrings(key).firstOrNull().orEmpty()
    return NovelQuery(view = value("view"), q = value("q"), sort = value("sort").ifBlank { "recent" },
        categories = json.novelStrings("category_id"), genres = json.novelStrings("genre_tag"), excluded = json.novelStrings("exclude_genre_tag"),
        personalTags = json.novelStrings("personal_tag"), allGenres = value("tag_mode") == "all", serialStatus = value("serial_status"),
        availability = value("availability"), status = value("status"), group = value("group"), minPopularity = value("min_popularity"),
        hasUpdates = value("has_updates") == "true", precise = value("precise") == "true", minWords = value("min_words"), maxWords = value("max_words"),
        updatedDays = value("updated_days"), unknownWords = value("unknown_words") == "true", ungrouped = value("ungrouped") == "true")
}

@Composable internal fun NovelSavedFilters(view: String, query: NovelQuery, onApply: (NovelQuery) -> Unit) {
    val connection = LocalNovelArtwork.current?.connection?.id.orEmpty()
    val preferences = LocalContext.current.getSharedPreferences("novel_saved_filters", android.content.Context.MODE_PRIVATE)
    val key = "$connection:$view"
    var saved by remember(key) { mutableStateOf(runCatching { JSONObject(preferences.getString(key, "{}") ?: "{}").novelObjects("items") }.getOrDefault(emptyList())) }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    fun save(rows: List<JSONObject>) { saved = rows.take(20); preferences.edit { putString(key, JSONObject().put("items", JSONArray(saved)).toString()) } }
    if (saved.isEmpty() && query == NovelQuery(view = query.view)) return
    LazyRow {
        item { TextButton(onClick = { name = query.q.ifBlank { "常用筛选" }; editing = true }) { Text("保存当前筛选") } }
        items(saved, key = { it.getString("name") }) { item ->
            InputChip(selected = false, onClick = { onApply(novelRestoreFilter(item.getJSONObject("query"))) }, label = { Text(item.optString("name")) }, trailingIcon = {
                TextButton(onClick = { save(saved - item) }) { Text("移除") }
            })
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("保存智能入口") }, text = {
        OutlinedTextField(name, { name = it.take(64) }, singleLine = true, label = { Text("名称") })
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
        val entry = JSONObject().put("name", name.trim()).put("query", novelFilterSnapshot(query.copy(view = view)))
        save(listOf(entry) + saved.filterNot { it.optString("name") == name.trim() }); editing = false
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } })
}
