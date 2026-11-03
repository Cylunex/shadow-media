package com.shadow.media.library

import androidx.paging.PagingSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LocalShelfOrganizationTest {
    @Test fun groupTagAndPinSurvivePagingAndFailedBatchLeavesOriginalValues() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val library = LibraryRepository(context)
        val id = LibraryRepository.digest(java.util.UUID.randomUUID().toString())
        val file = File(context.cacheDir, "$id.txt").apply { writeText("第一章\n本地书架的原创测试正文。") }
        val asset = library.importPrepared(id, "local", id, "本地测试", "txt", file)
        try {
            library.organizeShelf(setOf(id), "group", "周末", emptyList())
            library.organizeShelf(setOf(id), "tags_add", "", listOf("科幻", "短篇"))
            library.organizeShelf(setOf(id), "pin", "", emptyList())
            val saved = requireNotNull(library.dao.localShelfMetadata(id))
            assertTrue(saved.pinned)
            assertEquals("周末", saved.groupName)
            val page = library.dao.shelf(listOf(asset.kind), "%本地测试%", false, false, true, "周末", "\"科幻\"")
                .load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page
            assertEquals(listOf(id), page.data.map { it.asset.id })
            try { library.organizeShelf(linkedSetOf(id, "missing"), "group", "未提交组", emptyList()); fail("Missing assets must roll back the batch") }
            catch (_: IllegalArgumentException) { }
            assertEquals("周末", library.dao.localShelfMetadata(id)?.groupName)
            library.remove(id)
            assertNull(library.dao.localShelfMetadata(id))
        } finally { file.delete() }
    }
}
