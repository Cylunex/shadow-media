package top.cylunex.shadowmedia.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.KeyStore
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
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
import top.cylunex.shadowmedia.model.ExternalSourceImport
import top.cylunex.shadowmedia.model.ExternalMediaEntry
import top.cylunex.shadowmedia.model.ExternalCatalogSite
import top.cylunex.shadowmedia.model.ExternalSourceSummary

class SafeExternalSourceRepository(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ExternalSourceRepository {
    override suspend fun importFromUrl(url: String, allowInsecureHttp: Boolean): ExternalSourceImport =
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
            val imported = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("配置读取失败（HTTP ${response.code}）")
                if (sourceUrl.scheme == "https" && response.request.url.scheme != "https") {
                    throw IOException("已拒绝 HTTPS 降级到 HTTP 的配置重定向")
                }
                val body = response.body
                if (body.contentType()?.type == "image") {
                    throw IllegalArgumentException("地址返回的是图片，不是可导入的视频源配置")
                }
                if (body.contentLength() > MAX_CONFIG_BYTES) throw IOException("配置超过 2 MiB 安全上限")
                val bytes = body.readLimitedBytes(MAX_CONFIG_BYTES)
                importPayload(response.request.url.toString(), bytes.decodeToString())
            }
            val policyAware = imported.copy(
                summary = imported.summary.copy(allowInsecureHttp = allowInsecureHttp)
            )
            if (
                policyAware.summary.kind == ExternalSourceKind.TVBOX_CONFIG ||
                policyAware.summary.kind == ExternalSourceKind.DECLARATIVE
            ) {
                policyAware.copy(
                    resolvedEntries = resolveTvBoxEntries(policyAware, allowInsecureHttp),
                    resolvedCatalogSites = resolveTvBoxCatalogSites(policyAware, allowInsecureHttp),
                )
            } else {
                policyAware
            }
        }

    override fun importPayload(url: String, payload: String, displayName: String?): ExternalSourceImport {
        val normalized = payload.removePrefix("\uFEFF").trim()
        require(normalized.isNotEmpty()) { "配置内容为空" }
        require(normalized.encodeToByteArray().size <= MAX_CONFIG_BYTES) { "配置超过 2 MiB 安全上限" }
        require(!normalized.startsWith("<")) { "地址返回的是网页，不是 TVBox、M3U 或 TXT 视频源" }
        require(!looksLikeEncryptedPayload(normalized)) { "这是需要专用运行时解密的视频源，无法安全直接导入" }
        val summary = if (normalized.startsWith("{")) {
            inspectJson(url, normalized, displayName)
        } else if (looksLikePlaylist(normalized)) {
            val placeholder = ExternalSourceSummary(
                id = url.stableId(),
                name = displayName?.trim()?.takeIf(String::isNotEmpty)
                    ?: url.toHttpUrlOrNull()?.host
                    ?: "本地视频源",
                url = url,
                kind = ExternalSourceKind.LIVE_PLAYLIST,
                inspectedAtEpochMs = System.currentTimeMillis(),
            )
            placeholder.copy(liveCount = parsePlaylist(placeholder, normalized).size)
        } else {
            throw IllegalArgumentException("暂不支持此配置格式；首版支持 TVBox JSON、M3U 与 TXT")
        }
        return ExternalSourceImport(summary, normalized)
    }

    override fun entries(source: ExternalSourceImport): List<ExternalMediaEntry> =
        if (source.resolvedEntries.isNotEmpty()) {
            source.resolvedEntries
        } else if (source.summary.kind == ExternalSourceKind.LIVE_PLAYLIST) {
            parsePlaylist(source.summary, source.payload)
        } else {
            emptyList()
        }

    override fun catalogSites(source: ExternalSourceImport): List<ExternalCatalogSite> {
        if (source.resolvedCatalogSites.isNotEmpty()) return source.resolvedCatalogSites
        if (!source.payload.trimStart().startsWith("{")) return emptyList()
        val root = runCatching { parseJsonObject(source.payload) }.getOrNull() ?: return emptyList()
        val sites = root["sites"] as? JsonArray ?: return emptyList()
        return sites.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val rawApi = item["api"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (!rawApi.startsWith("https://") && !rawApi.startsWith("http://")) return@mapNotNull null
            val api = source.summary.url.toHttpUrlOrNull()?.resolve(rawApi)
                ?: rawApi.toHttpUrlOrNull()
                ?: return@mapNotNull null
            if (api.username.isNotEmpty() || api.password.isNotEmpty() || isLoopbackHost(api.host)) {
                return@mapNotNull null
            }
            if (api.scheme != "https" && !(api.scheme == "http" && source.summary.allowInsecureHttp)) {
                return@mapNotNull null
            }
            val key = item["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val name = item["name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty) ?: key.ifEmpty { api.host }
            ExternalCatalogSite(
                id = "${source.summary.id}:${key.ifEmpty { api.toString().stableId() }}",
                sourceId = source.summary.id,
                name = name,
                apiUrl = api.toString(),
                allowInsecureHttp = source.summary.allowInsecureHttp,
            )
        }.distinctBy(ExternalCatalogSite::id).take(MAX_CATALOG_SITES)
    }

    private suspend fun resolveTvBoxCatalogSites(
        source: ExternalSourceImport,
        allowInsecureHttp: Boolean,
    ): List<ExternalCatalogSite> = supervisorScope {
        val direct = catalogSites(source)
        val nested = remoteSources(source.payload, "urls").map { reference ->
            async(Dispatchers.IO) {
                val imported = runCatching {
                    fetchNestedImport(source.summary.url, reference, allowInsecureHttp)
                }.getOrNull() ?: return@async emptyList()
                catalogSites(imported).map { site ->
                    site.copy(
                        id = "${source.summary.id}:${site.id}",
                        sourceId = source.summary.id,
                        name = listOf(reference.name, site.name).filter(String::isNotBlank).joinToString(" · "),
                        allowInsecureHttp = allowInsecureHttp,
                    )
                }
            }
        }.awaitAll().flatten()
        (direct + nested).distinctBy(ExternalCatalogSite::apiUrl).take(MAX_CATALOG_SITES)
    }

    private fun inspectJson(url: String, payload: String, displayName: String?): ExternalSourceSummary {
        val root = parseJsonObject(payload)
        val sites = root["sites"] as? JsonArray ?: JsonArray(emptyList())
        val catalogs = root["urls"] as? JsonArray ?: JsonArray(emptyList())
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
        val name = displayName?.trim()?.takeIf(String::isNotEmpty)
            ?: root["name"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf(String::isNotEmpty)
            ?: url.toHttpUrlOrNull()?.host
            ?: "影视仓配置"
        return ExternalSourceSummary(
            id = url.stableId(),
            name = name,
            url = url,
            kind = if (catalogs.isNotEmpty() && sites.isEmpty()) {
                ExternalSourceKind.DECLARATIVE
            } else {
                ExternalSourceKind.TVBOX_CONFIG
            },
            siteCount = if (catalogs.isNotEmpty() && sites.isEmpty()) catalogs.size else sites.size,
            liveCount = liveCount,
            safeSiteCount = if (catalogs.isNotEmpty() && sites.isEmpty()) {
                catalogs.count { element ->
                    val address = (element as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
                    address.startsWith("https://") || address.startsWith("http://")
                }
            } else {
                remoteApiCount
            },
            runtimeRequiredCount = runtimeCount,
            inspectedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun looksLikeEncryptedPayload(payload: String): Boolean {
        if (payload.length < 512) return false
        return payload.take(512).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    private suspend fun resolveTvBoxEntries(
        source: ExternalSourceImport,
        allowInsecureHttp: Boolean,
    ): List<ExternalMediaEntry> = supervisorScope {
        val catalogSources = remoteSources(source.payload, "urls")
        val directLives = async { resolveTvBoxLives(source, allowInsecureHttp) }
        val catalogEntries = catalogSources.map { reference ->
            async(Dispatchers.IO) {
                val nested = runCatching {
                    fetchNestedImport(source.summary.url, reference, allowInsecureHttp)
                }.getOrNull() ?: return@async emptyList()
                when (nested.summary.kind) {
                    ExternalSourceKind.LIVE_PLAYLIST -> entries(nested).map {
                        it.copy(
                            sourceId = source.summary.id,
                            group = listOfNotNull(reference.name.takeIf(String::isNotEmpty), it.group)
                                .joinToString(" · ").takeIf(String::isNotEmpty),
                        )
                    }
                    ExternalSourceKind.TVBOX_CONFIG -> resolveTvBoxLives(nested, allowInsecureHttp).map {
                        it.copy(
                            sourceId = source.summary.id,
                            group = listOfNotNull(reference.name.takeIf(String::isNotEmpty), it.group)
                                .joinToString(" · ").takeIf(String::isNotEmpty),
                        )
                    }
                    ExternalSourceKind.DECLARATIVE -> emptyList()
                }
            }
        }.awaitAll().flatten()
        (directLives.await() + catalogEntries).distinctBy(ExternalMediaEntry::url).take(MAX_PLAYLIST_ENTRIES)
    }

    private suspend fun resolveTvBoxLives(
        source: ExternalSourceImport,
        allowInsecureHttp: Boolean,
    ): List<ExternalMediaEntry> = supervisorScope {
        val lives = remoteSources(source.payload, "lives")

        lives.map { live ->
            async(Dispatchers.IO) {
                runCatching { fetchNestedLiveEntries(source.summary, live, allowInsecureHttp) }
                    .getOrDefault(emptyList())
            }
        }.awaitAll().flatten().take(MAX_PLAYLIST_ENTRIES)
    }

    private fun fetchNestedLiveEntries(
        parent: ExternalSourceSummary,
        live: TvBoxRemoteSource,
        allowInsecureHttp: Boolean,
    ): List<ExternalMediaEntry> {
        val nested = fetchNestedImport(parent.url, live, allowInsecureHttp) ?: return emptyList()
        if (nested.summary.kind != ExternalSourceKind.LIVE_PLAYLIST) return emptyList()
        return entries(nested).map { entry ->
            entry.copy(
                sourceId = parent.id,
                group = listOfNotNull(
                    live.name.takeIf(String::isNotEmpty),
                    entry.group,
                ).joinToString(" · ").takeIf(String::isNotEmpty),
                requestHeaders = if (live.userAgent == null || "User-Agent" in entry.requestHeaders) {
                    entry.requestHeaders
                } else {
                    entry.requestHeaders + ("User-Agent" to live.userAgent)
                },
                epgUrl = entry.epgUrl ?: live.epgUrl?.let { epg ->
                    parent.url.toHttpUrlOrNull()?.resolve(epg)?.toString() ?: epg
                },
            )
        }
    }

    private fun fetchNestedImport(
        parentUrl: String,
        source: TvBoxRemoteSource,
        allowInsecureHttp: Boolean,
    ): ExternalSourceImport? {
        val sourceUrl = parentUrl.toHttpUrlOrNull()?.resolve(source.url)
            ?: source.url.toHttpUrlOrNull()
            ?: return null
        if (sourceUrl.username.isNotEmpty() || sourceUrl.password.isNotEmpty()) return null
        if (isLoopbackHost(sourceUrl.host)) return null
        if (sourceUrl.scheme != "https" && !(sourceUrl.scheme == "http" && allowInsecureHttp)) return null
        val request = Request.Builder()
            .url(sourceUrl)
            .header("Accept", "application/x-mpegURL, text/plain, application/json;q=0.5")
            .apply { source.userAgent?.let { header("User-Agent", it) } }
            .build()
        val call = client.newCall(request).apply {
            timeout().timeout(NESTED_SOURCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        return call.execute().use { response ->
            if (!response.isSuccessful) return null
            if (sourceUrl.scheme == "https" && response.request.url.scheme != "https") return null
            if (response.body.contentType()?.type == "image") return null
            val body = response.body
            if (body.contentLength() > MAX_CONFIG_BYTES) return null
            val bytes = runCatching { body.readLimitedBytes(MAX_CONFIG_BYTES) }.getOrNull() ?: return null
            runCatching {
                importPayload(response.request.url.toString(), bytes.decodeToString(), source.name.ifBlank { null })
            }.getOrNull()
        }
    }

    private fun remoteSources(payload: String, key: String): List<TvBoxRemoteSource> =
        (parseJsonObject(payload)[key] as? JsonArray)
            .orEmpty()
            .mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = item["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (url.isEmpty()) return@mapNotNull null
                TvBoxRemoteSource(
                    name = item["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    url = url,
                    userAgent = item["ua"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty),
                    epgUrl = item["epg"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty),
                )
            }
            .take(MAX_NESTED_LIVE_SOURCES)

    private fun isLoopbackHost(host: String): Boolean =
        host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"

    private fun okhttp3.ResponseBody.readLimitedBytes(maxBytes: Int): ByteArray =
        byteStream().use { input ->
            val output = ByteArrayOutputStream(minOf(maxBytes, SOURCE_BUFFER_SIZE))
            val buffer = ByteArray(SOURCE_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("配置超过 2 MiB 安全上限")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }

    private fun parseJsonObject(payload: String): JsonObject =
        json.parseToJsonElement(stripJsonComments(payload)).jsonObject

    private fun stripJsonComments(payload: String): String {
        val result = StringBuilder(payload.length)
        var index = 0
        var inString = false
        var escaped = false
        var lineComment = false
        var blockComment = false
        while (index < payload.length) {
            val current = payload[index]
            val next = payload.getOrNull(index + 1)
            when {
                lineComment -> {
                    if (current == '\n' || current == '\r') {
                        lineComment = false
                        result.append(current)
                    }
                }
                blockComment -> {
                    if (current == '*' && next == '/') {
                        blockComment = false
                        index++
                    } else if (current == '\n' || current == '\r') {
                        result.append(current)
                    }
                }
                inString -> {
                    result.append(current)
                    when {
                        escaped -> escaped = false
                        current == '\\' -> escaped = true
                        current == '"' -> inString = false
                    }
                }
                current == '"' -> {
                    inString = true
                    result.append(current)
                }
                current == '/' && next == '/' -> {
                    lineComment = true
                    index++
                }
                current == '/' && next == '*' -> {
                    blockComment = true
                    index++
                }
                else -> result.append(current)
            }
            index++
        }
        return result.toString()
    }

    private fun looksLikePlaylist(payload: String): Boolean {
        if (payload.startsWith("#EXTM3U", ignoreCase = true) || payload.contains("#genre#", ignoreCase = true)) {
            return true
        }
        return payload.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .take(10)
            .any { line ->
                val candidate = line.substringAfter(',', "").substringBefore('|').trim()
                candidate.startsWith("https://") || candidate.startsWith("http://")
            }
    }

    private fun parsePlaylist(
        source: ExternalSourceSummary,
        payload: String,
    ): List<ExternalMediaEntry> {
        val result = mutableListOf<ExternalMediaEntry>()
        var pendingTitle: String? = null
        var pendingGroup: String? = null
        var pendingLogo: String? = null
        var pendingEpgId: String? = null
        var pendingCatchupSource: String? = null
        var pendingCatchupDays: Int? = null
        var playlistEpgUrl: String? = null
        var txtGroup: String? = null

        payload.lineSequence().forEach { rawLine ->
            if (result.size >= MAX_PLAYLIST_ENTRIES) return@forEach
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attributes = ATTRIBUTE_PATTERN.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    playlistEpgUrl = sequenceOf(attributes["x-tvg-url"], attributes["url-tvg"])
                        .firstOrNull { !it.isNullOrBlank() }
                        ?.substringBefore(',')
                        ?.trim()
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attributes = ATTRIBUTE_PATTERN.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    pendingTitle = line.substringAfterLast(',', "").trim()
                        .ifEmpty { attributes["tvg-name"].orEmpty() }
                    pendingGroup = attributes["group-title"]?.trim()?.takeIf(String::isNotEmpty)
                    pendingLogo = attributes["tvg-logo"]?.trim()?.takeIf(String::isNotEmpty)
                    pendingEpgId = attributes["tvg-id"]?.trim()?.takeIf(String::isNotEmpty)
                    pendingCatchupSource = attributes["catchup-source"]?.trim()?.takeIf(String::isNotEmpty)
                    pendingCatchupDays = attributes["catchup-days"]?.toIntOrNull()
                        ?: attributes["timeshift"]?.toIntOrNull()
                }
                line.contains("#genre#", ignoreCase = true) -> {
                    txtGroup = line.substringBefore(',').trim().takeIf(String::isNotEmpty)
                }
                line.startsWith("#") -> Unit
                pendingTitle != null -> {
                    createEntry(
                        source = source,
                        title = pendingTitle.orEmpty(),
                        rawUrl = line,
                        group = pendingGroup,
                        logoUrl = pendingLogo,
                        epgId = pendingEpgId,
                        epgUrl = playlistEpgUrl,
                        catchupSource = pendingCatchupSource,
                        catchupDays = pendingCatchupDays,
                    )?.let(result::add)
                    pendingTitle = null
                    pendingGroup = null
                    pendingLogo = null
                    pendingEpgId = null
                    pendingCatchupSource = null
                    pendingCatchupDays = null
                }
                ',' in line -> {
                    createEntry(
                        source = source,
                        title = line.substringBefore(',').trim(),
                        rawUrl = line.substringAfter(',').trim(),
                        group = txtGroup,
                        logoUrl = null,
                        epgId = null,
                        epgUrl = playlistEpgUrl,
                        catchupSource = null,
                        catchupDays = null,
                    )?.let(result::add)
                }
            }
        }
        return result.distinctBy(ExternalMediaEntry::url)
    }

    private fun createEntry(
        source: ExternalSourceSummary,
        title: String,
        rawUrl: String,
        group: String?,
        logoUrl: String?,
        epgId: String?,
        epgUrl: String?,
        catchupSource: String?,
        catchupDays: Int?,
    ): ExternalMediaEntry? {
        val address = rawUrl.substringBefore('|').trim()
        val resolved = source.url.toHttpUrlOrNull()?.resolve(address)?.toString()
            ?: address.toHttpUrlOrNull()?.toString()
            ?: return null
        val headers = parseRequestHeaders(rawUrl.substringAfter('|', ""))
        val resolvedLogo = logoUrl?.let { source.url.toHttpUrlOrNull()?.resolve(it)?.toString() ?: it }
        val entryTitle = title.ifBlank {
            resolved.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.takeIf(String::isNotEmpty)
                ?: resolved.toHttpUrlOrNull()?.host
                ?: "未命名视频"
        }
        return ExternalMediaEntry(
            id = "${source.id}:$entryTitle:$resolved".stableId(),
            sourceId = source.id,
            title = entryTitle,
            url = resolved,
            group = group,
            logoUrl = resolvedLogo,
            requestHeaders = headers,
            epgId = epgId,
            epgUrl = epgUrl?.let { source.url.toHttpUrlOrNull()?.resolve(it)?.toString() ?: it },
            catchupSource = catchupSource,
            catchupDays = catchupDays,
        )
    }

    private fun parseRequestHeaders(suffix: String): Map<String, String> = suffix
        .split('&')
        .mapNotNull { part ->
            val name = part.substringBefore('=', "").trim().lowercase()
            val value = part.substringAfter('=', "").trim()
            val canonicalName = when (name) {
                "user-agent", "http-user-agent" -> "User-Agent"
                "referer", "referrer" -> "Referer"
                "origin" -> "Origin"
                else -> null
            }
            canonicalName?.let {
                it to runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
            }
        }
        .toMap()

    private fun String.stableId(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_CONFIG_BYTES = 2 * 1024 * 1024
        const val MAX_PLAYLIST_ENTRIES = 5_000
        const val MAX_NESTED_LIVE_SOURCES = 8
        const val MAX_CATALOG_SITES = 48
        const val NESTED_SOURCE_TIMEOUT_SECONDS = 8L
        const val SOURCE_BUFFER_SIZE = 8 * 1024
        val ATTRIBUTE_PATTERN = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    }
}

private data class TvBoxRemoteSource(
    val name: String,
    val url: String,
    val userAgent: String?,
    val epgUrl: String?,
)

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
    override fun load(sourceId: String): ExternalSourceImport? = readStored()
        ?.sources
        ?.firstOrNull { it.id == sourceId }
        ?.toImport()

    @Synchronized
    override fun save(source: ExternalSourceImport) {
        require(source.payload.encodeToByteArray().size <= MAX_STORED_PAYLOAD_BYTES) { "配置超过存储上限" }
        val updated = (readStored()?.sources.orEmpty().filterNot { it.id == source.summary.id } +
            StoredExternalSourceDto.fromModel(source))
            .sortedByDescending(StoredExternalSourceDto::inspectedAtEpochMs)
        writeStored(StoredExternalSourcesDto(updated))
    }

    @Synchronized
    override fun remove(sourceId: String) {
        val updated = readStored()?.sources.orEmpty().filterNot { it.id == sourceId }
        writeStored(StoredExternalSourcesDto(updated))
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
        const val MAX_STORED_PAYLOAD_BYTES = 2 * 1024 * 1024
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
    val allowInsecureHttp: Boolean = false,
    val payload: String = "",
    val resolvedEntries: List<StoredExternalMediaEntryDto> = emptyList(),
    val resolvedCatalogSites: List<StoredExternalCatalogSiteDto> = emptyList(),
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
        allowInsecureHttp = allowInsecureHttp,
    )

    fun toImport(): ExternalSourceImport = ExternalSourceImport(
        summary = toModel(),
        payload = payload,
        resolvedEntries = resolvedEntries.map(StoredExternalMediaEntryDto::toModel),
        resolvedCatalogSites = resolvedCatalogSites.map(StoredExternalCatalogSiteDto::toModel),
    )

    companion object {
        fun fromModel(source: ExternalSourceImport): StoredExternalSourceDto = StoredExternalSourceDto(
            id = source.summary.id,
            name = source.summary.name,
            url = source.summary.url,
            kind = source.summary.kind.name,
            siteCount = source.summary.siteCount,
            liveCount = source.summary.liveCount,
            safeSiteCount = source.summary.safeSiteCount,
            runtimeRequiredCount = source.summary.runtimeRequiredCount,
            inspectedAtEpochMs = source.summary.inspectedAtEpochMs,
            allowInsecureHttp = source.summary.allowInsecureHttp,
            payload = source.payload,
            resolvedEntries = source.resolvedEntries.map(StoredExternalMediaEntryDto::fromModel),
            resolvedCatalogSites = source.resolvedCatalogSites.map(StoredExternalCatalogSiteDto::fromModel),
        )
    }
}

@Serializable
private data class StoredExternalCatalogSiteDto(
    val id: String,
    val sourceId: String,
    val name: String,
    val apiUrl: String,
    val allowInsecureHttp: Boolean,
) {
    fun toModel() = ExternalCatalogSite(id, sourceId, name, apiUrl, allowInsecureHttp)

    companion object {
        fun fromModel(site: ExternalCatalogSite) = StoredExternalCatalogSiteDto(
            site.id,
            site.sourceId,
            site.name,
            site.apiUrl,
            site.allowInsecureHttp,
        )
    }
}

@Serializable
private data class StoredExternalMediaEntryDto(
    val id: String,
    val sourceId: String,
    val title: String,
    val url: String,
    val group: String? = null,
    val logoUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val epgId: String? = null,
    val epgUrl: String? = null,
    val catchupSource: String? = null,
    val catchupDays: Int? = null,
) {
    fun toModel(): ExternalMediaEntry = ExternalMediaEntry(
        id = id,
        sourceId = sourceId,
        title = title,
        url = url,
        group = group,
        logoUrl = logoUrl,
        requestHeaders = requestHeaders,
        epgId = epgId,
        epgUrl = epgUrl,
        catchupSource = catchupSource,
        catchupDays = catchupDays,
    )

    companion object {
        fun fromModel(entry: ExternalMediaEntry): StoredExternalMediaEntryDto = StoredExternalMediaEntryDto(
            id = entry.id,
            sourceId = entry.sourceId,
            title = entry.title,
            url = entry.url,
            group = entry.group,
            logoUrl = entry.logoUrl,
            requestHeaders = entry.requestHeaders,
            epgId = entry.epgId,
            epgUrl = entry.epgUrl,
            catchupSource = entry.catchupSource,
            catchupDays = entry.catchupDays,
        )
    }
}
