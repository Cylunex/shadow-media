package top.cylunex.shadowmedia.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.MessageDigest
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import top.cylunex.shadowmedia.model.ExternalSourceKind
import top.cylunex.shadowmedia.model.ExternalSourceSummary

class SafeExternalSourceRepository(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ExternalSourceRepository {
    override suspend fun inspect(url: String, allowInsecureHttp: Boolean): ExternalSourceSummary =
        withContext(Dispatchers.IO) {
            val sourceUrl = url.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("配置地址格式无效")
            require(sourceUrl.username.isEmpty() && sourceUrl.password.isEmpty()) { "配置地址不能包含账号密码" }
            require(sourceUrl.scheme == "https" || sourceUrl.scheme == "http") { "仅支持 HTTP 或 HTTPS 配置" }
            require(sourceUrl.scheme == "https" || allowInsecureHttp) {
                "HTTP 配置未加密，请确认它来自受信任的局域网"
            }
            val request = Request.Builder()
                .url(sourceUrl)
                .header("Accept", "application/json, application/x-mpegURL, text/plain;q=0.8")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("配置读取失败（HTTP ${response.code}）")
                if (sourceUrl.scheme == "https" && response.request.url.scheme != "https") {
                    throw IOException("已拒绝 HTTPS 降级到 HTTP 的配置重定向")
                }
                val body = response.body
                if (body.contentLength() > MAX_CONFIG_BYTES) throw IOException("配置超过 2 MiB 安全上限")
                val bytes = body.source().readByteArray(MAX_CONFIG_BYTES + 1L)
                if (bytes.size > MAX_CONFIG_BYTES) throw IOException("配置超过 2 MiB 安全上限")
                inspectPayload(response.request.url.toString(), bytes.decodeToString())
            }
        }

    internal fun inspectPayload(url: String, payload: String): ExternalSourceSummary {
        val normalized = payload.removePrefix("\uFEFF").trim()
        require(normalized.isNotEmpty()) { "配置内容为空" }
        return if (normalized.startsWith("{")) {
            inspectJson(url, normalized)
        } else if (normalized.startsWith("#EXTM3U", ignoreCase = true) || "#genre#" in normalized) {
            val channelCount = normalized.lineSequence().count {
                it.trimStart().startsWith("#EXTINF", ignoreCase = true) || "#genre#" in it
            }
            ExternalSourceSummary(
                id = url.stableId(),
                name = url.toHttpUrlOrNull()?.host ?: "直播订阅",
                url = url,
                kind = ExternalSourceKind.LIVE_PLAYLIST,
                liveCount = channelCount,
                inspectedAtEpochMs = System.currentTimeMillis(),
            )
        } else {
            throw IllegalArgumentException("暂不支持此配置格式；首版支持 TVBox JSON、M3U 与 TXT")
        }
    }

    private fun inspectJson(url: String, payload: String): ExternalSourceSummary {
        val root = json.parseToJsonElement(payload).jsonObject
        val sites = root["sites"] as? JsonArray ?: JsonArray(emptyList())
        val lives = root["lives"]
        val remoteApiCount = sites.count { element ->
            val api = (element as? JsonObject)?.get("api")?.jsonPrimitive?.contentOrNull.orEmpty()
            api.startsWith("https://") || api.startsWith("http://")
        }
        val runtimeCount = sites.size - remoteApiCount
        val liveCount = when (lives) {
            is JsonArray -> lives.size
            null -> 0
            else -> 1
        }
        val name = root["name"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf(String::isNotEmpty)
            ?: url.toHttpUrlOrNull()?.host
            ?: "影视仓配置"
        return ExternalSourceSummary(
            id = url.stableId(),
            name = name,
            url = url,
            kind = ExternalSourceKind.TVBOX_CONFIG,
            siteCount = sites.size,
            liveCount = liveCount,
            safeSiteCount = remoteApiCount,
            runtimeRequiredCount = runtimeCount,
            inspectedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun String.stableId(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_CONFIG_BYTES = 2 * 1024 * 1024
    }
}

class SharedPreferencesExternalSourceStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : ExternalSourceStore {
    private val preferences = context.getSharedPreferences("external_source_subscriptions", Context.MODE_PRIVATE)

    @Synchronized
    override fun loadAll(): List<ExternalSourceSummary> = readStored()
        ?.sources
        ?.map(StoredExternalSourceDto::toModel)
        .orEmpty()
        .sortedByDescending(ExternalSourceSummary::inspectedAtEpochMs)

    @Synchronized
    override fun save(source: ExternalSourceSummary) {
        val updated = (loadAll().filterNot { it.id == source.id } + source)
            .sortedByDescending(ExternalSourceSummary::inspectedAtEpochMs)
        writeStored(StoredExternalSourcesDto(updated.map(StoredExternalSourceDto::fromModel)))
    }

    @Synchronized
    override fun remove(sourceId: String) {
        val updated = loadAll().filterNot { it.id == sourceId }
        writeStored(StoredExternalSourcesDto(updated.map(StoredExternalSourceDto::fromModel)))
    }

    private fun readStored(): StoredExternalSourcesDto? = runCatching {
        val encoded = preferences.getString(KEY_SOURCES, null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE) { "Invalid encrypted source subscriptions" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_SIZE)),
        )
        val plaintext = cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)).decodeToString()
        json.decodeFromString<StoredExternalSourcesDto>(plaintext)
    }.getOrElse {
        preferences.edit().remove(KEY_SOURCES).commit()
        null
    }

    private fun writeStored(stored: StoredExternalSourcesDto) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(json.encodeToString(stored).encodeToByteArray())
        val payload = cipher.iv + encrypted
        check(
            preferences.edit()
                .putString(KEY_SOURCES, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit()
        ) { "无法保存外部源订阅" }
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

    private companion object {
        const val KEY_SOURCES = "sources_v1"
        const val KEY_ALIAS = "shadow_media_external_sources_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

@Serializable
private data class StoredExternalSourcesDto(val sources: List<StoredExternalSourceDto> = emptyList())

@Serializable
private data class StoredExternalSourceDto(
    val id: String,
    val name: String,
    val url: String,
    val kind: String,
    val siteCount: Int,
    val liveCount: Int,
    val safeSiteCount: Int,
    val runtimeRequiredCount: Int,
    val inspectedAtEpochMs: Long,
) {
    fun toModel(): ExternalSourceSummary = ExternalSourceSummary(
        id = id,
        name = name,
        url = url,
        kind = runCatching { ExternalSourceKind.valueOf(kind) }.getOrDefault(ExternalSourceKind.TVBOX_CONFIG),
        siteCount = siteCount,
        liveCount = liveCount,
        safeSiteCount = safeSiteCount,
        runtimeRequiredCount = runtimeRequiredCount,
        inspectedAtEpochMs = inspectedAtEpochMs,
    )

    companion object {
        fun fromModel(source: ExternalSourceSummary): StoredExternalSourceDto = StoredExternalSourceDto(
            id = source.id,
            name = source.name,
            url = source.url,
            kind = source.kind.name,
            siteCount = source.siteCount,
            liveCount = source.liveCount,
            safeSiteCount = source.safeSiteCount,
            runtimeRequiredCount = source.runtimeRequiredCount,
            inspectedAtEpochMs = source.inspectedAtEpochMs,
        )
    }
}
