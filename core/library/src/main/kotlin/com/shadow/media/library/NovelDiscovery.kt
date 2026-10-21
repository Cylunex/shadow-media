package com.shadow.media.library

import org.json.JSONObject

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

/** Use the server's filter parameters, never launch its web page or infer a category from a label. */
fun novelSectionQuery(section: JSONObject): NovelQuery {
    val id = section.optString("id")
    return NovelQuery(sort = if (id == "popular") "popularity" else "recent",
        serialStatus = if (id == "completed") "completed" else "",
        availability = if (id in setOf("completed", "shuffle")) "readable" else "",
        categories = if (id.startsWith("category-")) listOf(id.removePrefix("category-")) else emptyList())
}

/** A new snapshot must restart pagination; offsets belong to the token, not a mutable position. */
fun sameNovelRankingSnapshot(previous: JSONObject, next: JSONObject, local: Boolean): Boolean =
    local || previous.optString("token").isNotBlank() && previous.optString("token") == next.optString("token")
