package com.shadow.media.library

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class CatalogKind { OPDS, KOMGA, AUDIOBOOKSHELF, JELLYFIN, OPENSUBSONIC }
fun CatalogKind.isNativeMusic() = this == CatalogKind.JELLYFIN || this == CatalogKind.OPENSUBSONIC
data class CatalogConnection(val id: String = UUID.randomUUID().toString(), val name: String, val kind: CatalogKind,
    val url: String, val username: String = "", val password: String = "", val token: String = "", val allowHttp: Boolean = false,
    val oidcIssuer: String = "", val oidcSubject: String = "", val oidcClientId: String = "", val oidcAuthState: String = "") {
    fun base(): HttpUrl = url.trim().toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null) { "地址不能包含内嵌密码、查询密钥或片段" }
        require(it.isHttps || allowHttp) { "请明确允许 HTTP 明文连接" }
    }
    override fun toString() = "CatalogConnection(id=$id, name=$name, kind=$kind, credentials=<redacted>)"

    /** Without a verified remote user id, any credential/endpoint change is a new sync scope. */
    fun retainsAccountOf(previous: CatalogConnection): Boolean = kind == previous.kind &&
        base() == previous.base() && if (oidcIssuer.isNotBlank() || previous.oidcIssuer.isNotBlank()) {
            oidcIssuer.isNotBlank() && oidcSubject.isNotBlank() && oidcIssuer == previous.oidcIssuer &&
                oidcSubject == previous.oidcSubject && oidcClientId == previous.oidcClientId
        } else username == previous.username && password == previous.password && token == previous.token
}

/** One authenticated, encrypted payload. Corrupt ciphertext is retained, never overwritten. */
class CatalogConnectionStore(context: Context, namespace: String = "publication_connections", private val keyAlias: String = "shadow_publications_v1",
    private val keyProvider: (() -> SecretKey)? = null) {
    private val preferences = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    fun load(): List<CatalogConnection> = synchronized(lock) {
        val value = preferences.getString("encrypted", null) ?: return@synchronized emptyList()
        try {
            val bytes = Base64.getDecoder().decode(value); require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val array = JSONArray(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
            return@synchronized (0 until array.length()).map { index -> val o = array.getJSONObject(index)
                CatalogConnection(o.getString("id"), o.getString("name"), CatalogKind.valueOf(o.getString("kind")), o.getString("url"), o.optString("username"), o.optString("password"), o.optString("token"), o.optBoolean("allowHttp"), o.optString("oidcIssuer"), o.optString("oidcSubject"), o.optString("oidcClientId"), o.optString("oidcAuthState"))
            }
        } catch (e: Exception) { throw IllegalStateException("来源凭据无法解密，原数据已保留", e) }
    }
    fun save(connection: CatalogConnection) = synchronized(lock) { connection.base(); write(load().filterNot { it.id == connection.id } + connection) }
    fun replace(previous: CatalogConnection?, edited: CatalogConnection) = synchronized(lock) {
        edited.base()
        val safe = if (previous != null && !edited.retainsAccountOf(previous)) edited.copy(id = UUID.randomUUID().toString()) else edited
        write(load().filterNot { it.id == previous?.id || it.id == safe.id } + safe)
    }
    fun remove(id: String) = synchronized(lock) { write(load().filterNot { it.id == id }) }
    fun updateAuthState(id: String, expected: String, updated: String): Boolean = synchronized(lock) {
        val current = load().singleOrNull { it.id == id } ?: return@synchronized false
        if (current.oidcAuthState != expected) return@synchronized false
        write(load().map { if (it.id == id) it.copy(oidcAuthState = updated) else it }); true
    }
    fun beginLogin(value: JSONObject) = synchronized(lock) { check(preferences.edit().putString("login_attempt", seal(value.toString())).commit()) }
    fun pendingLogin(): JSONObject? = synchronized(lock) { preferences.getString("login_attempt", null)?.let { JSONObject(unseal(it)) } }
    fun cancelLogin(id: String) = synchronized(lock) { if (pendingLogin()?.optString("attempt") == id) check(preferences.edit().remove("login_attempt").commit()) }
    fun consumeLogin(id: String): JSONObject? = synchronized(lock) {
        val pending = pendingLogin() ?: return@synchronized null
        if (pending.optString("attempt") != id || pending.optBoolean("consumed") ||
            System.currentTimeMillis() - pending.getLong("created") !in 0..600_000) return@synchronized null
        beginLogin(pending.put("consumed", true)); pending
    }
    fun finishLogin(id: String, edited: CatalogConnection): CatalogConnection = synchronized(lock) {
        val pending = pendingLogin()
        check(pending?.optString("attempt") == id && pending.optBoolean("consumed") &&
            System.currentTimeMillis() - pending.getLong("created") in 0..600_000) { "登录已取消或超时，请重试" }
        val previousId = pending!!.optString("previousId")
        val previous = load().singleOrNull { it.id == previousId }
        check(previousId.isBlank() || previous != null) { "连接已移除，请重试" }
        val safe = if (previous != null && edited.retainsAccountOf(previous)) edited.copy(id = previous.id) else edited.copy(id = UUID.randomUUID().toString())
        write(load().filterNot { it.id == previousId || it.id == safe.id } + safe)
        cancelLogin(id); safe
    }
    fun signOut(id: String): CatalogConnection? = synchronized(lock) {
        val current = load().singleOrNull { it.id == id } ?: return@synchronized null
        pendingLogin()?.takeIf { it.optString("previousId") == id }?.optString("attempt")?.let(::cancelLogin)
        write(load().map { if (it.id == id) it.copy(oidcAuthState = "") else it }); current
    }
    private fun write(connections: List<CatalogConnection>) {
        val array = JSONArray()
        connections.forEach { c -> array.put(JSONObject().put("id", c.id).put("name", c.name).put("kind", c.kind.name).put("url", c.url)
            .put("username", c.username).put("password", c.password).put("token", c.token).put("allowHttp", c.allowHttp)
            .put("oidcIssuer", c.oidcIssuer).put("oidcSubject", c.oidcSubject).put("oidcClientId", c.oidcClientId).put("oidcAuthState", c.oidcAuthState)) }
        check(preferences.edit().putString("encrypted", seal(array.toString())).commit())
    }
    private fun seal(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()))
    }
    private fun unseal(value: String): String {
        val bytes = Base64.getDecoder().decode(value); require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    companion object { private val lock = Any() }
    private fun key(): SecretKey {
        keyProvider?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
