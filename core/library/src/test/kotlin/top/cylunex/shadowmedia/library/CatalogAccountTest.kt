package top.cylunex.shadowmedia.library

import org.junit.Assert.*
import org.junit.Test

class CatalogAccountTest {
    private val original = CatalogConnection(name = "Library", kind = CatalogKind.KOMGA,
        url = "https://example.com/books", username = "reader", password = "test-only")
    @Test fun `label changes preserve account scope`() {
        assertTrue(original.copy(name = "New label").retainsAccountOf(original))
    }
    @Test fun `endpoint and credentials never inherit queued progress`() {
        assertFalse(original.copy(username = "other").retainsAccountOf(original))
        assertFalse(original.copy(password = "rotated").retainsAccountOf(original))
        assertFalse(original.copy(token = "test-token").retainsAccountOf(original))
        assertFalse(original.copy(url = "https://example.com/other").retainsAccountOf(original))
        assertFalse(original.copy(kind = CatalogKind.OPDS).retainsAccountOf(original))
    }
}
