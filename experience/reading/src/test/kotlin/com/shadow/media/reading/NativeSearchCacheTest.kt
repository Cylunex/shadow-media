package com.shadow.media.reading

import com.shadow.media.database.LibraryAssetEntity
import com.shadow.media.database.NovelPublicationEntity
import com.shadow.media.library.LibraryRepository
import com.shadow.media.library.NovelRepository
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeSearchCacheTest {
    @Test fun overlappingWindowsAreSearchedOnceInChapterOrderWithWorkVersionAndAccountIsolation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val library = LibraryRepository(context)
        val novel = NovelRepository(context, library)
        val source = NativeTextSource(library, novel)
        suspend fun window(name: String, ordinals: List<Int>, work: String = "work", version: String = "v1", scope: String = "novel:test", missing: Boolean = false): Pair<LibraryAssetEntity, NovelPublicationEntity> {
            val id = LibraryRepository.digest(name)
            val file = File(library.folder(id), "content.epub")
            if (!missing) ZipOutputStream(file.outputStream()).use { zip ->
                ordinals.forEach { ordinal ->
                    zip.putNextEntry(ZipEntry("chapters/c$ordinal.xhtml"))
                    zip.write("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>第 $ordinal 章的星光。</p></body></html>".toByteArray())
                    zip.closeEntry()
                }
            }
            val asset = LibraryAssetEntity(id, scope, name, name, kind = "BOOK", format = "epub", localUri = file.toURI().toString(), revision = name, addedAt = 0)
            val mapping = JSONObject().put("schema", "shadow.novel.publication/v1").put("work_id", work).put("version_id", version)
                .put("complete", false).put("version_complete", false).put("total", 1000)
                .put("chapters", JSONArray().also { array -> ordinals.forEach { ordinal ->
                    array.put(JSONObject().put("id", "c$ordinal").put("title", "第 $ordinal 章").put("ordinal", ordinal).put("href", "chapters/c$ordinal.xhtml"))
                } })
            val publication = NovelPublicationEntity(id, scope, work, version, mapping.toString(), name)
            library.dao.putAsset(asset)
            novel.dao.putPublication(publication)
            return asset to publication
        }
        val (asset, publication) = window("later", listOf(1, 2))
        window("earlier", listOf(0, 1))
        window("different-version", listOf(77), version = "v2")
        window("different-book", listOf(78), work = "other")
        window("different-account", listOf(79), scope = "novel:other")
        window("removed-file", listOf(80), missing = true)
        val entries = source.searchEntries(asset, publication)
        assertEquals(listOf("c0", "c1", "c2"), entries.map { it.ref.id })
        val result = searchNativeText(entries, "星光").last()
        assertEquals(3, result.hits.size)
        assertEquals(0, result.skipped)
        result.hits.forEachIndexed { ordinal, hit ->
            assertEquals("chapters/c$ordinal.xhtml", JSONObject(hit.locator).getString("href"))
            assertNotNull(library.dao.asset(hit.assetId))
        }
        assertNull(novel.dao.state(NovelRepository.stateId(publication.scope, publication.workId)))
        assertNull(library.dao.progress(asset.id))
    }
}
