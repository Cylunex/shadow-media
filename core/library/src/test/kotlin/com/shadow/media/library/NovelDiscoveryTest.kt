package com.shadow.media.library

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovelDiscoveryTest {
    private val boards = listOf(
        NovelRankingBoard("a", "来源甲", "hot-month", "月热榜", "month", "科幻", "月热度"),
        NovelRankingBoard("a", "来源甲", "hot-week", "周热榜", "week", "科幻", "周热度"),
        NovelRankingBoard("b", "来源乙", "hot-month", "全榜", "total", "", "来源排名"),
    )
    @Test fun unavailablePeriodsAndDuplicateKeysNeverLeakAcrossSources() {
        val selected = NovelRankingSelection(provider = "b", key = "hot-month", period = "week").resolved(boards)
        assertEquals("total", selected.period)
        assertEquals(listOf("rankings", "b", "hot-month"), selected.request().first)
        val week = NovelRankingSelection(provider = "a", key = "hot-month", period = "week").resolved(boards)
        assertEquals("hot-week", week.key)
    }
    @Test fun libraryRankingsOnlyRequestTheTotalPeriodAndPassCategoryAndPagination() {
        val selected = NovelRankingSelection(kind = "rating", period = "day", category = "science").resolved(boards)
        val (path, query) = selected.request(30, 30)
        assertEquals(listOf("rankings", "local"), path)
        assertEquals(mapOf("kind" to "rating", "period" to "total", "category_id" to "science", "offset" to "30", "limit" to "30"), query.toMap())
    }
    @Test fun changedOrMissingSnapshotCannotAppendExternalRankPositions() {
        val first = JSONObject().put("token", "a".repeat(64))
        assertTrue(sameNovelRankingSnapshot(first, JSONObject(first.toString()), false))
        assertFalse(sameNovelRankingSnapshot(first, JSONObject().put("token", "b".repeat(64)), false))
        assertFalse(sameNovelRankingSnapshot(JSONObject(), JSONObject(), false))
        assertTrue(sameNovelRankingSnapshot(JSONObject(), JSONObject(), true))
    }
    @Test fun serverCapabilityParsingDropsUnsupportedPeriodsAndKeepsSourceIdentity() {
        val index = JSONObject().put("boards", JSONArray().put(JSONObject().put("provider_id", "a").put("key", "weekly").put("period", "week"))
            .put(JSONObject().put("provider_id", "a").put("key", "fake").put("period", "year")))
        assertEquals(listOf("weekly"), NovelRankingBoard.parse(index).map { it.key })
    }
    @Test fun sectionFiltersUseNativeParametersAndKeepCategoryIdentifiersIntact() {
        assertEquals(listOf("science-fiction"), novelSectionQuery(JSONObject().put("id", "category-science-fiction")).categories)
        val completed = novelSectionQuery(JSONObject().put("id", "completed"))
        assertEquals("completed", completed.serialStatus)
        assertEquals("readable", completed.availability)
        assertEquals("popularity", novelSectionQuery(JSONObject().put("id", "popular")).sort)
    }
}
