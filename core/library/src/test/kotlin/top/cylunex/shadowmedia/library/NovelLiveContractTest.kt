package top.cylunex.shadowmedia.library

import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional read-only check against an isolated Novel instance. No credentials or fixtures are committed. */
class NovelLiveContractTest {
    @Test fun realServerPackagesKeepChapterCoordinatesThroughMediaSanitizer() = runBlocking {
        val root = System.getenv("NOVEL_VALIDATION_URL").orEmpty()
        assumeTrue("Set NOVEL_VALIDATION_URL to an isolated server", root.isNotBlank())
        val c = CatalogConnection(name = "validation", kind = CatalogKind.OPDS, url = root, allowHttp = true)
        val api = NovelApi()
        assertEquals("shadow.novel.integration/v1", api.json(c, listOf("integration")).getString("schema"))
        val works = api.json(c, listOf("works"), listOf("view" to "search", "limit" to "10")).novelObjects("items")
        assertTrue(works.isNotEmpty())
        for (work in works) {
            val id = work.getString("id")
            val before = api.json(c, listOf("me", "progress", id)).toString()
            val manifest = api.json(c, listOf("works", id, "reading-manifest"), listOf("limit" to "3"))
            val version = manifest.getString("version_id")
            val raw = File.createTempFile("novel-contract-", ".epub"); val sanitized = File.createTempFile("novel-sanitized-", ".epub")
            try {
                api.download(c, listOf("works", id, "publication.epub"), raw, listOf("version_id" to version, "limit" to "3"))
                PublicationSanitizer.sanitize(raw, sanitized)
                SafeArchives.validate(sanitized)
                val mapping = NovelPublicationMap(JSONObject(SafeArchives.read(sanitized, "shadow-novel.json", 2L * 1024 * 1024).toString(Charsets.UTF_8)))
                assertEquals(id, mapping.workId); assertEquals(version, mapping.versionId)
                val readable = manifest.novelObjects("items").filter { it.optBoolean("readable") }
                assertEquals(readable.map { it.getString("id") }, mapping.chapters.map { it.getString("id") })
                ZipFile(sanitized).use { zip -> mapping.chapters.forEach { chapter ->
                    assertNotNull(zip.getEntry(chapter.getString("href")))
                    val position = mapping.position(JSONObject().put("href", chapter.getString("href") + "#p-1").put("locations", JSONObject().put("progression", 0.42)))!!
                    assertEquals(chapter.getString("id"), position.getString("chapter_id"))
                    assertNotNull(mapping.locator(position))
                } }
                assertEquals(before, api.json(c, listOf("me", "progress", id)).toString())
            } finally { raw.delete(); sanitized.delete() }
        }
    }
}
