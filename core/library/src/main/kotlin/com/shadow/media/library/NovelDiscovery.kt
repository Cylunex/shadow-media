package com.shadow.media.library

import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.Normalizer

/** Source identity and period are kept separate: an external week is never synthesized locally. */
data class NovelRankingBoard(
    val providerId: String, val providerName: String, val key: String, val name: String,
    val period: String, val category: String, val metric: String,
) {
    companion object {
        fun parse(index: JSONObject): List<NovelRankingBoard> = index.novelObjects("boards").mapNotNull {
            val provider = it.optString("provider_id")
            val key = it.optString("key")
            val period = it.optString("period")
            if (provider.isBlank() || key.isBlank() || period !in NOVEL_RANKING_PERIODS) null
            else NovelRankingBoard(provider, it.optString("provider_name"), key, it.optString("name"), period,
                it.optString("category"), it.optString("metric"))
        }.distinctBy { it.providerId to it.key }
    }
}

val NOVEL_RANKING_PERIODS = linkedMapOf("total" to "总榜", "month" to "月榜", "week" to "周榜", "day" to "日榜")
val NOVEL_LOCAL_RANKINGS = linkedMapOf("heat" to "书库热榜", "recent" to "最近收录", "completed" to "完结可读", "rating" to "我的高分")

data class NovelRankingEntry(
    val providerId: String, val providerName: String, val key: String, val name: String,
    val periodLabel: String, val paginated: Boolean, val cacheStatus: String, val refreshFailed: Boolean,
) {
    companion object {
        fun from(row: JSONObject): NovelRankingEntry? {
            val provider = row.novelId("provider_id") ?: return null
            val key = row.novelId("key") ?: return null
            val name = row.novelId("name") ?: return null
            val label = novelRankingPeriodLabel(row)
            return NovelRankingEntry(provider, row.novelId("provider_name") ?: provider, key, name, label, row.optBoolean("paginated"),
                row.optString("cache_status"), row.optBoolean("refresh_failed"))
        }
    }
}

data class NovelRankingEntries(val items: List<NovelRankingEntry>, val nextOffset: Int?) {
    companion object {
        fun from(json: JSONObject, index: Boolean = false): NovelRankingEntries {
            val items = json.novelObjects(if (index) "source_entries" else "items").mapNotNull(NovelRankingEntry::from)
            val next = json.optInt(if (index) "source_entries_next_offset" else "next_offset", -1)
            return NovelRankingEntries(items.distinctBy { it.providerId to it.key }, next.takeIf { it > json.optInt("offset", 0) })
        }
    }
}

fun novelRankingPeriodLabel(row: JSONObject): String =
    row.novelId("period_label") ?: NOVEL_RANKING_PERIODS[row.novelId("period")].orEmpty()

class NovelDiscoveryQuery(query: String) {
    private val terms = fold(query).trim().split(whitespace).filter(String::isNotBlank)

    fun matches(vararg fields: String): Boolean {
        if (terms.isEmpty()) return true
        val text = fold(fields.joinToString(" "))
        return terms.all { term ->
            text.contains(term, ignoreCase = true) ||
                (term in completedTerms && completedTerms.any(text::contains))
        }
    }

    private companion object {
        val whitespace = Regex("\\s+")
        val completedTerms = setOf("完本", "全本", "完结")
        fun fold(value: String) = SearchText.fold(Normalizer.normalize(value, Normalizer.Form.NFKC))
    }
}

data class NovelRankingSelection(
    val provider: String = "local", val kind: String = "heat", val key: String = "",
    val period: String = "total", val category: String = "",
) {
    val local get() = provider == "local"
    fun resolved(boards: List<NovelRankingBoard>): NovelRankingSelection {
        if (local) return copy(kind = kind.takeIf { it in NOVEL_LOCAL_RANKINGS } ?: "heat", period = "total", key = "")
        val candidates = boards.filter { it.providerId == provider }
        val board = candidates.firstOrNull { it.key == key && it.period == period }
            ?: candidates.firstOrNull { it.period == period } ?: candidates.firstOrNull()
        return copy(key = board?.key.orEmpty(), period = board?.period ?: "total", category = "")
    }
    fun request(offset: Int = 0, limit: Int = 30): Pair<List<String>, List<Pair<String, String>>> {
        require(offset >= 0 && limit in 1..100)
        val page = listOf("offset" to offset.toString(), "limit" to limit.toString())
        return if (local) listOf("rankings", "local") to
            (listOf("kind" to kind, "period" to "total", "category_id" to category) + page)
        else {
            require(provider.isNotBlank() && key.isNotBlank()) { "此来源没有可用榜单" }
            listOf("rankings", provider, key) to page
        }
    }
}

/** A response belongs to the selection that fetched it, including during source-switch composition. */
data class NovelRankingPage(val selection: NovelRankingSelection, val data: JSONObject) {
    fun forSelection(current: NovelRankingSelection): JSONObject? = data.takeIf { selection == current }
}

/** Use the server's filter parameters, never launch its web page or infer a category from a label. */
fun novelSectionQuery(section: JSONObject): NovelQuery {
    val id = section.optString("id")
    val fallback = NovelQuery(sort = if (id == "popular") "popularity" else "recent",
        minPopularity = if (id == "popular") "0" else "",
        serialStatus = if (id == "completed") "completed" else "",
        availability = if (id in setOf("completed", "shuffle")) "readable" else "",
        categories = if (id.startsWith("category-")) listOf(id.removePrefix("category-")) else emptyList())
    // Extract only known filter values. Never request, navigate to, or retain the supplied URL.
    val link = section.optString("filter_link").takeIf { it.contains('?') } ?: return fallback
    val url = "https://example.com/".toHttpUrl().resolve(link) ?: return fallback
    fun values(name: String) = url.queryParameterValues(name).filterNotNull().filter(String::isNotBlank).distinct()
    return fallback.copy(q = url.queryParameter("q").orEmpty().take(512),
        sort = url.queryParameter("sort")?.takeIf { it in setOf("recent", "title", "popularity") } ?: fallback.sort,
        categories = values("category_id").ifEmpty { fallback.categories }, genres = values("genre_tag"), excluded = values("exclude_genre_tag"),
        allGenres = url.queryParameter("tag_mode") == "all",
        serialStatus = url.queryParameter("serial_status")?.takeIf { it in setOf("ongoing", "completed", "hiatus", "unknown") } ?: fallback.serialStatus,
        availability = url.queryParameter("availability")?.takeIf { it in setOf("readable", "local", "online", "metadata_only") } ?: fallback.availability,
        minPopularity = url.queryParameter("min_popularity")?.takeIf { raw -> raw.toDoubleOrNull()?.let { it in 0.0..100.0 } == true } ?: fallback.minPopularity)
}

/** A new snapshot must restart pagination; offsets belong to the token, not a mutable position. */
fun sameNovelRankingSnapshot(previous: JSONObject, next: JSONObject, local: Boolean): Boolean =
    local || previous.optString("token").isNotBlank() && previous.optString("token") == next.optString("token")
