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
    @Test fun `verified OIDC account survives rotation but never adopts a different identity`() {
        val account = original.copy(username = "", password = "", oidcIssuer = "https://id.example.com",
            oidcSubject = "owner", oidcClientId = "media-test", oidcAuthState = "secret-state-one")
        assertTrue(account.copy(oidcAuthState = "rotated-state", name = "new label").retainsAccountOf(account))
        assertTrue(account.copy(oidcAuthState = "").retainsAccountOf(account))
        assertFalse(account.copy(oidcSubject = "other").retainsAccountOf(account))
        assertFalse(account.copy(oidcIssuer = "https://other.example.com").retainsAccountOf(account))
        assertFalse(account.copy(oidcClientId = "different-client").retainsAccountOf(account))
        assertFalse(account.copy(url = "https://else.example.com").retainsAccountOf(account))
        assertFalse(account.retainsAccountOf(original))
        assertFalse(original.retainsAccountOf(account))
        assertFalse(account.toString().contains("secret-state"))
    }
}
