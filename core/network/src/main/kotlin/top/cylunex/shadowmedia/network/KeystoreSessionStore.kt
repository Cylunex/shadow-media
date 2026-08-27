package top.cylunex.shadowmedia.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.cylunex.shadowmedia.model.EmbySession

class KeystoreSessionStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : SessionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): EmbySession? = runCatching {
        val encoded = preferences.getString(KEY_PAYLOAD, null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE) { "Invalid encrypted session" }
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        val stored = json.decodeFromString<StoredSessionDto>(cipher.doFinal(encrypted).decodeToString())
        stored.toDomain()
    }.getOrElse {
        clear()
        null
    }

    override fun save(session: EmbySession) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val plaintext = json.encodeToString(session.toStored()).encodeToByteArray()
        val encrypted = cipher.doFinal(plaintext)
        val payload = cipher.iv + encrypted
        preferences.edit().putString(KEY_PAYLOAD, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_PAYLOAD).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun EmbySession.toStored() = StoredSessionDto(
        serverUrl, serverId, userId, userName, accessToken, allowInsecureHttp
    )

    private fun StoredSessionDto.toDomain() = EmbySession(
        serverUrl, serverId, userId, userName, accessToken, allowInsecureHttp
    )

    companion object {
        private const val PREFERENCES_NAME = "secure_session"
        private const val KEY_PAYLOAD = "session_payload"
        private const val KEY_ALIAS = "shadow_media_session_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
