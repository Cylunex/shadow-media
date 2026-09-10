package top.cylunex.shadowmedia.database

import androidx.room.Room
import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryStateTest {
    @Test fun lateFavoriteAcknowledgementCannotClearNewerLocalChoice() = runBlocking {
        Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ShadowMediaDatabase::class.java).build().useDatabase { db ->
            val dao = db.libraryStateDao()
            val old = RemoteUserStateEntity("account", "item", true, "first", true, 1)
            dao.favorite(old); dao.favorite(old.copy(favorite = false, operationId = "second", updatedAt = 2))
            dao.acknowledgeUserState("account", "item", "first")
            assertTrue(dao.userState("account", "item")!!.pending)
            assertFalse(dao.userState("account", "item")!!.favorite)
            dao.acknowledgeUserState("account", "item", "second")
            assertFalse(dao.userState("account", "item")!!.pending)
        }
    }
    @Test fun accountScopeMigrationKeepsEditionIdsAndNeverGuessesAmbiguousHistory() = runBlocking {
        Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ShadowMediaDatabase::class.java).build().useDatabase { db ->
            val a = top.cylunex.shadowmedia.model.EmbySession("https://example.com/a", "server", "user", "A", "secret", false)
            val asset = LibraryAssetEntity("asset", a.legacyProviderId, "item", "书籍", kind = "AUDIOBOOK", format = "m4b", addedAt = 1)
            db.libraryDao().putAsset(asset)
            val progress = ContentProgressEntity("asset", locatorType = "time", locatorJson = "{\"positionMs\":1234}", updatedAt = 2)
            db.libraryDao().putProgress(progress)
            db.migrateAccountScopes(listOf(a)); db.migrateAccountScopes(listOf(a))
            assertEquals(a.providerId, db.libraryDao().asset("asset")!!.providerId)
            assertEquals(progress, db.libraryDao().progress("asset"))
            val ambiguous = a.copy(serverId = "clone")
            db.libraryDao().putAsset(asset.copy(id = "ambiguous", providerId = ambiguous.legacyProviderId))
            db.migrateAccountScopes(listOf(ambiguous, ambiguous.copy(serverUrl = "https://example.com/b")))
            db.migrateAccountScopes(listOf(ambiguous))
            assertEquals(ambiguous.legacyProviderId, db.libraryDao().asset("ambiguous")!!.providerId)
        }
    }
    @Test fun associationDoesNotTranslateOrOverwriteEitherRenditionProgress() = runBlocking {
        Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ShadowMediaDatabase::class.java).build().useDatabase { db ->
            val book = LibraryAssetEntity("book", "local", "book", "同一作品", kind = "BOOK", format = "epub", addedAt = 1)
            val audio = book.copy(id = "audio", itemId = "audio", kind = "AUDIOBOOK", format = "m4b")
            db.libraryDao().putAsset(book); db.libraryDao().putAsset(audio)
            val textProgress = ContentProgressEntity("book", locatorType = "text", locatorJson = "{\"href\":\"chapter-2.xhtml\"}", progression = .5, updatedAt = 2)
            val audioProgress = ContentProgressEntity("audio", locatorType = "time", locatorJson = "{\"positionMs\":1500}", progression = .1, updatedAt = 3)
            db.libraryDao().putProgress(textProgress); db.libraryDao().putProgress(audioProgress)
            val states = db.libraryStateDao(); states.associate("book", "audio", "同一作品")
            val workId = states.rendition("book")!!.workId
            assertEquals(2, states.editions(workId).first().size)
            assertEquals(textProgress, db.libraryDao().progress("book")); assertEquals(audioProgress, db.libraryDao().progress("audio"))
            states.unlink("book"); assertEquals(1, states.editions(workId).first().size)
        }
    }
    @Test fun indexingIsNotPersonalCollectionAndFavoritePreservesSeparateProfileState() = runBlocking {
        Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ShadowMediaDatabase::class.java).build().useDatabase { db ->
            val asset = LibraryAssetEntity("song", "account", "song", "歌曲", kind = "MUSIC", format = "audio", addedAt = 1)
            db.libraryDao().putAsset(asset)
            val states = db.libraryStateDao()
            states.seedCollection(UserCollectionEntity(assetId = asset.id, collected = false, favorite = false, addedAt = 1))
            states.seedCollection(UserCollectionEntity(profileId = "other", assetId = asset.id, collected = false, favorite = false, addedAt = 1))
            assertEquals(0, db.libraryDao().searchCount("MUSIC", "%"))
            db.libraryDao().favorite(asset.id, true)
            assertEquals(1, db.libraryDao().searchCount("MUSIC", "%"))
            assertTrue(states.collection(asset.id)!!.favorite); assertFalse(states.collection(asset.id, "other")!!.favorite)
        }
    }
    @Test fun continueProjectionReadsLegacyHistoryWithoutCopyingItIntoReadingProgress() = runBlocking {
        Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ShadowMediaDatabase::class.java).build().useDatabase { db ->
            val history = MediaHistoryEntity("provider:item", "provider", "item", "电影", positionMs = 5000, durationMs = null, lastPlayedAtEpochMs = 100)
            db.dao().upsertHistory(history)
            val source = db.libraryStateDao().continuing()
            val page = source.load(PagingSource.LoadParams.Refresh(0, 30, false)) as PagingSource.LoadResult.Page
            assertEquals(5000L, page.data.single().positionMs); assertNull(page.data.single().progression)
            assertEquals(history, db.dao().history(history.stableKey)); assertNull(db.libraryDao().progress("item"))
            source.invalidate()
        }
    }
}
