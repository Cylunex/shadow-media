package com.shadow.media.library

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovelContentsTest {
    @Test fun contentsSearchUsesServerContractWithVersionPrefixAndLiteralCharacters() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"version_id":"v1","offset":100,"limit":100,"total":350,"next_offset":200,"latest":{"id":"last","ordinal":349,"readable":true},"items":[{"id":"c200","ordinal":200,"readable":true}]}"""))
            val connection = CatalogConnection(name = "test", kind = CatalogKind.OPDS, url = server.url("/novel/").toString(), allowHttp = true)
            val query = NovelContentsQuery("v1", "100%_ 星河", true, 100)
            val page = NovelApi().json(connection, listOf("works", "book", "contents"), query.parameters())
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/novel/api/v1/works/book/contents", request.requestUrl!!.encodedPath)
            assertEquals("100%_ 星河", request.requestUrl!!.queryParameter("q"))
            assertEquals("desc", request.requestUrl!!.queryParameter("order"))
            assertEquals("v1", request.requestUrl!!.queryParameter("version_id"))
            assertEquals(200, novelContentsNext(page))
            assertEquals("last", page.getJSONObject("latest").getString("id"))
            assertEquals(1, server.requestCount) // Browsing cannot download chapters or write progress.
        } finally { server.shutdown() }
    }

    @Test fun nullNextOffsetWinsAndMalformedOffsetsCannotLoop() {
        val page = JSONObject().put("offset", 100).put("total", 1000).put("items", JSONArray().put(JSONObject().put("id", "chapter")))
        assertEquals(101, novelContentsNext(page))
        page.put("next_offset", JSONObject.NULL)
        assertNull(novelContentsNext(page))
        page.put("next_offset", 100)
        assertNull(novelContentsNext(page))
        page.put("next_offset", 1000)
        assertNull(novelContentsNext(page))
    }

    @Test fun searchSortAndCurrentChapterHaveSeparateOffsetSemantics() {
        val original = NovelContentsQuery("version", offset = 400)
        val searched = original.search("  山海  ")
        assertEquals(0, searched.offset)
        assertEquals("山海", searched.q)
        assertTrue(searched.reverse().descending)
        assertEquals(NovelContentsQuery("version", offset = 1200), searched.reverse().locate(1234))
    }

    @Test fun offlineSearchCoversAllDownloadedWindowsAndNeverInventsLastChapter() {
        val chapters = listOf(3, 500, 501, 900).map { JSONObject().put("id", "c$it").put("ordinal", it).put("title", "第$it 章 山海").put("readable", true) }
        val page = novelOfflineContents(chapters + chapters.first(), NovelContentsQuery("v1", "山海", true, 1200, 2))
        assertEquals(4, page.getInt("total"))
        assertEquals(2, page.getInt("offset"))
        assertEquals(listOf(500, 3), page.novelObjects("items").map { it.getInt("ordinal") })
        assertTrue(page.getBoolean("_downloaded_only"))
        assertTrue(page.isNull("latest"))
        assertNull(novelContentsNext(page))
    }

    @Test fun updateFilterDoesNotMeanNoUpdatesWhenTurnedOff() {
        assertFalse(NovelQuery(view = "shelf").parameters().any { it.first == "has_updates" })
        assertTrue(NovelQuery(view = "shelf", hasUpdates = true).parameters().contains("has_updates" to "true"))
    }

    @Test fun sectionUsesServerFiltersAndKeepsRepeatedCategoriesAndTags() {
        val section = JSONObject().put("id", "popular").put("filter_link", "/novel/?view=city&city_tab=all&category_id=sci-fi&genre_tag=群像&genre_tag=冒险&exclude_genre_tag=悲剧&tag_mode=all&availability=readable&sort=popularity&min_popularity=15")
        val query = novelSectionQuery(section)
        assertEquals(listOf("sci-fi"), query.categories)
        assertEquals(listOf("群像", "冒险"), query.genres)
        assertEquals(listOf("悲剧"), query.excluded)
        assertEquals("readable", query.availability)
        assertEquals("15", query.minPopularity)
        assertTrue(query.allGenres)
        assertEquals("0", novelSectionQuery(JSONObject().put("id", "popular")).minPopularity)
    }
}
