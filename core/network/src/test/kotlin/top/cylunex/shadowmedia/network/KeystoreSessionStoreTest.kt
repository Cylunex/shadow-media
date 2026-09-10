package top.cylunex.shadowmedia.network

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import javax.crypto.KeyGenerator
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.cylunex.shadowmedia.model.EmbySession

class KeystoreSessionStoreTest {
    private class Preferences {
        val values = mutableMapOf<String, String>()
        var failCommit = false
        val store = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0]] ?: args[1]
                "edit" -> editor()
                else -> error(method.name)
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, String?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString" -> { pending[args[0] as String] = args[1] as String?; proxy }
                    "remove" -> { pending[args[0] as String] = null; proxy }
                    "commit" -> {
                        if (!failCommit) pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        !failCommit
                    }
                    else -> error(method.name)
                }
            } as SharedPreferences.Editor
        }
    }
    private val alice = EmbySession("https://example.com", "server", "alice", "Alice", "test-only-a", false)
    private val bob = alice.copy(userId = "bob", userName = "Bob", accessToken = "test-only-b")
    private fun key() = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()

    @Test fun decryptionFailureCannotOverwriteOrEraseOtherAccounts() {
        val preferences = Preferences()
        val originalKey = key()
        var currentKey = originalKey
        val store = KeystoreSessionStore(preferences.store, Json { ignoreUnknownKeys = true }, { currentKey })
        store.save(alice); store.save(bob)
        val originalPayload = preferences.values.toMap()
        assertFalse(originalPayload.values.single().contains(alice.accessToken))
        currentKey = key()
        assertTrue(store.loadAll().isEmpty())
        assertFalse(store.select(alice))
        assertTrue(runCatching { store.remove(alice) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { store.save(alice) }.exceptionOrNull() is IllegalStateException)
        assertEquals(originalPayload, preferences.values)
        currentKey = originalKey
        assertEquals(listOf(alice, bob), store.loadAll())
        assertEquals(bob, store.load())
        store.remove(alice)
        assertEquals(listOf(bob), store.loadAll())
    }

    @Test fun unavailableKeystoreLeavesEncryptedAccountsRecoverable() {
        val preferences = Preferences()
        val key = key()
        var locked = false
        val store = KeystoreSessionStore(preferences.store, Json, {
            check(!locked) { "device locked" }; key
        })
        store.save(alice)
        val originalPayload = preferences.values.toMap()
        locked = true
        assertNull(store.load())
        assertTrue(runCatching { store.remove(alice) }.isFailure)
        assertEquals(originalPayload, preferences.values)
        locked = false
        assertEquals(alice, store.load())
    }

    @Test fun failedRemovalCommitIsReportedAndAccountRemainsReadable() {
        val preferences = Preferences()
        val key = key()
        val store = KeystoreSessionStore(preferences.store, Json, { key })
        store.save(alice)
        preferences.failCommit = true
        assertTrue(runCatching { store.remove(alice) }.exceptionOrNull() is IllegalStateException)
        assertEquals(alice, store.load())
    }
}
