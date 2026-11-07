package com.shadow.media

import android.content.SharedPreferences
import com.shadow.media.library.NovelQuery

/** Restore a list's choice before requesting data, including entry from another module. */
internal class NovelLibraryPreferences(private val preferences: SharedPreferences) {
    private fun key(connection: String, view: String, query: NovelQuery) =
        "sort:$connection:$view:" + if (query.ungrouped) "ungrouped" else "group:${query.group}"
    fun restore(connection: String, view: String, query: NovelQuery): NovelQuery {
        val saved = preferences.getString(key(connection, view, query), null)
            ?: preferences.getString("sort:$view:${query.group}", null)
        val allowed = novelSortOptions(view).map { it.first }
        return query.copy(view = view, sort = saved?.takeIf { it in allowed } ?: query.sort.takeIf { it in allowed } ?: "recent")
    }
    fun save(connection: String, view: String, query: NovelQuery) {
        if (query.sort in novelSortOptions(view).map { it.first })
            preferences.edit().putString(key(connection, view, query), query.sort).apply()
    }
}
