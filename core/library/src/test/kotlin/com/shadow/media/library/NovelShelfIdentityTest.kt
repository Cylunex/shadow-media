package com.shadow.media.library

import androidx.paging.PagingSource
import androidx.room.Room
import com.shadow.media.database.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NovelShelfIdentityTest {
    private lateinit var db: ShadowMediaDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ShadowMediaDatabase::class.java).build() }
    @After fun close() { db.close() }
    private suspend fun window(id: String, work: String = "book", scope: String = "novel:account", version: String = "v1", time: Long = 1) {
        db.libraryDao().putAsset(LibraryAssetEntity(id, scope, version, "同一本书", "作者", "BOOK", "epub", addedAt = time))
        db.novelDao().putPublication(NovelPublicationEntity(id, scope, work, version, "{}", "hash"))
    }
    private suspend fun <T : Any> rows(source: PagingSource<Int, T>): List<T> = try {
        val result = source.load(PagingSource.LoadParams.Refresh(null, 100, false))
        (result as PagingSource.LoadResult.Page).data
    } finally { source.invalidate() }

    @Test fun legacyWindowsAndNewSourcesAreOneBookAndUseLatestHiddenProgress() = runBlocking {
        window("first"); window("next", time = 2); window("other-source", version = "v2", time = 3)
        db.libraryDao().seedPersonalState("next"); db.libraryDao().hideCacheWindow("next")
        db.libraryDao().putProgress(ContentProgressEntity("first", locatorType = "text", locatorJson = "old", progression = .1, updatedAt = 10))
        db.libraryDao().putProgress(ContentProgressEntity("next", locatorType = "text", locatorJson = "current", progression = .7, updatedAt = 20))
        val shelf = rows(db.libraryDao().shelf(listOf("BOOK"), "%", false, false, false))
        assertEquals(1, shelf.size); assertEquals("first", shelf.single().asset.id)
        assertEquals(.7, shelf.single().progression!!, .0001)
        assertEquals(1, db.libraryDao().searchCount("BOOK", "%"))
        assertEquals(listOf("first"), db.libraryDao().searchPage("BOOK", "%", 0, 20).map { it.id })
        assertEquals(1, rows(db.libraryStateDao().continuing()).size)
        assertEquals(20L, db.libraryDao().continuing(listOf("BOOK")).first()!!.updatedAt)
        assertEquals(3, db.novelDao().publications("novel:account").size)
        assertEquals("current", db.libraryDao().progress("next")!!.locatorJson)
    }
    @Test fun sameTitlesAcrossAccountsWorksAndLocalImportsRemainSeparate() = runBlocking {
        window("one"); window("window-2", time = 2)
        window("other-work", work = "another-book")
        window("other-account", scope = "novel:another-account")
        db.libraryDao().putAsset(LibraryAssetEntity("local", "local", "document", "同一本书", "作者", "BOOK", "epub", addedAt = 4))
        assertEquals(4, db.libraryDao().searchCount("BOOK", "%"))
        assertEquals(2, db.novelDao().publications("novel:account").map { it.workId }.distinct().size)
    }
    @Test fun favoritesAndRemovalApplyToOneWorkWithoutResurfacingSiblingWindows() = runBlocking {
        window("first"); window("next", version = "v2"); window("unrelated", work = "other")
        val dao = db.libraryDao()
        dao.favorite("first", true)
        assertEquals(1, rows(dao.shelf(listOf("BOOK"), "%", true, false, false)).size)
        dao.favorite("first", false)
        assertEquals(0, rows(dao.shelf(listOf("BOOK"), "%", true, false, false)).size)
        dao.putAnnotation(ContentAnnotationEntity("mark", "next", "locator", "note", 1))
        db.novelDao().putState(NovelReadingStateEntity("state", "novel:account", "book", "device"))
        dao.removeFromShelf("first")
        assertNull(dao.asset("first")); assertNull(dao.asset("next")); assertNotNull(dao.asset("unrelated"))
        assertNull(db.novelDao().state("state")); assertNull(db.novelDao().publication("next"))
        assertTrue(dao.annotations("next").first().isEmpty())
        assertEquals(1, dao.searchCount("BOOK", "%"))
    }
}
