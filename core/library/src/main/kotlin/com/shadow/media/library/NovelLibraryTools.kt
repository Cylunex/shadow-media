package com.shadow.media.library

import java.io.InputStream
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Independent source groups use OR; repeated source tags use AND. */
data class NovelSourceFilter(
    val q: String = "", val groups: List<String> = emptyList(), val tags: List<String> = emptyList(),
    val category: String = "", val availability: String = "", val capability: String = "",
    val enabled: String = "", val exploreEnabled: String = "", val groupMode: String = "", val sort: String = "priority",
) {
    fun parameters() = buildList {
        listOf("q" to q, "category" to category, "availability" to availability, "capability" to capability,
            "enabled" to enabled, "explore_enabled" to exploreEnabled, "group_mode" to groupMode)
            .filter { it.second.isNotBlank() }.forEach(::add)
        groups.distinct().forEach { add("group_id" to it) }; tags.distinct().forEach { add("tag" to it) }
    }
}

fun novelSourceNextPage(source: JSONObject, paginated: Boolean): Int? =
    if (paginated && source.optBoolean("ok") && source.optInt("raw_count", source.optInt("count")) > 0 && source.optInt("page", 1) < 100)
        source.optInt("page", 1) + 1 else null

fun novelResultKey(row: JSONObject) = row.optString("provider_id") + "\u0000" + row.optString("bookUrl")
fun novelSourceDetail(row: JSONObject, preview: JSONObject?): JSONObject = JSONObject(row.toString()).also { merged ->
    val source = preview?.optJSONObject("source") ?: preview
    listOf("provider_id", "provider_name", "capabilities", "bookUrl").forEach { key ->
        if (source != null && source.has(key) && !source.isNull(key)) merged.put(key, source.get(key))
    }
    preview?.optJSONObject("detail")?.let { detail -> detail.keys().forEach { key ->
        if (!detail.isNull(key) && detail.optString(key).isNotBlank()) merged.put(key, detail.get(key))
    } }
}
fun novelPreviewChapters(preview: JSONObject?, query: String, descending: Boolean): List<JSONObject> =
    preview?.optJSONObject("toc")?.novelObjects("items").orEmpty()
        .filter { it.optString("title").contains(query.trim(), ignoreCase = true) }.let { if (descending) it.reversed() else it }
fun novelPreviewTitles(preview: JSONObject?, query: String, descending: Boolean): List<String> =
    novelPreviewChapters(preview, query, descending).map { it.optString("title") }
fun JSONObject.novelId(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

class NovelLibraryTools(private val api: NovelApi) {
    suspend fun catalog(c: CatalogConnection, filter: NovelSourceFilter, offset: Int = 0) = api.json(c, listOf("providers", "catalog"),
        filter.parameters() + listOf("sort" to filter.sort, "offset" to offset.toString(), "limit" to "40"))
    suspend fun selection(c: CatalogConnection, filter: NovelSourceFilter) = api.json(c, listOf("source-selection"), filter.parameters()).novelStrings("provider_ids")
    suspend fun upload(c: CatalogConnection, filename: String, input: () -> InputStream) = api.upload(c, filename, input)
    suspend fun export(c: CatalogConnection, path: List<String>, output: () -> OutputStream, body: JSONObject? = null) =
        api.export(c, path, output, if (body == null) "GET" else "POST", body)
    suspend fun import(c: CatalogConnection, path: List<String>, input: () -> InputStream): JSONObject {
        val content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            input().use { SafeArchives.readBounded(it, 8L * 1024 * 1024).toString(Charsets.UTF_8) }
        }
        return api.json(c, path, method = "POST", body = JSONObject().put("content", content))
    }
    suspend fun exportOpml(c: CatalogConnection, output: () -> OutputStream) {
        val rows = api.json(c, listOf("subscriptions")).novelObjects("items").filter { it.optString("kind") == "rss" }
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        val text = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><opml version=\"2.0\"><head><title>Novel RSS</title></head><body>" +
            rows.joinToString("") { "<outline type=\"rss\" text=\"${escape(it.optString("name"))}\" xmlUrl=\"${escape(it.optString("url"))}\"/>" } + "</body></opml>"
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { output().use { it.write(text.toByteArray()) } }
    }
    suspend fun bulkBooks(c: CatalogConnection, ids: Collection<String>, action: String, value: String = "", tags: List<String> = emptyList()): JSONObject {
        require(ids.isNotEmpty() && ids.size <= 500) { "一次可整理 1～500 本作品" }
        return api.json(c, listOf("library", "books", "bulk"), method = "POST", body = JSONObject()
            .put("work_ids", JSONArray(ids)).put("action", action).put("value", value).put("tags", JSONArray(tags)))
    }
}

/** A failed TOC refresh must not erase the previously loaded synopsis or preview. */
fun mergeNovelPreview(previous: JSONObject?, incoming: JSONObject): JSONObject = JSONObject(incoming.toString()).also { merged ->
    val detail = JSONObject(previous?.optJSONObject("detail")?.toString() ?: "{}")
    incoming.optJSONObject("detail")?.let { value -> value.keys().forEach { key -> if (!value.isNull(key) && value.optString(key).isNotBlank()) detail.put(key, value.get(key)) } }
    if (detail.length() > 0) merged.put("detail", detail)
    if (!incoming.has("toc")) previous?.optJSONObject("toc")?.let { merged.put("toc", it) }
}
