package com.shadow.media.library

import com.shadow.media.database.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real immutable EPUBs and HTTP requests exercise cache/session separation without a live service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NovelReadingCacheTest {
    private class Service(val total: Int = 8, val volumes: Set<Int> = emptySet()) : AutoCloseable {
        val server = MockWebServer()
        val cached = mutableSetOf<Int>()
        val broken = mutableSetOf<Int>()
        val badXml = mutableSetOf<Int>()
        val requests = CopyOnWriteArrayList<String>()
        val progressBodies = CopyOnWriteArrayList<String>()
        var canonical = "work"
        var detail = "work"
        var badVersion = false
        var packageVersion: String? = null
        var complete = true
        var cacheJobs = false
        var jobVersion = "v1"
        var jobChapters = emptyList<String>()
        val c: CatalogConnection
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = requireNotNull(request.requestUrl)
                    val path = url.encodedPath.removePrefix("/api/v1/")
                    requests += "${request.method} $path"
                    val version = url.queryParameter("version_id") ?: "v1"
                    val offset = url.queryParameter("offset")?.toInt() ?: 0
                    val limit = url.queryParameter("limit")?.toInt() ?: 3
                    fun json(value: JSONObject) = MockResponse().setBody(value.toString())
                    return when {
                        path == "integration" -> json(JSONObject().put("capabilities", JSONObject().put("reading_cache_jobs", cacheJobs)))
                        path == "works/work/cache-jobs" && request.method == "POST" -> {
                            val body = JSONObject(request.body.clone().readUtf8())
                            jobChapters = body.novelStrings("chapter_ids")
                            json(JSONObject().put("job_id", "cache-job").put("work_id", "work").put("version_id", "v1").put("total", jobChapters.size))
                        }
                        path == "jobs/cache-job" -> {
                            cached += jobChapters.map { it.removePrefix("c").toInt() }
                            json(JSONObject().put("kind", "reading_cache").put("state", "succeeded")
                                .put("payload", JSONObject().put("work_id", "work").put("version_id", jobVersion).put("chapter_ids", JSONArray(jobChapters)))
                                .put("result", JSONObject().put("version_id", jobVersion).put("total", jobChapters.size).put("completed", jobChapters.size).put("failed", 0)
                                    .put("items", JSONArray(jobChapters.map { JSONObject().put("chapter_id", it).put("state", "succeeded") }))))
                        }
                        path == "me/progress/work" && request.method == "PUT" -> {
                            progressBodies += request.body.clone().readUtf8()
                            json(JSONObject().put("work_id", canonical).put("revision", 14))
                        }
                        request.method != "GET" -> MockResponse().setResponseCode(400)
                        path == "works/work" -> json(JSONObject().put("id", detail))
                        path.endsWith("/open") -> json(JSONObject().put("version_id", version).put("quality", "exact").put("revision", 0)
                            .put("first_chapter_id", "c0").put("position", JSONObject.NULL)
                            .put("chapters", JSONArray((0 until total).map(::chapter))))
                        path.endsWith("/reading-manifest") -> json(manifest(version, offset, limit))
                        path.startsWith("chapters/") -> {
                            val n = path.substringAfter("chapters/c").toInt()
                            if (n in broken || n in volumes) MockResponse().setResponseCode(503).setBody("{}")
                            else { cached += n; json(JSONObject().put("id", "c$n").put("version_id", version).put("content", "正文$n")) }
                        }
                        path.endsWith("/publication.epub") -> {
                            val missing = (offset until minOf(offset + limit, total)).filter { it !in volumes && it !in cached }
                            if (missing.isNotEmpty()) MockResponse().setResponseCode(409).setBody(JSONObject().put("detail", JSONObject()
                                .put("code", "publication_not_cached").put("missing_chapter_ids", JSONArray(missing.map { "c$it" }))).toString())
                            else MockResponse().setBody(okio.Buffer().write(epub(packageVersion ?: version, offset, limit)))
                        }
                        path.startsWith("me/progress/") -> json(JSONObject().put("revision", 14))
                        else -> MockResponse().setResponseCode(404).setBody("{}")
                    }
                }
            }
            server.start()
            c = CatalogConnection(name = "isolated", kind = CatalogKind.OPDS, url = server.url("/").toString(), allowHttp = true)
        }
        private fun chapter(n: Int) = JSONObject().put("id", "c$n").put("title", "第${n + 1}章").put("ordinal", n)
            .put("readable", n !in volumes).put("cached", n in cached).put("href", if (n in volumes) JSONObject.NULL else "chapters/c$n.xhtml").put("body_hash", "h$n")
        fun manifest(version: String, offset: Int, limit: Int) = JSONObject().put("schema", "shadow.novel.reading/v1").put("work_id", canonical)
            .put("version_id", if (badVersion) "another-version" else version).put("title", "缓存测试").put("version_complete", complete)
            .put("total", total).put("readable_total", total - volumes.size).put("offset", offset).put("limit", limit)
            .put("next_offset", (offset + limit).takeIf { it < total } ?: JSONObject.NULL)
            .put("items", JSONArray((offset until minOf(offset + limit, total)).map(::chapter)))
        private fun epub(version: String, offset: Int, limit: Int): ByteArray {
            val entries = (offset until minOf(offset + limit, total)).filterNot { it in volumes }
            val map = JSONObject().put("schema", "shadow.novel.publication/v1").put("work_id", canonical).put("version_id", version)
                .put("offset", offset).put("limit", limit).put("total", total).put("next_offset", (offset + limit).takeIf { it < total } ?: JSONObject.NULL)
                .put("complete", offset == 0 && offset + limit >= total).put("version_complete", complete)
                .put("chapters", JSONArray(entries.map { chapter(it).apply { remove("readable"); remove("cached") } }))
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                fun entry(name: String, value: String) { zip.putNextEntry(ZipEntry(name).apply { time = 0 }); zip.write(value.toByteArray()); zip.closeEntry() }
                entry("mimetype", "application/epub+zip")
                entry("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""")
                entry("book.opf", """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>缓存测试</dc:title></metadata><manifest/><spine/></package>""")
                entry("shadow-novel.json", map.toString())
                for (n in entries) entry("chapters/c$n.xhtml", if (n in badXml) "<html><body><p>malformed" else """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>第${n + 1}章</p><p>正文$n。</p></body></html>""")
            }
            return bytes.toByteArray()
        }
        override fun close() { server.shutdown() }
    }
    private fun repository(service: Service): NovelRepository = NovelRepository(RuntimeEnvironment.getApplication(), api = NovelApi(), connectionLoader = { listOf(service.c) })

    @Test fun shelfResumeUsesNewestWindowAndSourceWithoutDiscardingOtherWindows() = runBlocking {
        Service().use { service ->
            val repository = repository(service)
            val first = repository.cache.window(service.c, "work", "v1", 0, 1)
            service.cached += 4
            val latest = repository.cache.window(service.c, "work", "v2", 4, 1)
            val position = JSONObject().put("asset_id", latest.asset.id).put("version_id", "v2").put("chapter_id", "c4").put("ratio", .6)
            repository.dao.putState(NovelReadingStateEntity(NovelRepository.stateId(novelScope(service.c), "work"), novelScope(service.c), "work", "device", localPosition = position.toString()))
            val resumed = repository.resumeCached(first.publication)!!
            assertEquals(latest.asset.id, resumed.asset.id)
            assertEquals("chapters/c4.xhtml", JSONObject(resumed.locator!!).getString("href"))
            val library = LibraryRepository(RuntimeEnvironment.getApplication())
            val windows = setOf(first.asset.id, latest.asset.id)
            assertEquals(1, library.dao.searchPage("BOOK", "%缓存测试%", 0, 1000).count { it.id in windows })
            assertEquals(2, repository.dao.publications(novelScope(service.c)).size)
        }
    }

    @Test fun enteringAChapterPrefetchesThreeReadableChaptersWithoutTouchingFrozenProgress() = runBlocking {
        Service(volumes = setOf(1)).use { service ->
            val repository = repository(service)
            val current = repository.cache.window(service.c, "work", "v1", 0, 1)
            val state = NovelReadingStateEntity(NovelRepository.stateId(novelScope(service.c), "work"), novelScope(service.c), "work", "device",
                revision = 9, localSequence = 7, localPosition = """{"version_id":"v1","asset_id":"${current.asset.id}","chapter_id":"c0","ratio":0.4}""", requestJson = """{"mutation_id":"frozen"}""", requestSequence = 6)
            repository.dao.putState(state)
            repository.cache.prefetch(service.c, current.publication, ordinal = 0)
            assertEquals(setOf(0, 2, 3, 4), service.cached)
            assertTrue(repository.cachedContents(current.publication).all { it.getBoolean("readable") })
            assertEquals(state, repository.dao.state(state.id))
            assertNotNull(repository.cachedChapter(novelScope(service.c), "work", "v1", "c4"))
            val restarted = repository(service)
            val beforeAdjacency = service.requests.size
            assertEquals("c2", restarted.cache.adjacent(novelScope(service.c), "work", "v1", 0, 1)!!.chapter!!.getString("id"))
            assertEquals(beforeAdjacency, service.requests.size)
            assertNull(repository.cachedChapter("novel:another-account", "work", "v1", "c4"))
            assertEquals(1, repository.downloaded(novelScope(service.c)).size)
            val count = service.requests.count { it.contains("publication.epub") }
            repository.cache.prefetch(service.c, current.publication, ordinal = 0)
            assertEquals(count, service.requests.count { it.contains("publication.epub") })
            assertTrue(service.requests.all { it.startsWith("GET ") })
            assertFalse(service.requests.any { it.contains("/open") || it.contains("progress/") })
        }
    }

    @Test fun fullBookFailureIsIsolatedAndRestartDownloadsOnlyMissingChapters() = runBlocking {
        Service(total = 25, volumes = setOf(10)).use { service ->
            val repository = repository(service)
            service.broken += 4
            val initial = NovelDownloadTask(novelScope(service.c), "work", "v1", "书名")
            val first = downloadNovelContents(repository, service.c, initial) {}
            assertEquals("partial", first.state); assertEquals(24, first.total); assertEquals(23, first.completed); assertEquals(1, first.failed)
            assertNull(repository.dao.state(NovelRepository.stateId(initial.scope, initial.workId)))
            val before = service.requests.count { it == "GET chapters/c24" }
            service.broken.clear()
            val restarted = repository(service)
            val last = downloadNovelContents(restarted, service.c, first.copy(operation = "retry")) {}
            assertEquals("completed", last.state); assertEquals(24, last.completed); assertEquals(0, last.failed)
            assertEquals(before, service.requests.count { it == "GET chapters/c24" })
            assertNotNull(restarted.cache.savedDirectory(initial.scope, "work", "v1"))
            assertTrue(service.requests.none { it.contains("progress/") || it.endsWith("/open") })
        }
    }

    @Test fun selectedRangeAndIncompleteSourceDoNotClaimACompleteBook() = runBlocking {
        Service(total = 12, volumes = setOf(3)).use { service ->
            service.complete = false
            val repository = repository(service)
            val task = downloadNovelContents(repository, service.c, NovelDownloadTask(novelScope(service.c), "work", "v1", "test", start = 2, end = 7)) {}
            assertEquals(setOf(2, 4, 5, 6), service.cached)
            assertFalse(task.fullBook); assertFalse(task.versionComplete); assertEquals(4, task.completed)
            val snapshot = repository.cache.savedDirectory(task.scope, task.workId, task.versionId)!!
            val page = novelSavedDirectoryContents(snapshot, NovelContentsQuery("v1", q = "第12章"))
            assertEquals(1, page.getInt("total")); assertTrue(page.getBoolean("_directory_cached")); assertFalse(page.has("_downloaded_only"))
        }
    }

    @Test fun canonicalWorkPackagesAreAcceptedOnlyAfterAliasProofWithoutRekeyingOutbox() = runBlocking {
        Service().use { service ->
            service.canonical = "canonical-work"; service.detail = "canonical-work"
            val repository = repository(service)
            val frozen = """{"version_id":"v1","chapter_id":"c0","ratio":0.2,"anchor":{},"expected_revision":8,"client_id":"old-client","client_seq":2,"mutation_id":"original"}"""
            val state = NovelReadingStateEntity(NovelRepository.stateId(novelScope(service.c), "work"), novelScope(service.c), "work", "old-client",
                revision = 8, localSequence = 1, localPosition = """{"version_id":"v1","chapter_id":"c0","ratio":0.2,"anchor":{}}""", requestJson = frozen, requestSequence = 1, nextClientSeq = 2)
            repository.dao.putState(state)
            val window = repository.cache.window(service.c, "work", "v1", 0, 1)
            assertEquals("canonical-work", window.mapping.workId)
            assertEquals("work", window.publication.workId)
            assertTrue(service.requests.contains("GET works/work"))
            assertEquals(state, repository.dao.state(state.id))
            assertTrue(repository.sync())
            assertEquals(frozen, service.progressBodies.single())
            assertTrue(service.requests.contains("PUT me/progress/work"))
            assertNull(repository.dao.state(NovelRepository.stateId(novelScope(service.c), "canonical-work")))
            service.detail = "unrelated-work"
            try { repository.cache.window(service.c, "work", "v1", 1, 1); fail("unproven work must be rejected") } catch (_: IllegalArgumentException) {}
            assertFalse(1 in service.cached)
        }
    }

    @Test fun wrongVersionOrMismatchedPackageCannotReplaceCurrentContent() = runBlocking {
        Service().use { service ->
            val repository = repository(service)
            val current = repository.cache.window(service.c, "work", "v1", 0, 1)
            service.badVersion = true
            try { repository.cache.prefetch(service.c, current.publication, 0); fail("wrong manifest") } catch (_: IllegalArgumentException) {}
            assertEquals(setOf(0), service.cached)
            service.badVersion = false; service.packageVersion = "v2"
            try { repository.cache.window(service.c, "work", "v1", 1, 1); fail("wrong package") } catch (_: IllegalArgumentException) {}
            assertEquals(1, repository.dao.publications(novelScope(service.c)).size)
            assertNotNull(repository.cachedChapter(novelScope(service.c), "work", "v1", "c0"))
            assertNull(repository.dao.state(NovelRepository.stateId(novelScope(service.c), "work")))
        }
    }

    @Test fun damagedLocalPackageIsRepairedAndNeverCountedAsAlreadyDownloaded() = runBlocking {
        Service(total = 3).use { service ->
            val repository = repository(service)
            val first = repository.cache.window(service.c, "work", "v1", 0, 3)
            val library = LibraryRepository(RuntimeEnvironment.getApplication())
            library.localFile(first.asset).writeText("damaged")
            assertTrue(repository.cache.chapters(first.publication.scope, "work", "v1", verify = true).isEmpty())
            val repaired = downloadNovelContents(repository, service.c, NovelDownloadTask(novelScope(service.c), "work", "v1", "test")) {}
            assertEquals("completed", repaired.state); assertEquals(3, repaired.completed)
            assertEquals(3, repository.cache.chapters(first.publication.scope, "work", "v1", verify = true).size)
        }
    }

    @Test fun staleWorkerCannotOverwritePauseOrResumedOperation() = runBlocking {
        val store = NovelDownloads(RuntimeEnvironment.getApplication())
        val task = NovelDownloadTask("novel:unique-${java.util.UUID.randomUUID()}", "work", "v1", "test")
        assertTrue(store.save(task))
        assertTrue(store.save(task.copy(state = "paused", completed = 2), task.operation))
        assertFalse(store.save(task.copy(state = "completed", completed = 100), task.operation))
        val resumed = task.copy(operation = "new-operation")
        assertTrue(store.save(resumed))
        assertFalse(store.save(task.copy(state = "failed"), task.operation))
        assertEquals(resumed, NovelDownloads(RuntimeEnvironment.getApplication()).read(task.key))
    }

    @Test fun brokenAheadChapterDoesNotPreventLaterReadableChaptersFromPrefetching() = runBlocking {
        Service(total = 5).use { service ->
            val repository = repository(service)
            val current = repository.cache.window(service.c, "work", "v1", 0, 1)
            service.broken += 1
            try { repository.cache.prefetch(service.c, current.publication, 0); fail("partial prefetch must remain visible") } catch (_: NovelApiException) {}
            assertEquals(setOf(0, 2, 3), service.cached)
            assertNotNull(repository.cachedChapter(current.publication.scope, "work", "v1", "c0"))
            assertEquals("c1", repository.cache.adjacent(current.publication.scope, "work", "v1", 0, 1)!!.chapter!!.getString("id"))
        }
    }

    @Test fun serverCacheCompletionIsNotLocalDownloadCompletionUntilPackageValidation() = runBlocking {
        Service(total = 3).use { service ->
            service.cacheJobs = true
            val repository = repository(service)
            val updates = mutableListOf<NovelDownloadTask>()
            val task = NovelDownloadTask(novelScope(service.c), "work", "v1", "test")
            service.packageVersion = "wrong-package-version"
            try { downloadNovelContents(repository, service.c, task) { updates += it }; fail("invalid local EPUB") } catch (_: IllegalArgumentException) {}
            assertTrue(service.requests.contains("POST works/work/cache-jobs"))
            assertTrue(service.requests.none { it.startsWith("GET chapters/") })
            assertTrue(updates.all { it.completed == 0 && it.state != "completed" })
            assertTrue(repository.dao.publications(task.scope).isEmpty())
            service.packageVersion = null
            val final = downloadNovelContents(repository, service.c, task) {}
            assertEquals("completed", final.state); assertEquals(3, final.completed)
        }
    }

    @Test fun resumingServerWarmupReusesItsJobAndRejectsAnotherVersionsResult() = runBlocking {
        Service(total = 3).use { service ->
            service.cacheJobs = true; service.jobChapters = listOf("c0", "c1", "c2")
            val repository = repository(service)
            val task = NovelDownloadTask(novelScope(service.c), "work", "v1", "test", serverJobId = "cache-job",
                serverSelection = LibraryRepository.digest("c0/c1/c2"))
            val final = downloadNovelContents(repository, service.c, task) {}
            assertEquals(3, final.completed)
            assertFalse(service.requests.any { it == "POST works/work/cache-jobs" })
        }
        Service(total = 3).use { service ->
            service.cacheJobs = true; service.jobVersion = "v2"
            val repository = repository(service)
            try { downloadNovelContents(repository, service.c, NovelDownloadTask(novelScope(service.c), "work", "v1", "test")) {}; fail("wrong server job version") }
            catch (_: IllegalArgumentException) {}
            assertFalse(service.requests.any { it.endsWith("publication.epub") })
        }
    }

    @Test fun offlineBookEntryUsesTheLastReadWindowInsteadOfTheLatestPrefetchedWindow() = runBlocking {
        Service().use { service ->
            val repository = repository(service)
            val first = repository.cache.window(service.c, "work", "v1", 0, 1)
            val read = repository.cache.window(service.c, "work", "v1", 3, 1)
            repository.cache.window(service.c, "work", "v1", 6, 1)
            val library = LibraryRepository(RuntimeEnvironment.getApplication())
            library.dao.putProgress(ContentProgressEntity(first.asset.id, locatorType = "text", locatorJson = "{}", updatedAt = 100))
            library.dao.putProgress(ContentProgressEntity(read.asset.id, locatorType = "text", locatorJson = "{}", updatedAt = 200))
            repository.dao.putState(NovelReadingStateEntity(NovelRepository.stateId(first.publication.scope, "work"), first.publication.scope, "work", "device",
                localPosition = """{"version_id":"another-version","asset_id":"another-window"}"""))
            assertEquals(read.asset.id, repository.downloaded(first.publication.scope).single().id)
        }
    }

    @Test fun redirectedDetailRemainsAvailableAfterNavigationToTheCanonicalWorkOffline() = runBlocking {
        Service().use { service ->
            service.detail = "canonical"
            val repository = repository(service)
            assertEquals("canonical", repository.get(service.c, listOf("works", "work")).getString("id"))
            val before = service.requests.size
            LibraryResources.offlineOnly = true
            try {
                val page = repository.get(service.c, listOf("works", "canonical"))
                assertEquals("canonical", page.getString("id")); assertTrue(page.has("_cached_at"))
                assertEquals(before, service.requests.size)
            } finally { LibraryResources.offlineOnly = false }
        }
    }

    @Test fun malformedChapterCannotBeCountedAsLocallyReadableAndOtherChaptersSurvive() = runBlocking {
        Service(total = 3).use { service ->
            service.badXml += 1
            val repository = repository(service)
            val task = downloadNovelContents(repository, service.c, NovelDownloadTask(novelScope(service.c), "work", "v1", "test")) {}
            assertEquals("partial", task.state); assertEquals(2, task.completed); assertEquals(1, task.failed)
            assertEquals(setOf("c0", "c2"), repository.cache.chapters(task.scope, "work", "v1").map { it.getString("id") }.toSet())
        }
    }

    @Test fun unreadableFollowingPackageChapterCannotBlockOpeningTheCurrentChapter() = runBlocking {
        Service(total = 3).use { service ->
            service.badXml += 1
            val repository = repository(service)
            val prepared = repository.prepare(service.c, "work", "v1", "c0")
            val publication = repository.dao.publication(prepared.asset.id)!!
            assertEquals(listOf("c0"), NovelPublicationMap(JSONObject(publication.mappingJson)).chapters.map { it.getString("id") })
            assertFalse(repository.dao.state(NovelRepository.stateId(novelScope(service.c), "work"))!!.pending)
        }
    }
}
