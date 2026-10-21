package top.cylunex.shadowmedia.library

import androidx.room.Room
import androidx.room.withTransaction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.cylunex.shadowmedia.database.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NovelSyncTest {
    private fun position(chapter: String, ratio: Double) = JSONObject().put("version_id", "v1").put("chapter_id", chapter).put("ratio", ratio).put("anchor", JSONObject()).toString()
    private fun state(c: CatalogConnection) = NovelReadingStateEntity(NovelRepository.stateId(novelScope(c), "work"), novelScope(c), "work", "device", localSequence = 1, localPosition = position("chapter-1", 0.2))
    private fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ShadowMediaDatabase::class.java).allowMainThreadQueries().build()
    private fun connection(server: MockWebServer) = CatalogConnection(name = "test", kind = CatalogKind.OPDS, url = server.url("/").toString(), allowHttp = true)

    @Test fun realServerPackageInstallsItsTitleAndPartialEndNeverCompletesTheBook() = runBlocking {
        val root = System.getenv("NOVEL_VALIDATION_URL").orEmpty()
        assumeTrue("Set NOVEL_VALIDATION_URL to an isolated server", root.isNotBlank())
        val context = RuntimeEnvironment.getApplication()
        val c = CatalogConnection(name = "validation", kind = CatalogKind.OPDS, url = root, allowHttp = true)
        val api = NovelApi(); val library = LibraryRepository(context); val repository = NovelRepository(context, library, api)
        val work = api.json(c, listOf("works"), listOf("view" to "search", "limit" to "1")).novelObjects("items").first()
        val id = work.getString("id")
        val before = api.json(c, listOf("me", "progress", id)).toString()
        val prepared = repository.prepare(c, id)
        assertEquals(work.getString("title"), prepared.asset.title)
        assertEquals(work.getString("author"), prepared.asset.author)
        val publication = repository.dao.publication(prepared.asset.id)!!
        val mapping = NovelPublicationMap(JSONObject(publication.mappingJson))
        assertFalse(mapping.complete)
        assertEquals(mapping.chapters.size, repository.cachedContents(publication).size)
        assertNotNull(repository.cachedChapter(publication.scope, id, publication.versionId, mapping.chapters.first().getString("id")))
        assertNull(repository.cachedChapter("novel:another-account", id, publication.versionId, mapping.chapters.first().getString("id")))
        assertTrue(library.publicationFile(prepared.asset).isFile)
        val stateId = NovelRepository.stateId(novelScope(c), id)
        assertFalse(repository.dao.state(stateId)!!.pending)
        val last = mapping.chapters.last()
        val session = ProgressWriter.begin(library, prepared.asset)
        ProgressWriter.save(library, session, "text", JSONObject().put("href", last.getString("href")).put("locations", JSONObject().put("progression", 1.0).put("totalProgression", 1.0)), 1.0, completed = true)
        ProgressWriter.flush()
        assertFalse(library.dao.progress(prepared.asset.id)!!.completed)
        assertTrue(repository.dao.state(stateId)!!.pending)
        assertEquals(last.getString("id"), JSONObject(repository.dao.state(stateId)!!.localPosition).getString("chapter_id"))
        assertEquals(before, api.json(c, listOf("me", "progress", id)).toString())
        // A following chapter can fail independently. The first readable chapter must still install.
        val one = java.io.File.createTempFile("novel-one-", ".epub")
        val simulated = MockWebServer()
        try {
            val version = publication.versionId
            val manifestOne = api.json(c, listOf("works", id, "reading-manifest"), listOf("version_id" to version, "limit" to "1"))
            val manifestThree = api.json(c, listOf("works", id, "reading-manifest"), listOf("version_id" to version, "limit" to "3"))
            val open = api.json(c, listOf("works", id, "open"), listOf("version_id" to version))
            api.download(c, listOf("works", id, "publication.epub"), one, listOf("version_id" to version, "limit" to "1"))
            val broken = mapping.chapters[1].getString("id")
            simulated.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.contains("/open") -> MockResponse().setBody(open.toString())
                    request.path!!.contains("reading-manifest") -> MockResponse().setBody(if (request.requestUrl!!.queryParameter("limit") == "1") manifestOne.toString() else manifestThree.toString())
                    request.path!!.contains("publication.epub") && request.requestUrl!!.queryParameter("limit") == "1" -> MockResponse().setBody(okio.Buffer().write(one.readBytes()))
                    request.path!!.contains("publication.epub") -> MockResponse().setResponseCode(409).setBody(JSONObject().put("detail", JSONObject().put("code", "publication_not_cached").put("missing_chapter_ids", org.json.JSONArray().put(broken))).toString())
                    else -> MockResponse().setResponseCode(503).setBody("{}")
                }
            }
            simulated.start()
            val simulatedConnection = connection(simulated)
            val fallback = repository.prepare(simulatedConnection, id, version)
            val fallbackMap = NovelPublicationMap(JSONObject(repository.dao.publication(fallback.asset.id)!!.mappingJson))
            assertEquals(1, fallbackMap.chapters.size); assertEquals(work.getString("title"), fallback.asset.title)
        } finally { one.delete(); simulated.shutdown() }
    }

    @Test fun retryUsesByteIdenticalFrozenRequestAndScopeIsNotReassigned() = runBlocking {
        val server = MockWebServer(); server.start(); val db = database()
        try {
            val c = connection(server); val initial = state(c); db.novelDao().putState(initial)
            var active = listOf(c)
            val repository = NovelRepository(RuntimeEnvironment.getApplication(), api = NovelApi(), database = db, connectionLoader = { active })
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            assertFalse(repository.sync())
            val first = server.takeRequest().body.readUtf8()
            db.novelDao().putState(db.novelDao().state(initial.id)!!.copy(localSequence = 2, localPosition = position("chapter-2", 0.7)))
            active = listOf(c.copy(id = "new-account", token = "REPLACE_TOKEN"))
            assertTrue(repository.sync()); assertEquals(1, server.requestCount)
            active = listOf(c)
            server.enqueue(MockResponse().setBody("{\"revision\":1}")); server.enqueue(MockResponse().setBody("{\"revision\":2}"))
            assertTrue(repository.sync())
            val retry = server.takeRequest().body.readUtf8(); val latest = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(first, retry); assertEquals("chapter-2", latest.getString("chapter_id"))
            assertEquals(1, latest.getInt("expected_revision")); assertEquals(2, latest.getInt("client_seq"))
            assertFalse(db.novelDao().state(initial.id)!!.pending)
        } finally { db.close(); server.shutdown() }
    }
    @Test fun aNewLocatorArrivingWhileRequestIsInFlightSurvivesItsReceipt() = runBlocking {
        val server = MockWebServer(); val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.clone().readUtf8())
                if (body.getInt("client_seq") == 1) check(release.await(5, TimeUnit.SECONDS))
                return MockResponse().setBody("{\"revision\":${body.getInt("client_seq")}}")
            }
        }
        server.start(); val db = database()
        try {
            val c = connection(server); val initial = state(c); db.novelDao().putState(initial)
            val repository = NovelRepository(RuntimeEnvironment.getApplication(), api = NovelApi(), database = db, connectionLoader = { listOf(c) })
            val sync = async(Dispatchers.IO) { repository.sync() }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            db.withTransaction { val current = db.novelDao().state(initial.id)!!; db.novelDao().putState(current.copy(localSequence = 2, localPosition = position("chapter-2", 0.8))) }
            release.countDown(); assertTrue(sync.await())
            val second = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals("chapter-2", second.getString("chapter_id")); assertEquals(1, second.getInt("expected_revision"))
            val final = db.novelDao().state(initial.id)!!
            assertEquals(2L, final.revision); assertEquals(2L, final.acknowledgedSequence); assertFalse(final.pending)
            assertEquals("chapter-2", JSONObject(final.localPosition).getString("chapter_id"))
        } finally { release.countDown(); db.close(); server.shutdown() }
    }
    @Test fun conflictPreservesLocalLocatorAndFrozenRequestAndPausesWrites() = runBlocking {
        val server = MockWebServer(); server.start(); val db = database()
        try {
            val c = connection(server); val initial = state(c); db.novelDao().putState(initial)
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":{"code":"progress_conflict","current":{"version_id":"v1","chapter_id":"remote","ratio":0.6,"revision":8}}}"""))
            val repository = NovelRepository(RuntimeEnvironment.getApplication(), api = NovelApi(), database = db, connectionLoader = { listOf(c) })
            assertTrue(repository.sync()); assertTrue(repository.sync())
            val conflict = db.novelDao().state(initial.id)!!
            assertTrue(conflict.pending); assertEquals(initial.localPosition, conflict.localPosition); assertTrue(conflict.requestJson.isNotBlank())
            assertEquals(8, JSONObject(conflict.conflictJson).getJSONObject("current").getInt("revision")); assertEquals(1, server.requestCount)
        } finally { db.close(); server.shutdown() }
    }
    @Test fun nativeReaderCharacterAnchorIsWrittenAtomicallyAndSyncedWithoutWindowCoordinates() = runBlocking {
        val server = MockWebServer(); server.start()
        val context = RuntimeEnvironment.getApplication()
        val db = ShadowMediaDatabase.create(context)
        val library = LibraryRepository(context)
        try {
            val c = connection(server)
            val scope = novelScope(c)
            val id = LibraryRepository.digest("native-anchor-sync")
            val asset = LibraryAssetEntity(id, scope, "native-test", "原生阅读测试", kind = "BOOK", format = "epub", revision = "r1", addedAt = 0)
            library.dao.putAsset(asset)
            val mapping = NovelPublicationMap(JSONObject("""{
                "schema":"shadow.novel.publication/v1","work_id":"native-work","version_id":"v1","complete":false,"version_complete":true,
                "chapters":[{"id":"chapter-1","title":"第一章","ordinal":12,"href":"chapters/chapter-1.xhtml","body_hash":"h1"}]
            }"""))
            db.novelDao().putPublication(NovelPublicationEntity(id, scope, "native-work", "v1", mapping.json.toString(), "r1"))
            val initial = NovelReadingStateEntity(NovelRepository.stateId(scope, "native-work"), scope, "native-work", "native-device")
            db.novelDao().putState(initial)
            val session = ProgressWriter.begin(library, asset)
            assertFalse(db.novelDao().state(initial.id)!!.pending)
            val native = JSONObject().put("schema", 1).put("chapter_id", "chapter-1").put("version_id", "v1").put("offset", 537).put("text_hash", "a".repeat(64))
            val locator = JSONObject().put("href", "chapters/chapter-1.xhtml").put("locations", JSONObject().put("progression", .37)).put("native_text", native)
            ProgressWriter.save(library, session, "text", locator, .1)
            ProgressWriter.flush()
            val local = JSONObject(library.dao.progress(id)!!.locatorJson)
            assertEquals(537, local.getJSONObject("native_text").getInt("offset"))
            val pending = db.novelDao().state(initial.id)!!
            assertTrue(pending.pending)
            assertEquals(537, mapping.locator(JSONObject(pending.localPosition))!!.getJSONObject("native_text").getInt("offset"))
            server.enqueue(MockResponse().setBody("{\"revision\":1}"))
            val repository = NovelRepository(context, library, database = db, connectionLoader = { listOf(c) })
            assertTrue(repository.sync())
            val sent = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("chapter-1", sent.getString("chapter_id"))
            assertEquals(.37, sent.getDouble("ratio"), .00001)
            val media = sent.getJSONObject("anchor").getJSONObject("media_locator")
            assertEquals(537, media.getJSONObject("native_text").getInt("offset"))
            assertFalse(media.getJSONObject("locations").has("totalProgression"))
            assertFalse(db.novelDao().state(initial.id)!!.pending)
        } finally { server.shutdown() }
    }
}
