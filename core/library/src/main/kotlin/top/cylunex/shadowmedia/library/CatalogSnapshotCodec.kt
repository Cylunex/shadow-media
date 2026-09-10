package top.cylunex.shadowmedia.library

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** Persist display metadata and replayable catalog ids; acquisition URLs and cover leases stay ephemeral. */
internal object CatalogSnapshotCodec {
    private fun stableUrl(value: String): Boolean = runCatching {
        // Native providers use opaque ids and cursors (including "4:20"). Only web
        // references have URL credentials; parsing opaque ids as URIs loses pages.
        if (!value.startsWith("http:", true) && !value.startsWith("https:", true)) return@runCatching true
        val uri = URI(value)
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return@runCatching true
        uri.rawUserInfo == null && uri.fragment == null && uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).all {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase() in setOf("page", "offset", "limit", "size", "search", "query", "sort", "order")
        }
    }.getOrDefault(false)
    fun encode(page: CatalogPage, kind: CatalogKind): String {
        val entries = JSONArray()
        for (entry in page.entries) {
            if (!stableUrl(entry.id)) continue
            if (kind == CatalogKind.OPDS && listOfNotNull(entry.id, entry.locator, entry.navigation).any { !OpdsReferenceStore.isReference(it) }) continue
            if (entry.navigation != null && !stableUrl(entry.navigation)) continue
            entries.put(JSONObject().put("id", entry.id).put("title", entry.title.take(2048)).put("author", entry.author.take(2048))
                .put("format", entry.format).put("navigation", entry.navigation).put("locator", entry.locator))
        }
        return JSONObject().put("title", page.title.take(2048)).put("entries", entries).put("next", page.next?.takeIf { stableUrl(it) && (kind != CatalogKind.OPDS || OpdsReferenceStore.isReference(it)) }).toString()
    }
    fun hasLegacyWebReferences(payload: String): Boolean = runCatching {
        val entries = JSONObject(payload).optJSONArray("entries") ?: return@runCatching false
        (0 until entries.length()).any { index ->
            val entry = entries.getJSONObject(index)
            listOf(entry.optString("id"), entry.optString("navigation")).any { it.startsWith("http", true) } ||
                runCatching { OpdsCatalog.decodeLocator(entry.optString("locator")) }.isSuccess
        } || JSONObject(payload).optString("next").startsWith("http", true)
    }.getOrDefault(false)
    fun decode(payload: String): CatalogPage {
        val json = JSONObject(payload); val entries = json.getJSONArray("entries")
        return CatalogPage(json.getString("title"), (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            CatalogEntry(item.getString("id"), item.getString("title"), item.optString("author"), item.optString("format"),
                navigation = item.optString("navigation").takeIf(String::isNotBlank), locator = item.getString("locator"))
        }, json.optString("next").takeIf(String::isNotBlank), cached = true)
    }
}
