package top.cylunex.shadowmedia.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineCatalogTransactionTest {
    @Test fun incompleteAndLateRefreshNeverTombstoneTheCurrentCatalog() = runBlocking {
        Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ShadowMediaDatabase::class.java).build().useDatabase { db ->
            val dao = db.catalogSnapshotDao()
            dao.begin("scope", "one"); dao.append("scope", "one", listOf("a", "b")); dao.finish("scope", "one", 1, true)
            dao.begin("scope", "two"); dao.append("scope", "two", listOf("a")); dao.finish("scope", "two", 2, false)
            fun present(): Int = db.openHelper.readableDatabase.query("SELECT count(*) FROM catalog_entries WHERE present = 1").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(2, present())
            dao.begin("scope", "three"); dao.append("scope", "three", listOf("b"))
            dao.finish("scope", "two", 3, true)
            assertEquals(2, present())
            dao.finish("scope", "three", 4, true)
            assertEquals(1, present())
        }
    }
    @Test fun deletedOrReplacedTaskRejectsOldProgressAndCompletion() = runBlocking {
        Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ShadowMediaDatabase::class.java).build().useDatabase { db ->
            val dao = db.resourceTaskDao()
            val old = ResourceTaskEntity("asset", "old", "r", "OFFLINE_PENDING", "MEDIA3", updatedAt = 1)
            dao.put(old); dao.put(old.copy(operationId = "new"))
            assertEquals(0, dao.update("asset", "old", "OFFLINE_AVAILABLE", 100, 100, "", 2))
            dao.delete("asset", "old"); assertEquals("new", dao.task("asset")?.operationId)
            dao.delete("asset", "new")
            assertEquals(0, dao.update("asset", "new", "OFFLINE_AVAILABLE", 100, 100, "", 3))
            assertNull(dao.task("asset"))
        }
    }
}
