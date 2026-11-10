package com.shadow.media.library

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class NovelSavedSourceScope(val filter: NovelSourceFilter, val selected: Set<String>)

/** Store choices, not source responses: availability is checked again before every search. */
class NovelSourceScopeStore(context: Context) {
    private val preferences = context.getSharedPreferences("novel_source_scope", Context.MODE_PRIVATE)

    fun load(key: String): NovelSavedSourceScope? = runCatching {
        val raw = preferences.getString(key, null) ?: return null
        val value = JSONObject(raw)
        NovelSavedSourceScope(NovelSourceFilter(
            q = value.optString("q"), groups = value.novelStrings("groups"), tags = value.novelStrings("tags"),
            category = value.optString("category"), availability = value.optString("availability"),
            capability = value.optString("capability"), enabled = value.optString("enabled"),
            exploreEnabled = value.optString("explore_enabled"), groupMode = value.optString("group_mode"),
            sort = value.optString("sort", "priority"),
        ), value.novelStrings("selected").toSet())
    }.getOrNull()

    fun save(key: String, scope: NovelSavedSourceScope) {
        val filter = scope.filter
        val value = JSONObject().put("q", filter.q).put("groups", JSONArray(filter.groups)).put("tags", JSONArray(filter.tags))
            .put("category", filter.category).put("availability", filter.availability).put("capability", filter.capability)
            .put("enabled", filter.enabled).put("explore_enabled", filter.exploreEnabled).put("group_mode", filter.groupMode)
            .put("sort", filter.sort).put("selected", JSONArray(scope.selected.toList()))
        preferences.edit { putString(key, value.toString()) }
    }
}
