package top.cylunex.shadowmedia.library

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
    val url: String, val username: String = "", val password: String = "", val token: String = "", val allowHttp: Boolean = false) {
    fun base(): HttpUrl = url.trim().toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null) { "地址不能包含内嵌密码、查询密钥或片段" }
        require(it.isHttps || allowHttp) { "请明确允许 HTTP 明文连接" }
    }
    override fun toString() = "CatalogConnection(id=$id, name=$name, kind=$kind, credentials=<redacted>)"

    /** Without a verified remote user id, any credential/endpoint change is a new sync scope. */
    fun retainsAccountOf(previous: CatalogConnection): Boolean = kind == previous.kind &&
        base() == previous.base() && username == previous.username && password == previous.password && token == previous.token
}

/** One authenticated, encrypted payload. Corrupt ciphertext is retained, never overwritten. */
class CatalogConnectionStore(context: Context) {
    private val preferences = context.getSharedPreferences("publication_connections", Context.MODE_PRIVATE)
    @Synchronized fun load(): List<CatalogConnection> {
        val value = preferences.getString("encrypted", null) ?: return emptyList()
        try {
            val bytes = Base64.getDecoder().decode(value); require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val array = JSONArray(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
            return (0 until array.length()).map { index -> val o = array.getJSONObject(index)
                CatalogConnection(o.getString("id"), o.getString("name"), CatalogKind.valueOf(o.getString("kind")), o.getString("url"), o.optString("username"), o.optString("password"), o.optString("token"), o.optBoolean("allowHttp"))
            }
        } catch (e: Exception) { throw IllegalStateException("来源凭据无法解密，原数据已保留", e) }
    }
    @Synchronized fun save(connection: CatalogConnection) { connection.base(); write(load().filterNot { it.id == connection.id } + connection) }
    @Synchronized fun replace(previous: CatalogConnection?, edited: CatalogConnection) {
        edited.base()
        val safe = if (previous != null && !edited.retainsAccountOf(previous)) edited.copy(id = UUID.randomUUID().toString()) else edited
        write(load().filterNot { it.id == previous?.id || it.id == safe.id } + safe)
    }
    @Synchronized fun remove(id: String) = write(load().filterNot { it.id == id })
    private fun write(connections: List<CatalogConnection>) {
        val array = JSONArray()
        connections.forEach { c -> array.put(JSONObject().put("id", c.id).put("name", c.name).put("kind", c.kind.name).put("url", c.url)
            .put("username", c.username).put("password", c.password).put("token", c.token).put("allowHttp", c.allowHttp)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        check(preferences.edit().putString("encrypted", Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(array.toString().toByteArray()))).commit())
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("shadow_publications_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("shadow_publications_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
