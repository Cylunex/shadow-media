package com.shadow.media

import android.app.Application
import com.shadow.media.library.*
import com.shadow.media.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DevicePlaybackFeatureTest {
    @Test fun portableHandoffHasNoCredentialsAndSurvivesDifferentLocalConnectionIds() {
        val source = CatalogConnection(id = "one", name = "library", url = "https://example.com/media/",
            kind = CatalogKind.OPDS, token = "test-only-secret")
        val key = portableMdc(source, true)
        assertFalse(key.contains(source.token))
        assertEquals(key, portableMdc(source.copy(id = "other", name = "TV"), true))
        assertNotEquals(key, portableMdc(source.copy(token = "other-identity"), true))
        assertNotEquals(key, portableMdc(source, false))
        val now = DevicePlayback(MediaKey(key, "site:video:ep"), "片名", 345678L)
        assertEquals(now, DevicePlayback.parse(now.json()))
    }
    @Test fun syncOptInFollowsVerifiedIdentityAcrossTokenRotationAndConnectionReuse() {
        val account = CatalogConnection(id = "slot", name = "one", url = "https://example.com/",
            kind = CatalogKind.OPDS, oidcIssuer = "issuer", oidcSubject = "reader", oidcClientId = "android")
        assertEquals(account.handoffAccount(), account.copy(oidcAuthState = "refreshed").handoffAccount())
        assertNotEquals(account.handoffAccount(), account.copy(oidcSubject = "another").handoffAccount())
        assertNotEquals(account.handoffAccount(), account.copy(url = "https://example.com/other/").handoffAccount())
    }

    @Test fun sharedEpisodeLinkResolvesDifferentDeviceSlotAndRetainsPositionAndLegacyLinks() {
        val phone = CatalogConnection(id = "phone", name = "片库", url = "https://example.com/mdc/",
            kind = CatalogKind.OPDS, token = "REPLACE_ACCOUNT")
        val tv = phone.copy(id = "tv")
        val saved = MediaKey(portableMdc(phone, true), "site:film:episode")
        val link = saved.toHandoffUri(123456, accountScopedMdc = true)
        assertFalse(link.toString().contains(phone.token))
        assertFalse(link.toString().contains(phone.id))
        val received = requireNotNull(link.toMediaHandoffOrNull())
        assertTrue(received.accountScopedMdc)
        assertEquals(123456L, received.positionMs)
        assertEquals(MediaKey("mdc-tvbox:tv", saved.itemId), received.key.localMdcKey(listOf(tv)))
        assertNull(received.key.localMdcKey(listOf(tv.copy(token = "REPLACE_OTHER"))))
        assertNull(received.key.localMdcKey(listOf(phone, tv)))
        val legacy = MediaKey("mdc-tvbox:phone", saved.itemId)
        assertEquals(MediaHandoff(legacy, 0), legacy.toHandoffUri().toMediaHandoffOrNull())
    }

    @Test fun feedModesKeepTheirOwnQueuesAndSeenIsScopedToTheAccountAndLibrary() {
        val store = FeedSessionStore(RuntimeEnvironment.getApplication())
        val owner = EmbySession("https://example.com", "demo", "owner", "", "", false)
        fun item(id: String, played: Boolean, favorite: Boolean) = MediaItem(id, id, "Movie", null, null, null, null, 0L, played, favorite)
        val items = listOf(item("a", false, true), item("b", true, false), item("c", false, false))
        assertEquals(listOf("a","b","c"), store.reconcile(owner, "library", items).orderedItemIds)
        store.markCurrent(owner, "library", "a", 0)
        store.setFilter(owner, "library", FeedFilter(FeedOrder.UNWATCHED, true))
        assertEquals(listOf("c"), store.reconcile(owner, "library", items).orderedItemIds)
        store.setFilter(owner, "library", FeedFilter(FeedOrder.FAVORITES))
        assertEquals(listOf("a"), store.reconcile(owner, "library", items).orderedItemIds)
        assertTrue(store.seen(owner, "other").isEmpty())
        assertTrue(store.seen(owner.copy(userId = "another"), "library").isEmpty())
    }
}
