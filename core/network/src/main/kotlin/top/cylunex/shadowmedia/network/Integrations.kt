package top.cylunex.shadowmedia.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import top.cylunex.shadowmedia.model.IntegrationConnection
import top.cylunex.shadowmedia.model.IntegrationHealth
import top.cylunex.shadowmedia.model.IntegrationKind
import top.cylunex.shadowmedia.model.IntegrationStatus

class DefaultIntegrationRepository(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : IntegrationRepository {
    override suspend fun probe(connection: IntegrationConnection): IntegrationStatus = withContext(Dispatchers.IO) {
        val base = connection.validatedBaseUrl()
        val started = System.nanoTime()
        var lastMessage = "服务没有响应"
        for (path in connection.kind.probePaths()) {
            val url = base.resolve(path) ?: continue
            val result = runCatching { executeProbe(connection, url) }
            result.getOrNull()?.let { probe ->
                val latency = (System.nanoTime() - started) / 1_000_000
                return@withContext IntegrationStatus(
                    connectionId = connection.id,
                    health = probe.health,
                    latencyMs = latency,
                    version = probe.version,
                    message = probe.message,
                    checkedAtEpochMs = System.currentTimeMillis(),
                )
            }
            lastMessage = result.exceptionOrNull()?.message ?: lastMessage
        }
        IntegrationStatus(
            connectionId = connection.id,
            health = IntegrationHealth.OFFLINE,
            message = lastMessage,
            checkedAtEpochMs = System.currentTimeMillis(),
        )
    }

    override suspend fun requestMedia(
        connection: IntegrationConnection,
        tmdbId: Int,
        mediaType: String,
    ): String = withContext(Dispatchers.IO) {
        require(connection.kind == IntegrationKind.SEERR) { "当前只对 Seerr 启用标准化请求写入" }
        require(tmdbId > 0) { "TMDB ID 无效" }
        require(mediaType == "movie" || mediaType == "tv") { "媒体类型必须是 movie 或 tv" }
        require(connection.apiToken.isNotBlank()) { "Seerr API Key 不能为空" }
        val url = connection.validatedBaseUrl().resolve("api/v1/request")
            ?: throw IllegalArgumentException("Seerr 地址无效")
        val payload = buildJsonObject {
            put("mediaType", mediaType)
            put("mediaId", tmdbId)
        }.toString().toRequestBody(JSON)
        val request = Request.Builder().url(url).post(payload).applyAuth(connection).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw IOException("Seerr API Key 无效或权限不足")
            if (!response.isSuccessful) throw IOException("Seerr 请求失败（HTTP ${response.code}）")
            "请求已提交到 Seerr"
        }
    }

    override fun virtualChannelUrls(connection: IntegrationConnection): Pair<String, String?>? {
        val base = runCatching { connection.validatedBaseUrl() }.getOrNull() ?: return null
        val playlist = (
            connection.playlistUrl?.validatedChild(connection)
                ?: if (connection.kind == IntegrationKind.TUNARR) {
                    base.resolve("api/channels.m3u")?.toString()
                } else null
            ) ?: return null
        val epg = connection.epgUrl?.validatedChild(connection)
            ?: if (connection.kind == IntegrationKind.TUNARR) base.resolve("api/xmltv.xml")?.toString() else null
        return playlist to epg
    }

    private fun executeProbe(connection: IntegrationConnection, url: HttpUrl): ProbeResult {
        val request = Request.Builder().url(url).get().applyAuth(connection).build()
        client.newCall(request).execute().use { response ->
            if (url.scheme == "https" && response.request.url.scheme != "https") {
                throw IOException("已拒绝 HTTPS 降级重定向")
            }
            if (response.code == 401 || response.code == 403) {
                return ProbeResult(IntegrationHealth.AUTH_REQUIRED, null, "服务在线，但凭据无效或权限不足")
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            if (body.contentLength() > MAX_PROBE_BYTES) throw IOException("诊断响应过大")
            val bytes = body.byteStream().use { input ->
                val output = ByteArrayOutputStream(8 * 1024)
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_PROBE_BYTES) throw IOException("诊断响应过大")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            val version = runCatching {
                val root = json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
                listOf("version", "appVersion", "currentVersion").firstNotNullOfOrNull { key ->
                    (root?.get(key) as? JsonPrimitive)?.contentOrNull
                }
            }.getOrNull()
            return ProbeResult(IntegrationHealth.ONLINE, version, "连接正常")
        }
    }

    private fun Request.Builder.applyAuth(connection: IntegrationConnection): Request.Builder = apply {
        val token = connection.apiToken.trim().takeIf(String::isNotEmpty) ?: return@apply
        when (connection.kind) {
            IntegrationKind.SEERR, IntegrationKind.DISPATCHARR -> header("X-Api-Key", token)
            IntegrationKind.MOVIEPILOT, IntegrationKind.TUNARR -> header("Authorization", "Bearer $token")
        }
    }

    private fun IntegrationConnection.validatedBaseUrl(): HttpUrl {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("服务地址格式无效")
        require(url.username.isEmpty() && url.password.isEmpty()) { "服务地址不能内嵌账号密码" }
        require(url.scheme == "https" || (url.scheme == "http" && allowInsecureHttp)) {
            "HTTP 服务需要明确允许局域网明文连接"
        }
        return url.newBuilder().apply {
            if (!url.encodedPath.endsWith('/')) addPathSegment("")
        }.build()
    }

    private fun String.validatedChild(connection: IntegrationConnection): String? {
        val value = toHttpUrlOrNull() ?: return null
        if (value.username.isNotEmpty() || value.password.isNotEmpty()) return null
        if (value.scheme != "https" && !(value.scheme == "http" && connection.allowInsecureHttp)) return null
        return value.toString()
    }

    private fun IntegrationKind.probePaths(): List<String> = when (this) {
        IntegrationKind.MOVIEPILOT -> listOf("api/v1/system/env", "")
        IntegrationKind.SEERR -> listOf("api/v1/status", "")
        IntegrationKind.TUNARR -> listOf("api/system/health", "api/version", "")
        IntegrationKind.DISPATCHARR -> listOf("api/core/version/", "swagger/", "")
    }

    private data class ProbeResult(
        val health: IntegrationHealth,
        val version: String?,
        val message: String,
    )

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_PROBE_BYTES = 512 * 1024
    }
}

class KeystoreIntegrationStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : IntegrationStore {
    private val preferences = context.getSharedPreferences("integration_connections", Context.MODE_PRIVATE)

    @Synchronized
    override fun loadAll(): List<IntegrationConnection> = read().orEmpty().map(StoredIntegration::toModel)

    @Synchronized
    override fun save(connection: IntegrationConnection) {
        val rows = (read().orEmpty().filterNot { it.id == connection.id } + StoredIntegration.from(connection))
            .sortedBy(StoredIntegration::name)
        write(rows)
    }

    @Synchronized
    override fun remove(connectionId: String) = write(read().orEmpty().filterNot { it.id == connectionId })

    private fun read(): List<StoredIntegration>? = runCatching {
        val encoded = preferences.getString(KEY, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_SIZE)))
        json.decodeFromString<List<StoredIntegration>>(
            cipher.doFinal(payload.copyOfRange(IV_SIZE, payload.size)).decodeToString()
        )
    }.getOrElse {
        preferences.edit().remove(KEY).commit()
        null
    }

    private fun write(rows: List<StoredIntegration>) {
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
        const val KEY_ALIAS = "shadow_media_integrations_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

@Serializable
private data class StoredIntegration(
    val id: String,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val apiToken: String,
    val allowInsecureHttp: Boolean,
    val playlistUrl: String? = null,
    val epgUrl: String? = null,
) {
    fun toModel() = IntegrationConnection(
        id,
        name,
        IntegrationKind.valueOf(kind),
        baseUrl,
        apiToken,
        allowInsecureHttp,
        playlistUrl,
        epgUrl,
    )

    companion object {
        fun from(value: IntegrationConnection) = StoredIntegration(
            value.id,
            value.name,
            value.kind.name,
            value.baseUrl,
            value.apiToken,
            value.allowInsecureHttp,
            value.playlistUrl,
            value.epgUrl,
        )
    }
}
