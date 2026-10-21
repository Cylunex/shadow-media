package top.cylunex.shadowmedia.reading

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.cylunex.shadowmedia.database.NovelPublicationEntity
import top.cylunex.shadowmedia.library.*
import java.io.File

/** Read real cached transport windows without contacting a server or mutating remote progress. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeNovelWindowTest {
    @Test fun cachedNovelWindowsRenderEveryChapterAndKeepCharacterAnchors() = runBlocking {
        val paths = System.getenv("NOVEL_VALIDATION_PACKAGES").orEmpty().split(',').filter(String::isNotBlank)
        assumeTrue("Set paths to previously downloaded Novel packages", paths.isNotEmpty())
        val context = RuntimeEnvironment.getApplication()
        val library = LibraryRepository(context); val repository = NovelRepository(context, library)
        val source = NativeTextSource(library, repository)
        for (path in paths) {
            val file = File(path)
            val map = NovelPublicationMap(JSONObject(SafeArchives.read(file, "shadow-novel.json").toString(Charsets.UTF_8)))
            val asset = library.importPrepared(LibraryRepository.digest("fixture:${map.workId}"), "novel:fixture", map.workId, "阅读测试.epub", "epub", file)
            val publication = NovelPublicationEntity(asset.id, asset.providerId, map.workId, map.versionId, map.json.toString(), asset.revision)
            repository.dao.putPublication(publication)
            for (ref in map.chapters) {
                val content = source.chapter(asset, publication, ref.getString("href"))
                val layout = NativeTextLayout.paginate(content, 432, 900, ReaderSettings(), 1f, 1f)
                assertTrue(layout.pages.isNotEmpty())
                val rendered = layout.pages.flatMap { it.lines }.flatMap { it.glyphs }.joinToString("") { it.text }.filterNot { it.isWhitespace() }
                val original = content.text.filterNot { it.isWhitespace() }
                val repeatedHeading = content.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() == content.ref.title
                val expectedBody = if (repeatedHeading) original.removePrefix(content.ref.title.filterNot { it.isWhitespace() }) else original
                assertEquals(expectedBody, rendered)
                val offset = safeTextOffset(content.text, content.text.length / 2)
                val restored = map.locator(map.position(content.locator(offset))!!)!!
                assertEquals(offset, content.restore(restored))
            }
            assertNull(repository.dao.state(NovelRepository.stateId(publication.scope, publication.workId)))
        }
    }
}
