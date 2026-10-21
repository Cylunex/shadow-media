@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package top.cylunex.shadowmedia.reading

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import top.cylunex.shadowmedia.library.*

/** Read-only regression for specified real works. Values/content stay outside the repository. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelReaderOpeningTest {
    @Test fun specifiedWorksPrepareSanitizeAndCreateReadiumNavigatorWithoutWritingRemoteProgress() = runBlocking {
        val root = System.getenv("NOVEL_VALIDATION_URL").orEmpty()
        val targets = System.getenv("NOVEL_VALIDATION_TARGETS").orEmpty().split(',').filter(String::isNotBlank)
        assumeTrue("Set NOVEL_VALIDATION_URL and work:version pairs in NOVEL_VALIDATION_TARGETS", root.isNotBlank() && targets.isNotEmpty())
        val context = RuntimeEnvironment.getApplication()
        val api = NovelApi()
        val connection = CatalogConnection(name = "validation", kind = CatalogKind.OPDS, url = root, allowHttp = true)
        val library = LibraryRepository(context)
        val repository = NovelRepository(context, library, api)
        for (target in targets) {
            val (work, version) = target.split(':', limit = 2)
            val before = api.json(connection, listOf("me", "progress", work)).toString()
            val detail = api.json(connection, listOf("works", work))
            val open = api.json(connection, listOf("works", work, "open"), listOf("version_id" to version))
            val chapter = open.novelObjects("chapters").first { it.optBoolean("readable") }.getString("id")
            val prepared = repository.prepare(connection, work, version, chapter)
            assertEquals(detail.getString("title"), prepared.asset.title)
            assertNotNull(repository.dao.publication(prepared.asset.id))
            val file = library.publicationFile(prepared.asset)
            SafeArchives.validate(file)
            val initial = Locator.fromJSON(JSONObject(requireNotNull(prepared.locator)))
            assertNotNull(initial)
            val engine = ReadiumEngineAdapter(context)
            val publication = engine.open(file)
            try {
                assertEquals(3, publication.readingOrder.size)
                for (bold in listOf(false, true)) {
                    val preferences = ReaderSettings(bold = bold).epubPreferences()
                    assertNotNull(engine.navigator(publication, initial, preferences))
                }
                val nativeSource = NativeTextSource(library, repository)
                val packageMap = NovelPublicationMap(JSONObject(repository.dao.publication(prepared.asset.id)!!.mappingJson))
                for (ref in packageMap.chapters) {
                    val nativeChapter = nativeSource.chapter(prepared.asset, repository.dao.publication(prepared.asset.id), ref.getString("href"))
                    assertTrue(nativeChapter.text.isNotBlank())
                    val pages = NativeTextLayout.paginate(nativeChapter, 432, 900, ReaderSettings(), 1f, 1f)
                    assertTrue(pages.pages.isNotEmpty())
                    assertEquals(nativeChapter.text.length, pages.pages.last().end)
                    val restored = packageMap.locator(packageMap.position(nativeChapter.locator(nativeChapter.text.length / 2))!!)!!
                    assertEquals(nativeChapter.text.length / 2, nativeChapter.restore(restored))
                }
                assertFalse(repository.dao.state(NovelRepository.stateId(novelScope(connection), work))!!.pending)
                assertEquals(before, api.json(connection, listOf("me", "progress", work)).toString())
            } finally { publication.close() }
        }
    }
}
