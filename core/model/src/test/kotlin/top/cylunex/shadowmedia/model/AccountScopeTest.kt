package top.cylunex.shadowmedia.model

import org.junit.Assert.*
import org.junit.Test

class AccountScopeTest {
    private val a = EmbySession("https://example.com/media/", "server", "user", "A", "secret", false)
    @Test fun canonicalEndpointAndTokenRotationKeepIdentity() {
        assertEquals(a.providerId, a.copy(serverUrl = "HTTPS://EXAMPLE.COM:443/media", accessToken = "new").providerId)
        assertFalse(a.providerId.contains("example")); assertFalse(a.providerId.contains("secret"))
    }
    @Test fun originProxyPathAccountAndProfileAreIsolated() {
        for (b in listOf(a.copy(serverUrl = "https://other.example.com/media"), a.copy(serverUrl = "https://example.com/other"), a.copy(userId = "other"))) assertNotEquals(a.providerId, b.providerId)
        assertNotEquals(a.providerId, accountScope("emby", a.serverUrl, a.serverId, a.userId, "other"))
    }
    @Test fun ambiguousLegacyAccountIsNeverGuessed() {
        assertEquals(a, listOf(a).accountFor(a.legacyProviderId))
        val b = a.copy(serverUrl = "https://other.example.com/media")
        assertNull(listOf(a, b).accountFor(a.legacyProviderId)); assertEquals(a, listOf(a, b).accountFor(a.providerId))
    }
}
