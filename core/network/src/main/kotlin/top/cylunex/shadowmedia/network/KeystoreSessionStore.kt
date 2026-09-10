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
import kotlinx.serialization.json.jsonObject
import top.cylunex.shadowmedia.model.EmbySession

class KeystoreSessionStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : SessionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var readFailed = false

    @Synchronized
    override fun load(): EmbySession? {
        val stored = readStored()
        return stored.sessions.firstOrNull { it.sessionKey == stored.activeSessionKey }?.toDomain()
            ?: stored.sessions.firstOrNull()?.toDomain()
    }

    @Synchronized
    override fun loadAll(): List<EmbySession> = readStored().sessions.map { it.toDomain() }

    @Synchronized
    override fun save(session: EmbySession) {
        val current = readStored()
        check(!readFailed) { "系统暂时无法解锁已有账号，请解锁设备后重试；已保留原登录信息" }
        val next = current.sessions.filterNot { it.sessionKey == session.sessionKey } + session.toStored()
        writeStored(StoredSessionsDto(activeSessionKey = session.sessionKey, sessions = next))
    }

    @Synchronized
    override fun select(session: EmbySession): Boolean {
        val current = readStored()
        if (current.sessions.none { it.sessionKey == session.sessionKey }) return false
        writeStored(current.copy(activeSessionKey = session.sessionKey))
        return true
    }

    @Synchronized
    override fun remove(session: EmbySession) {
        val current = readStored()
        val remaining = current.sessions.filterNot { it.sessionKey == session.sessionKey }
        val activeKey = current.activeSessionKey
            ?.takeUnless { it == session.sessionKey }
            ?.takeIf { key -> remaining.any { it.sessionKey == key } }
            ?: remaining.firstOrNull()?.sessionKey
        if (remaining.isEmpty()) clearAll() else writeStored(
            StoredSessionsDto(activeSessionKey = activeKey, sessions = remaining)
        )
    }

    @Synchronized
    override fun clearAll() {
        preferences.edit().remove(KEY_PAYLOAD).commit()
    }

    private fun readStored(): StoredSessionsDto = runCatching {
        readFailed = false
        val encoded = preferences.getString(KEY_PAYLOAD, null) ?: return StoredSessionsDto()
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE) { "Invalid encrypted session" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_SIZE)),
        )
        val plaintext = cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)).decodeToString()
        val element = json.parseToJsonElement(plaintext).jsonObject
        if ("sessions" in element) {
            json.decodeFromString<StoredSessionsDto>(plaintext)
        } else {
            val legacy = json.decodeFromString<StoredSessionDto>(plaintext)
            StoredSessionsDto(legacy.sessionKey, listOf(legacy)).also(::writeStored)
        }
    }.getOrElse {
        readFailed = true
        StoredSessionsDto()
    }

    private fun writeStored(stored: StoredSessionsDto) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(json.encodeToString(stored).encodeToByteArray())
        val payload = cipher.iv + encrypted
        check(
            preferences.edit()
                .putString(KEY_PAYLOAD, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit()
        ) { "无法保存 Emby 登录信息" }
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

    private val EmbySession.sessionKey: String get() = "$serverUrl|$serverId|$userId"
    private val StoredSessionDto.sessionKey: String get() = "$serverUrl|$serverId|$userId"

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
