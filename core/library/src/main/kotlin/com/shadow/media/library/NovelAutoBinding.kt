package com.shadow.media.library

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Match identities, never fuzzy titles, aliases, missing authors or the user's search keyword. */
fun sameNovelIdentity(work: JSONObject, source: JSONObject): Boolean {
    fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().replace(Regex("[\\s\\p{Z}]+"), " ").lowercase(Locale.ROOT)
    val title = normalized(work.optString("title"))
    fun authorKey(value: String) = normalized(value).replace(Regex("^作者\\s*[:：]\\s*"), "")
    val author = authorKey(work.optString("author"))
    val unknown = setOf("", "null", "未知", "未知作者", "作者未知", "作者待补全", "暂无", "佚名", "unknown", "anonymous", "无", "无作者", "待定", "待补全", "unknown author", "anon", "n/a", "none")
    return !work.optBoolean("author_unknown") && title.any(Char::isLetterOrDigit) && title !in unknown && author.any(Char::isLetterOrDigit) && author !in unknown &&
        title == normalized(source.optString("name")) && author == authorKey(source.optString("author"))
}

fun novelSourceIdentity(row: JSONObject): String = com.shadow.media.model.scopedContentId(row.optString("provider_id"), row.optString("bookUrl"))

/** Compatible with existing Novel services. Association does not await/download chapter bodies or move progress. */
suspend fun NovelSourceSearch.autoBindMatches(
    connection: CatalogConnection, work: JSONObject, rows: List<JSONObject>, features: NovelSourceFeatures,
    attempted: MutableSet<String>, onBound: (String, JSONObject) -> Unit, onFailure: (String, String) -> Unit,
) {
    val workId = work.novelId("id") ?: return
    for (row in rows) {
        if (row.novelId("provider_id") == null || row.novelId("bookUrl") == null) continue
        val key = novelSourceIdentity(row)
        if (features.autoBind) {
            val marker = row.optJSONObject("auto_bind") ?: continue
            if (marker.optString("status") == "skipped") continue
            try {
                val receipt = automaticReceipt(connection, workId, row)
                if (receipt != null) onBound(key, receipt)
                else onFailure(key, "${marker.optString("reason").ifBlank { "association_failed" }}：重新搜索可重试")
            } catch (e: CancellationException) { attempted.remove(key); throw e }
            catch (e: Exception) { onFailure(key, e.message ?: "无法确认关联结果") }
            continue
        }
        if (!sameNovelIdentity(work, row)) continue
        if (!features.intakeReuse && !readableNovelSource(row)) continue
        if (!attempted.add(key)) continue
        try { onBound(key, collect(connection, row, workId, features = features)) }
        catch (e: CancellationException) { attempted.remove(key); throw e }
        catch (e: Exception) { onFailure(key, e.message ?: "自动关联失败，可重试") }
    }
}
