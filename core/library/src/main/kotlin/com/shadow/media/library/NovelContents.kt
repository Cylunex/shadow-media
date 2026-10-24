package com.shadow.media.library

import org.json.JSONArray
import org.json.JSONObject

/** Offsets address the filtered, ordered result, while chapter ordinals always address the version. */
data class NovelContentsQuery(val versionId: String, val q: String = "", val descending: Boolean = false, val offset: Int = 0, val limit: Int = 100) {
    init { require(versionId.isNotBlank() && offset >= 0 && limit in 1..200 && q.length <= 200) }
    fun parameters() = listOf("version_id" to versionId, "q" to q.trim(), "order" to if (descending) "desc" else "asc",
        "offset" to offset.toString(), "limit" to limit.toString())
    fun search(text: String) = copy(q = text.trim().take(200), offset = 0)
    fun reverse() = copy(descending = !descending, offset = 0)
    fun locate(ordinal: Int) = copy(q = "", descending = false, offset = ordinal.coerceAtLeast(0) / limit * limit)
}

/** next_offset=null is authoritative; old v1 services without it retain bounded pagination. */
fun novelContentsNext(page: JSONObject): Int? {
    val offset = page.optInt("offset").coerceAtLeast(0)
    val next = if (page.has("next_offset")) {
        if (page.isNull("next_offset")) return null else page.optInt("next_offset", -1)
    } else offset + page.novelObjects("items").size
    return next.takeIf { it > offset && it < page.optInt("total") }
}

/** Offline fallback searches all downloaded titles, never represents them as a complete directory. */
fun novelOfflineContents(chapters: List<JSONObject>, query: NovelContentsQuery): JSONObject {
    val ordered = chapters.distinctBy { it.getString("id") }.sortedBy { it.getInt("ordinal") }
    val filtered = ordered.filter { query.q.isBlank() || it.optString("title").contains(query.q.trim(), ignoreCase = true) }
        .let { if (query.descending) it.reversed() else it }
    val start = query.offset.coerceAtMost(((filtered.size - 1).coerceAtLeast(0) / query.limit) * query.limit)
    val rows = filtered.drop(start).take(query.limit)
    return JSONObject().put("version_id", query.versionId).put("offset", start).put("limit", query.limit)
        .put("total", filtered.size).put("items", JSONArray(rows)).put("_downloaded_only", true)
        .put("next_offset", (start + rows.size).takeIf { it < filtered.size } ?: JSONObject.NULL)
        .put("latest", JSONObject.NULL)
}

/** A saved complete TOC includes uncached chapters; availability badges still come from local files. */
fun novelSavedDirectoryContents(directory: JSONObject, query: NovelContentsQuery): JSONObject {
    require(directory.getString("version_id") == query.versionId)
    val entries = directory.novelObjects("items")
    return novelOfflineContents(entries, query).apply {
        remove("_downloaded_only")
        put("_directory_cached", true)
        put("latest", entries.lastOrNull { it.optBoolean("readable") } ?: JSONObject.NULL)
    }
}
