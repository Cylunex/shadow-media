package top.cylunex.shadowmedia.network

import kotlinx.coroutines.runBlocking
import top.cylunex.shadowmedia.database.*
import top.cylunex.shadowmedia.model.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class EmbyCatalogCacheTest {
    private fun memory(): Pair<EmbyCatalogCache, MutableMap<String, CatalogPageEntity>> {
        val pages = mutableMapOf<String, CatalogPageEntity>()
        val dao = Proxy.newProxyInstance(LibraryStateDao::class.java.classLoader, arrayOf(LibraryStateDao::class.java)) { _, method, args ->
            when (method.name) {
                "catalogPage" -> pages[args[0]]
                "cacheCatalogPage" -> { val row = args[0] as CatalogPageEntity; pages[row.id] = row; Unit }
                "trimCatalogPages" -> Unit
                else -> error(method.name)
            }
        } as LibraryStateDao
        return EmbyCatalogCache(dao) to pages
    }
    @Test fun emptyFailureAndOfflineReadsKeepLastMetadataWithoutNetwork() = runBlocking {
        val (cache, _) = memory()
        val original = EmbyCatalogSnapshot(listOf(BaseItemDto("id", "标题")))
        assertFalse(cache.fetch("key") { original }.second)
        assertEquals(original to true, cache.fetch("key") { EmbyCatalogSnapshot() })
        assertEquals(original to true, cache.fetch("key") { error("offline") })
        try {
            NetworkPolicy.offlineOnly = true
            assertEquals(original to true, cache.fetch("key") { error("must not request") })
        } finally { NetworkPolicy.offlineOnly = false }
    }
    @Test fun oversizeFreshResultDoesNotPretendOldCacheWasFresh() = runBlocking {
        val (cache, _) = memory()
        cache.write("key", EmbyCatalogSnapshot(listOf(BaseItemDto("old", "旧目录"))))
        val large = EmbyCatalogSnapshot(listOf(BaseItemDto("new", "新目录", overview = "a".repeat(3 * 1024 * 1024))))
        assertEquals(large to false, cache.fetch("key") { large })
        assertEquals("old", cache.read("key")!!.items.single().id)
    }
    @Test fun reverseProxyAndUserScopeSeparateSnapshotsWithoutStoringCredentials() {
        val (cache, _) = memory()
        val a = EmbySession("https://example.com/a", "same", "same", "A", "private", false)
        assertNotEquals(cache.key(a, "home"), cache.key(a.copy(serverUrl = "https://example.com/b"), "home"))
        assertFalse(cache.key(a, "home").contains("private"))
    }
}
