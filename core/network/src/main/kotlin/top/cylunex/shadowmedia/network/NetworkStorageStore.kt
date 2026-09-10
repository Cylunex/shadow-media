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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.cylunex.shadowmedia.model.NetworkStorageConnection
import top.cylunex.shadowmedia.model.NetworkStorageKind

interface NetworkStorageStore {
    fun loadAll(): List<NetworkStorageConnection>
    fun save(connection: NetworkStorageConnection)
    fun remove(connectionId: String)
}

/** Stores every network credential as one authenticated AES-GCM payload backed by Android Keystore. */
class KeystoreNetworkStorageStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : NetworkStorageStore {
    private val preferences = context.getSharedPreferences("network_storage_connections", Context.MODE_PRIVATE)

    @Synchronized
    override fun loadAll(): List<NetworkStorageConnection> = read().orEmpty().map(StoredConnection::toModel)

    @Synchronized
    override fun save(connection: NetworkStorageConnection) {
        val rows = (read().orEmpty().filterNot { it.id == connection.id } + StoredConnection.from(connection))
            .sortedBy(StoredConnection::name)
        write(rows)
    }

    @Synchronized
    override fun remove(connectionId: String) = write(read().orEmpty().filterNot { it.id == connectionId })

    private fun read(): List<StoredConnection>? = runCatching {
        val encoded = preferences.getString(KEY, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_SIZE)))
        json.decodeFromString<List<StoredConnection>>(
            cipher.doFinal(payload.copyOfRange(IV_SIZE, payload.size)).decodeToString()
        )
    }.getOrElse {
        throw IllegalStateException("网络存储凭据无法解密，原数据已保留", it)
    }

    private fun write(rows: List<StoredConnection>) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(json.encodeToString(rows).encodeToByteArray())
        check(preferences.edit().putString(KEY, Base64.encodeToString(payload, Base64.NO_WRAP)).commit())
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY = "connections_v1"
        const val KEY_ALIAS = "shadow_media_network_storage_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

@Serializable
private data class StoredConnection(
    val id: String,
    val name: String,
    val kind: String,
    val address: String,
    val username: String,
    val password: String,
    val domain: String,
    val share: String,
    val rootPath: String,
    val allowInsecureHttp: Boolean,
    val readNfo: Boolean,
    val resolveStrm: Boolean,
) {
    fun toModel() = NetworkStorageConnection(
        id = id,
        name = name,
        kind = NetworkStorageKind.valueOf(kind),
        address = address,
        username = username,
        password = password,
        domain = domain,
        share = share,
        rootPath = rootPath,
        allowInsecureHttp = allowInsecureHttp,
        readNfo = readNfo,
        resolveStrm = resolveStrm,
    )

    companion object {
        fun from(value: NetworkStorageConnection) = StoredConnection(
            id = value.id,
            name = value.name,
            kind = value.kind.name,
            address = value.address,
            username = value.username,
            password = value.password,
            domain = value.domain,
            share = value.share,
            rootPath = value.rootPath,
            allowInsecureHttp = value.allowInsecureHttp,
            readNfo = value.readNfo,
            resolveStrm = value.resolveStrm,
        )
    }
}
