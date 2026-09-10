package top.cylunex.shadowmedia.library

import org.junit.Assert.*
import org.junit.Test

class CatalogSnapshotCodecTest {
    @Test fun snapshotKeepsDisplayMetadataButOmitsAcquisitionAndCoverLeases() {
        val entry = CatalogEntry("opds-ref:v1:opaque-id", "标题", "作者", "epub", acquisition = "https://example.com/book?token=private", cover = "https://example.com/cover?token=private",
            locator = "opds-ref:v1:opaque-book-reference")
        val encoded = CatalogSnapshotCodec.encode(CatalogPage("书库", listOf(entry), "opds-ref:v1:next"), CatalogKind.OPDS)
        assertFalse(encoded.contains("private")); assertFalse(encoded.contains("acquisition")); assertFalse(encoded.contains("cover"))
        val restored = CatalogSnapshotCodec.decode(encoded)
        assertTrue(restored.cached); assertEquals("标题", restored.entries.single().title)
        assertEquals(entry.locator, restored.entries.single().locator); assertNull(restored.entries.single().acquisition)
    }
    @Test fun signedCatalogReferencesAreNotPersistedAsStableIdentifiers() {
        val page = CatalogPage("目录", listOf(CatalogEntry("id", "标题", navigation = "HTTPS://example.com/catalog?token=private")), "https://example.com/next?signature=private")
        val encoded = CatalogSnapshotCodec.encode(page, CatalogKind.OPDS)
        assertFalse(encoded.contains("private")); assertTrue(CatalogSnapshotCodec.decode(encoded).entries.isEmpty())
        assertNull(CatalogSnapshotCodec.decode(encoded).next)
    }
    @Test fun legacyPathCredentialsAreRemovedAndReferencesAreAccountBound() {
        val path = "https://example.com/api/opds/private-key/books"
        val raw = OpdsCatalog.locator(path, "urn:book")
        val old = org.json.JSONObject().put("entries", org.json.JSONArray().put(org.json.JSONObject().put("locator", raw))).toString()
        assertTrue(CatalogSnapshotCodec.hasLegacyWebReferences(old))
        val first = OpdsReferenceStore.reference("account-a", raw)
        assertFalse(first.contains("private-key"))
        assertEquals(first, OpdsReferenceStore.reference("account-a", raw))
        assertNotEquals(first, OpdsReferenceStore.reference("account-b", raw))
        val safe = CatalogSnapshotCodec.encode(CatalogPage("目录", listOf(CatalogEntry(path, "标题", navigation = path))), CatalogKind.OPDS)
        assertFalse(safe.contains("private-key"))
    }
    @Test fun nativeCatalogSnapshotPreservesOpaquePaginationAndAccountLocalIds() {
        val page = CatalogPage("音乐", listOf(CatalogEntry("album:123", "专辑", navigation = "album:123")), "4:20")
        val restored = CatalogSnapshotCodec.decode(CatalogSnapshotCodec.encode(page, CatalogKind.OPENSUBSONIC))
        assertEquals(page.entries, restored.entries); assertEquals(page.next, restored.next)
    }
}
