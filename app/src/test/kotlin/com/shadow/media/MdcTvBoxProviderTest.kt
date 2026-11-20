package com.shadow.media

import com.shadow.media.library.CatalogConnection
import com.shadow.media.library.CatalogKind
import com.shadow.media.model.MediaKey
import com.shadow.media.model.UnifiedMediaItem
import com.shadow.media.model.UnifiedMediaPage
import com.shadow.media.model.UnifiedPlaybackRequest
import com.shadow.media.database.LocalMediaStateRepository
import com.shadow.media.database.MediaHistoryEntity
import com.shadow.media.database.ShadowMediaDatabase
import androidx.room.Room
import kotlinx.coroutines.flow.first
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [28], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class MdcTvBoxProviderTest {
    @Test fun seriesDetailRestoresEpisodeAndLineUsingOnlyVisibleHistoryFromThisSeries() = kotlinx.coroutines.runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ShadowMediaDatabase::class.java).build()
        try {
            val local = LocalMediaStateRepository(db.dao())
            val owner = "mdc-tvbox:one"
            val series = UnifiedMediaItem(MediaKey(owner, "site:film_1"), "剧名", "Series")
            fun episode(id: String, title: String, line: String) =
                UnifiedMediaItem(MediaKey(owner, series.key.itemId + ":" + id), "剧名 · $title", "Episode", subtitle = line)
            val first = episode("a", "第一集", "A")
            val second = episode("a2", "第二集", "A")
            val alternate = episode("b", "第一集", "B")
            val children = listOf(first, second, alternate)
            suspend fun saved(key: MediaKey, time: Long, position: Long = 5000, done: Boolean = false) =
                local.recordHistory(MediaHistoryEntity(key.stableId, key.providerId, key.itemId, "历史标题",
                    positionMs = position, completed = done, lastPlayedAtEpochMs = time))
            suspend fun guide(line: String? = null): TvBoxEpisodeGuide = tvBoxEpisodeGuide(series, children,
                local.episodeHistory(owner, series.key.itemId).first(), line)
            assertEquals(first, guide().target)
            saved(first.key, 100, done = true)
            saved(alternate.key, 200, position = 45000)
            saved(second.key.copy(providerId = "mdc-tvbox:other"), 300)
            saved(MediaKey(owner, "site:filmX1:a2"), 400)
            saved(MediaKey(owner, "site:film_10:a2"), 500)
            saved(MediaKey(owner, "site:film_1:removed"), 600)
            saved(second.key, 700, position = 0)
            val rows = local.episodeHistory(owner, series.key.itemId).first()
            assertEquals(4, rows.size)
            assertEquals("B", guide().line)
            assertEquals(alternate.copy(progressMs = 45000), guide().target)
            assertEquals("继续 第一集 · 0:45", guide().action)
            val selected = guide("A")
            assertEquals(second, selected.target)
            assertEquals("接着看 第二集", selected.action)
            assertTrue(selected.episodes.first().played)
            val duplicateFirst = episode("another-first", "第一集", "A")
            assertEquals(second, tvBoxEpisodeGuide(series, listOf(first, duplicateFirst, second), rows, "A").target)
            assertEquals("B", guide("已移除的线路").line)
            saved(second.key, 800, done = true)
            assertEquals("重播 第二集", guide().action)
            assertEquals(0L, guide().target!!.progressMs)
            assertEquals(null, tvBoxEpisodeGuide(series, emptyList(), rows, null).target)
        } finally {
            db.close()
        }
    }

    @Test fun failedFirstAndSecondResolversFallThroughWithoutReplayingAnAttemptedLine() = runTest {
        MockWebServer().use { server ->
            val provider = MdcTvBoxProvider(MdcMediaProvider(CatalogConnection(name = "MDC", kind = CatalogKind.OPDS,
                url = server.url("/").toString(), allowHttp = true), OkHttpClient()))
            val key = MediaKey(provider.descriptor.id, "site:movie:a")
            server.enqueue(MockResponse().setBody("""{"features":["tvbox_v1"]}"""))
            server.enqueue(MockResponse().setResponseCode(502))
            server.enqueue(MockResponse().setBody("""{"site_id":"site","id":"movie","title":"Movie","episodes":[
                {"id":"a","line":"A","title":"1"},{"id":"b","line":"B","title":"1"},{"id":"c","line":"C","title":"1"}]}"""))
            server.enqueue(MockResponse().setResponseCode(502))
            server.enqueue(MockResponse().setBody("""{"url":"https://example.com/movie.mp4","headers":{},"mime_type":"video/mp4"}"""))
            val result = provider.resolveEpisode(UnifiedPlaybackRequest(key), emptySet(), true)
            assertEquals("site:movie:c", result.key.itemId)
            assertEquals(setOf("site:movie:a", "site:movie:b"), result.failed)
        }
    }

    @Test fun crossSiteSearchPublishesEachStationAndRetainsPartialResults() = kotlinx.coroutines.runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = if ("/bad/" in request.path.orEmpty())
                    MockResponse().setResponseCode(503) else MockResponse().setBody("""{"items":[{"site_id":"good","id":"a","title":"Demo"}],"next_page":2}""")
            }
            val c = CatalogConnection(name = "MDC", kind = CatalogKind.OPDS, url = server.url("/").toString(), allowHttp = true)
            val provider = MdcTvBoxProvider(MdcMediaProvider(c, OkHttpClient()))
            val updates = mutableListOf<UnifiedMediaPage>()
            val page = provider.searchSites(listOf(MdcTvBoxChoice("good","正常站"), MdcTvBoxChoice("bad","离线站")),
                "Demo", "1", updates::add)
            assertEquals(2, updates.size)
            assertEquals(listOf("离线站"), page.failedSources)
            assertEquals("Demo", page.items.single().title)
            assertEquals("2", page.nextPageToken)
        }
    }

    @Test fun paginationKeepsPreviousHitsAndStopsOnRepeatedCursors() {
        fun item(id: String) = UnifiedMediaItem(MediaKey("site", id), id, "Series")
        val pages = TvBoxPages()
        pages.record("1", UnifiedMediaPage(listOf(item("one")), "2"))
        pages.record("2", UnifiedMediaPage(listOf(item("one"), item("two")), "3", failedSources = listOf("slow")))
        assertEquals(listOf(item("one"), item("two")), pages.items)
        assertEquals(listOf("slow"), pages.failed["2"])
        assertFalse(pages.allowAutoLoad)
        pages.record("2", UnifiedMediaPage(listOf(item("three")), "3", failedSources = listOf("other")))
        assertTrue(pages.items.containsAll(listOf(item("one"), item("two"), item("three"))))
        pages.record("2", UnifiedMediaPage(listOf(item("three"), item("two")), "3"))
        assertTrue(pages.failed.isEmpty())
        assertTrue(pages.allowAutoLoad)
        pages.record("3", UnifiedMediaPage(listOf(item("two")), "3"))
        assertEquals(null, pages.next)
        assertFalse(pages.allowAutoLoad)
    }

    @Test fun `warehouse preserves partial failures and authenticated poster metadata`() = runTest {
        MockWebServer().use { server ->
            val connection = CatalogConnection(name = "MDC", kind = CatalogKind.OPDS,
                url = server.url("/mdc/").toString(), allowHttp = true)
            val provider = MdcTvBoxProvider(MdcMediaProvider(connection, OkHttpClient()))
            server.enqueue(MockResponse().setBody("""{"items":[{"site_id":"one","id":"film","title":"Film","image":true,"year":"2024","remarks":"更新至第六集"}],"failures":["two"]}"""))
            val page = provider.searchGroup("film", "1", "")
            assertEquals(listOf("two"), page.failedSources)
            val film = page.items.single()
            assertEquals(2024, film.year)
            assertEquals("更新至第六集", film.subtitle)
            assertEquals(server.url("/mdc/api/media/v1/tvbox/sites/one/items/film/image").toString(), film.posterUrl)
        }
    }

    @Test fun `recovery selects a unique matching episode on another line`() = runTest {
        MockWebServer().use { server ->
            val provider = MdcTvBoxProvider(MdcMediaProvider(CatalogConnection(name = "MDC", kind = CatalogKind.OPDS,
                url = server.url("/").toString(), allowHttp = true), OkHttpClient()))
            val key = com.shadow.media.model.MediaKey(provider.descriptor.id, "one:film:first")
            fun episodes(extra: String = "") = """{"site_id":"one","id":"film","title":"Film","episodes":[{"id":"first","title":"第二集","line":"A"},{"id":"other","title":"第一集","line":"B"},{"id":"backup","title":"第二集","line":"B"}$extra]}"""
            server.enqueue(MockResponse().setBody(episodes()))
            assertEquals("one:film:backup", provider.alternateEpisodes(key).singleOrNull()?.itemId)
            server.enqueue(MockResponse().setBody(episodes(""",{"id":"duplicate","title":"第二集","line":"B"}""")))
            assertTrue(provider.alternateEpisodes(key).isEmpty())
        }
    }

    @Test fun `resolves existing episode after cold start without passing catalog credentials to player`() = runTest {
        MockWebServer().use { server ->
            val connection = CatalogConnection(
                id = "mdc-test", name = "MDC", kind = CatalogKind.OPDS,
                url = server.url("/mdc/").toString(), allowHttp = true, token = "REPLACE_MDC_TOKEN",
            )
            fun provider() = MdcTvBoxProvider(MdcMediaProvider(connection, OkHttpClient()))
            server.enqueue(MockResponse().setBody("""{"features":["tvbox_v1"]}"""))
            assertTrue(provider().supported())
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"station","name":"Demo"}]}"""))
            assertEquals("station", provider().sites().single().id)
            server.enqueue(MockResponse().setBody("""
                {"items":[{"site_id":"station","id":"demo","title":"Demo series"}],"next_page":2}
            """.trimIndent()))
            val page = provider().items("station", "category", "1", "demo")
            assertEquals("2", page.nextPageToken)
            server.enqueue(MockResponse().setBody("""
                {"site_id":"station","id":"demo","title":"Demo series","episodes":[
                    {"id":"episode","title":"First","line":"Primary"}
                ]}
            """.trimIndent()))
            val key = provider().detail(page.items.single().key).children.single().key
            assertEquals("station:demo:episode", key.itemId)
            assertEquals(key, externalHistoryKey(key.providerId, key.itemId))

            server.enqueue(MockResponse().setBody("""{"features":["tvbox_v1"]}"""))
            server.enqueue(MockResponse().setBody("""
                {"url":"https://example.com/lease","mime_type":"application/vnd.apple.mpegurl",
                 "headers":{"Referer":"https://example.com/","Authorization":"discard-me"}}
            """.trimIndent()))
            val stream = provider().resolve(UnifiedPlaybackRequest(key)).single()
            assertEquals("https://example.com/lease", stream.url)
            assertEquals("application/vnd.apple.mpegurl", stream.mimeType)
            assertEquals(mapOf("Referer" to "https://example.com/"), stream.requiredHeaders)
            val requests = List(6) { server.takeRequest() }
            assertTrue(requests.all { it.getHeader("Authorization") == "Bearer REPLACE_MDC_TOKEN" })
            assertEquals("/mdc/api/media/v1/tvbox/sites/station/items/demo/resolve", requests.last().path)
            assertEquals("""{"episode_id":"episode"}""", requests.last().body.readUtf8())
        }
    }

    @Test fun `stream lease keeps the service prefix and uses shared credentials only on that origin`() = runTest {
        MockWebServer().use { server ->
            val base = server.url("/library/services/mdc/")
            val connection = CatalogConnection(name = "MDC", kind = CatalogKind.OPDS,
                url = base.toString(), allowHttp = true)
            val p = MdcTvBoxProvider(MdcMediaProvider(connection, OkHttpClient()) { "REPLACE_SHARED" })
            val key = com.shadow.media.model.MediaKey(p.descriptor.id, "station:film:episode")
            val path = "/api/media/v1/tvbox/stream/${"a".repeat(32)}/${"b".repeat(16)}"
            server.enqueue(MockResponse().setBody("""{"features":["tvbox_v1","tvbox_stream"]}"""))
            server.enqueue(MockResponse().setBody("""{"url":"$path","mime_type":"application/vnd.apple.mpegurl"}"""))
            val candidate = p.resolve(UnifiedPlaybackRequest(key)).single()
            assertEquals(server.url("/library/services/mdc$path").toString(), candidate.url)
            assertEquals(base.toString(), candidate.credentialOrigin)
            assertEquals(mapOf("Authorization" to "Bearer REPLACE_SHARED"), candidate.requiredHeaders)
            server.takeRequest()
            assertEquals("/library/services/mdc/api/media/v1/tvbox/sites/station/items/film/watch",
                server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"url":"https://example.com$path","mime_type":"video/mp4"}"""))
            assertTrue(runCatching { p.resolve(UnifiedPlaybackRequest(key)) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun `warehouse order and scoped search use the existing connection`() = runTest {
        MockWebServer().use { server ->
            val p = MdcTvBoxProvider(MdcMediaProvider(CatalogConnection(name = "MDC", kind = CatalogKind.OPDS,
                url = server.url("/mdc/").toString(), token = "REPLACE_MDC_TOKEN", allowHttp = true), OkHttpClient()))
            server.enqueue(MockResponse().setBody("""{"features":["tvbox_v1","tvbox_groups"]}"""))
            server.enqueue(MockResponse().setBody("""
                {"items":[{"id":"family","name":"Family","site_ids":["site-b","site-a"]}]}
            """.trimIndent()))
            assertEquals(listOf("site-b","site-a"), p.groups().single().sites)
            server.enqueue(MockResponse().setBody("""{"items":[],"next_page":null}"""))
            assertTrue(p.searchGroup("demo", "2", "family").items.isEmpty())
            server.takeRequest()
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals("/mdc/api/media/v1/tvbox/search", request.requestUrl!!.encodedPath)
            assertEquals("family", request.requestUrl!!.queryParameter("group_id"))
            assertEquals("2", request.requestUrl!!.queryParameter("page"))
        }
    }

    @Test fun `capability discovery hides native entry for older MDC deployments`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"features":["catalog","playback_plan"]}"""))
            val connection = CatalogConnection(name = "MDC", kind = CatalogKind.OPDS,
                url = server.url("/mdc/").toString(), allowHttp = true)
            assertFalse(MdcTvBoxProvider(MdcMediaProvider(connection, OkHttpClient())).supported())
        }
    }
}
