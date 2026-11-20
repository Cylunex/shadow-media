package com.shadow.media.library

import android.content.Context
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NovelSessionStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun store() = CatalogConnectionStore(context, "oidc-test", "fixture", keyProvider = { key })
    private fun account() = CatalogConnection(id = "account", name = "owner", kind = CatalogKind.OPDS, url = "https://example.com/novel/",
        oidcIssuer = "https://id.example.com", oidcSubject = "owner", oidcClientId = "media", oidcAuthState = "private-refresh-token")
    private fun attempt(id: String, created: Long = System.currentTimeMillis()) = JSONObject().put("attempt", id).put("created", created)
        .put("previousId", "account").put("request", "private-pkce-verifier")

    @Test fun staleUnauthorizedResponsesReuseTheRotatedTokenWithoutRewritingTheVault() = runBlocking {
        val config = net.openid.appauth.AuthorizationServiceConfiguration(
            android.net.Uri.parse("https://id.example.com/auth"), android.net.Uri.parse("https://id.example.com/token"))
        val request = net.openid.appauth.TokenRequest.Builder(config, "media").setGrantType("refresh_token")
            .setRefreshToken("refresh").build()
        val tokens = net.openid.appauth.TokenResponse.Builder(request).setTokenType("Bearer").setAccessToken("current")
            .setAccessTokenExpirationTime(System.currentTimeMillis() + 3600000L).build()
        val state = net.openid.appauth.AuthState(config).apply { update(tokens, null) }
        val current = account().copy(oidcAuthState = state.jsonSerializeString())
        val store = store()
        store.save(current)
        val prefs = context.getSharedPreferences("oidc-test", Context.MODE_PRIVATE)
        val original = prefs.getString("encrypted", null)
        val oidc = NovelOidcTokens(context, store)
        assertEquals("current", oidc.tokenAfterRejection(current, "already-replaced"))
        assertEquals("current", oidc.tokenAfterRejection(current, null))
        assertTrue(store.updateAuthState(current.id, current.oidcAuthState, current.oidcAuthState))
        assertEquals(original, prefs.getString("encrypted", null))
    }

    @Test fun `sessions and pending PKCE survive process recreation encrypted`() {
        val store = store(); store.save(account()); store.beginLogin(attempt("login"))
        val saved = context.getSharedPreferences("oidc-test", Context.MODE_PRIVATE).all.toString()
        assertFalse(saved.contains("private-refresh-token")); assertFalse(saved.contains("private-pkce-verifier"))
        val recreated = store()
        assertEquals("private-refresh-token", recreated.load().single().oidcAuthState)
        assertEquals("private-pkce-verifier", recreated.pendingLogin()!!.getString("request"))
    }
    @Test fun `logout blocks late refresh and late login without deleting offline scope`() {
        val store = store(); store.save(account()); store.beginLogin(attempt("login"))
        assertNotNull(store.consumeLogin("login"))
        store.signOut("account")
        assertFalse(store.updateAuthState("account", "private-refresh-token", "late-refresh"))
        try { store.finishLogin("login", account()); fail("late callback cannot restore login") } catch (_: IllegalStateException) {}
        assertEquals("account", store.load().single().id); assertEquals("", store.load().single().oidcAuthState)
    }
    @Test fun `home only offers currently connected scopes`() = runBlocking {
        val store = store()
        store.save(account())
        assertEquals(listOf("novel:account"), store.connectedProviderIds(::novelScope).first())
        store.signOut("account")
        assertTrue(store.connectedProviderIds(::novelScope).first().isEmpty())
        store.remove("account")
        assertTrue(store.connectedProviderIds(::novelScope).first().isEmpty())
    }
    @Test fun `pending callbacks are one-time time-bounded and generation-bound`() {
        val store = store(); store.save(account()); store.beginLogin(attempt("one"))
        assertNull(store.consumeLogin("other")); assertNotNull(store.consumeLogin("one")); assertNull(store.consumeLogin("one"))
        store.beginLogin(attempt("two")); store.cancelLogin("one"); assertEquals("two", store.pendingLogin()!!.getString("attempt"))
        store.beginLogin(attempt("expired", System.currentTimeMillis() - 601_000)); assertNull(store.consumeLogin("expired"))
        store.beginLogin(attempt("future", System.currentTimeMillis() + 10_000)); assertNull(store.consumeLogin("future"))
    }
    @Test fun `reauthentication preserves identity scope but changing owner creates a new one`() {
        val store = store(); store.save(account()); store.beginLogin(attempt("same")); store.consumeLogin("same")
        assertEquals("account", store.finishLogin("same", account().copy(oidcAuthState = "new-token")).id)
        store.beginLogin(attempt("other")); store.consumeLogin("other")
        assertNotEquals("account", store.finishLogin("other", account().copy(oidcSubject = "other-owner")).id)
    }
}
