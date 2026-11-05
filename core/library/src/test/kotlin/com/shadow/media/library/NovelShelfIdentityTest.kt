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

    @Test fun commuteUsesVisibleCollectedAudioAndCompletedDownloadsOfTheCurrentRevision() = runBlocking {
        val dao = db.libraryDao()
        fun asset(id: String, provider: String = "catalog:mine") = LibraryAssetEntity(id, provider, id, id,
            kind = "AUDIOBOOK", format = "mp3", revision = "v2", addedAt = 1)
        for ((id, revision, state) in listOf(Triple("ready", "v2", "OFFLINE_AVAILABLE"),
            Triple("stale", "v1", "OFFLINE_AVAILABLE"), Triple("pending", "v2", "OFFLINE_PENDING"),
            Triple("other", "v2", "OFFLINE_AVAILABLE"), Triple("done", "v2", "OFFLINE_AVAILABLE"))) {
            dao.putAsset(asset(id, if (id == "other") "catalog:other" else "catalog:mine"))
            db.resourceTaskDao().put(ResourceTaskEntity(id, "op-$id", revision, state, "MEDIA3", updatedAt = 1))
        }
        dao.putAsset(asset("local", "local").copy(localUri = "file:///example.mp3"))
        dao.putAsset(asset("hidden", "local").copy(localUri = "file:///another.mp3"))
        db.libraryStateDao().seedCollection(UserCollectionEntity(assetId = "hidden", collected = false, favorite = false, addedAt = 1))
        dao.putProgress(ContentProgressEntity("done", locatorType = "time", locatorJson = "{}", completed = true, updatedAt = 2))
        assertEquals(setOf("local", "ready"), dao.commuteAudio(listOf("catalog:mine","local")).first().map { it.asset.id }.toSet())

        window("excerpt-window", scope = "novel:reader")
        dao.putAnnotation(ContentAnnotationEntity("note", "excerpt-window", "{}", "选定的原文", 1))
        assertNull(dao.savedNovelNote("note", listOf("novel:other")).first())
        assertEquals("选定的原文", dao.savedNovelNote("note", listOf("novel:reader")).first()?.note)
        dao.removeAnnotation("note")
        assertNull(dao.savedNovelNote("note", listOf("novel:reader")).first())
    }

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
        val latest = db.libraryStateDao().latestContinuing(listOf("novel:account")).first()!!
        assertEquals("first", latest.assetId)
        assertEquals(20L, latest.updatedAt)
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
    @Test fun homeContinuationUsesNewestEligibleAccountOrMediumAndSkipsFinishedItems() = runBlocking {
        val state = db.libraryStateDao()
        val visible = listOf("novel:account", "emby:active", "local")
        window("novel")
        db.libraryDao().putProgress(ContentProgressEntity("novel", locatorType = "text", locatorJson = "p", updatedAt = 10))
        suspend fun video(provider: String, time: Long, completed: Boolean = false) {
            db.dao().upsertHistory(MediaHistoryEntity("$provider:film", provider, "film", "电影", positionMs = 5,
                durationMs = 10, completed = completed, lastPlayedAtEpochMs = time))
        }
        video("emby:active", 20)
        video("emby:other", 30)
        video("external:mdc:private", 40)
        assertFalse(rows(state.continuing()).any { it.providerId == "external:mdc:private" })
        assertEquals("emby:active", state.latestContinuing(visible).first()?.providerId)
        video("emby:active", 50, completed = true)
        assertEquals("novel", state.latestContinuing(visible).first()?.assetId)

        db.libraryDao().putAsset(LibraryAssetEntity("song", "local", "track", "歌曲", kind = "MUSIC", format = "mp3", addedAt = 1))
        db.musicDao().putTrack(MusicTrackEntity("song"))
        db.musicDao().putListening(MusicListeningEntity("song", lastPlayedAt = 60))
        assertEquals("song", state.latestContinuing(visible).first()?.assetId)
        assertNull(state.latestContinuing(emptyList()).first())
    }

    @Test fun moduleContinuationFiltersBeforeChoosingLatestAndPagingHistory() = runBlocking {
        val dao = db.libraryDao()
        val state = db.libraryStateDao()
        for ((index, kind) in listOf("MOVIE", "COMIC", "AUDIOBOOK", "BOOK").withIndex()) {
            dao.putAsset(LibraryAssetEntity(kind, "local", kind, kind, kind = kind, format = "fixture", addedAt = 1))
            dao.putProgress(ContentProgressEntity(kind, locatorType = "text", locatorJson = "position", progression = .3, updatedAt = index + 10L))
        }
        val providers = listOf("local")
        assertEquals("BOOK", state.latestContinuing(providers).first()?.kind)
        for (kind in listOf("MOVIE", "COMIC", "AUDIOBOOK", "BOOK")) {
            assertEquals(kind, state.latestContinuing(providers, listOf(kind)).first()?.kind)
            assertEquals(listOf(kind), rows(state.continuing(providers, listOf(kind))).map { it.kind })
        }
        assertNull(state.latestContinuing(providers, listOf("MUSIC")).first())
        assertNull(state.latestContinuing(providers, emptyList()).first())
        assertNull(state.latestContinuing(listOf("other-account"), listOf("BOOK")).first())
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
