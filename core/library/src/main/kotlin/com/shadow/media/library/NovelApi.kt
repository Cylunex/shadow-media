package com.shadow.media.library

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.novelObjects(key: String): List<JSONObject> = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull(a::optJSONObject) }.orEmpty()
fun JSONObject.novelStrings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
fun novelScope(c: CatalogConnection) = "novel:${c.id}"

class NovelApiException(val status: Int, val code: String, val detail: JSONObject) : IOException(
    when (code) {
        "choose_existing_work" -> "库内存在同名作品，请选择要关联的作品"
        "already_linked" -> "此来源已关联其他作品，不能转移；请选择其他来源"
        "progress_conflict" -> "另一设备已有新进度，请选择继续位置"
        "version_unavailable" -> "暂无可读版本，请选择可用版本或先在 Novel 获取正文"
        "content_changed" -> "内容已变化，请重新选择版本"
        "authentication_required" -> "登录已失效，请重新登录统一账号"
        else -> when {
            status == 409 -> "数据已被更新，输入已保留，请读取最新版本后合并"
            status == 413 -> "文件超过服务器允许的大小"
            detail.optString("message").isNotBlank() -> detail.optString("message").take(300)
            else -> "Novel 请求失败（HTTP $status，${code.ifBlank { "request_failed" }}）"
        }
    }
)

/** Every redirect is checked before transmission, including 307/308 mutation redirects. */
fun interface NovelBearerTokens { suspend fun token(connection: CatalogConnection, forceRefresh: Boolean): String }

class NovelApi(private val client: OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(com.shadow.media.network.OfflineModeInterceptor())
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(), private val bearer: NovelBearerTokens? = null) {
    fun url(c: CatalogConnection, path: List<String>, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        c.base().newBuilder().apply {
            if (!c.base().encodedPath.endsWith('/')) addPathSegment("")
            addPathSegment("api"); addPathSegment("v1"); path.forEach(::addPathSegment)
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()

    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    private suspend fun <T> execute(c: CatalogConnection, url: HttpUrl, method: String, body: RequestBody?, consume: (Response) -> T): T = withContext(Dispatchers.IO) {
        check(!LibraryResources.offlineOnly) { "仅离线模式已开启" }
        require(sameOrigin(c.base(), url) && (url.isHttps || c.allowHttp) && url.username.isBlank() && url.password.isBlank()) { "拒绝跨源 Novel 请求" }
        val scoped = client.newBuilder().addNetworkInterceptor { chain ->
            val redirected = chain.request()
            if (!sameOrigin(c.base(), redirected.url) || (!redirected.url.isHttps && !c.allowHttp)) throw IOException("拒绝 Novel 跨源重定向")
            chain.proceed(redirected)
        }.build()
        val activeCall = java.util.concurrent.atomic.AtomicReference<Call?>()
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { activeCall.get()?.cancel() } }
        suspend fun request(force: Boolean): Response {
        val access = if (c.oidcIssuer.isNotBlank()) bearer?.token(c, force)
            ?: throw NovelApiException(401, "authentication_required", JSONObject()) else c.token
        if (c.oidcIssuer.isNotBlank() && access.isBlank()) throw NovelApiException(401, "authentication_required", JSONObject())
        val call = scoped.newCall(Request.Builder().url(url).apply {
            if (access.isNotBlank()) header("Authorization", "Bearer $access")
            else if (c.username.isNotBlank()) header("Authorization", Credentials.basic(c.username, c.password))
            header("Accept", "application/json, application/epub+zip")
            method(method, body
                ?: if (method in setOf("POST", "PUT", "PATCH")) "{}".toRequestBody("application/json".toMediaType()) else null)
        }.build())
        activeCall.set(call)
        currentCoroutineContext().ensureActive()
        return call.execute()
        }
        try {
        var response = request(false)
        if (response.code == 401 && c.oidcIssuer.isNotBlank()) { response.close(); response = request(true) }
        response.use {
                if (!response.isSuccessful) {
                    val value = response.body?.byteStream()?.use { SafeArchives.readBounded(it, 128 * 1024) }?.toString(Charsets.UTF_8)
                    val parsed = runCatching { JSONObject(value.orEmpty()) }.getOrNull()
                    val detail = parsed?.optJSONObject("detail") ?: if (parsed?.opt("detail") is String) JSONObject().put("message", parsed.getString("detail")) else parsed ?: JSONObject()
                    throw NovelApiException(response.code, detail.optString("code"), detail)
                }
                consume(response)
        }
        } catch (e: Exception) { currentCoroutineContext().ensureActive(); throw e }
        finally { cancellation.cancel() }
    }
    suspend fun json(c: CatalogConnection, path: List<String>, query: List<Pair<String, String>> = emptyList(), method: String = "GET", body: JSONObject? = null): JSONObject =
        execute(c, url(c, path, query), method, body?.toString()?.toRequestBody("application/json".toMediaType())) { response ->
            val text = response.body?.byteStream()?.use { SafeArchives.readBounded(it, 16L * 1024 * 1024) }?.toString(Charsets.UTF_8).orEmpty()
            if (text.isBlank() || text == "null") JSONObject() else JSONObject(text)
        }
    /** Streams SAF content; reopening the input also makes an OIDC refresh retry safe. */
    suspend fun upload(c: CatalogConnection, filename: String, input: () -> java.io.InputStream): JSONObject {
        require(filename.substringAfterLast('.').lowercase() in setOf("txt", "epub")) { "仅支持 TXT 或 EPUB 文件" }
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: okio.BufferedSink) {
                input().use { source ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val count = source.read(buffer); if (count < 0) break
                        total += count
                        require(total <= 64L * 1024 * 1024) { "文件超过客户端 64 MiB 上传上限" }
                        sink.write(buffer, 0, count)
                    }
                    require(total > 0) { "文件不能为空" }
                }
            }
        }
        return execute(c, url(c, listOf("library", "files"), listOf("filename" to filename)), "POST", body) {
            JSONObject(SafeArchives.readBounded(requireNotNull(it.body).byteStream(), 1024 * 1024).toString(Charsets.UTF_8))
        }
    }

    /** Export responses may be JSON arrays, OPML, or large backup archives. Never parse as JSONObject. */
    suspend fun export(c: CatalogConnection, path: List<String>, output: () -> java.io.OutputStream,
        method: String = "GET", body: JSONObject? = null, maximum: Long = 2L * 1024 * 1024 * 1024) =
        execute(c, url(c, path), method, body?.toString()?.toRequestBody("application/json".toMediaType())) { response ->
            requireNotNull(response.body).byteStream().use { source -> output().use { sink ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    val count = source.read(buffer); if (count < 0) break
                    total += count; require(total <= maximum) { "导出文件超过大小上限" }
                    sink.write(buffer, 0, count)
                }
            } }
        }

    suspend fun cover(c: CatalogConnection, workId: String): ByteArray =
        execute(c, url(c, listOf("works", workId, "cover")), "GET", null) { response ->
            require(response.body?.contentType()?.type == "image") { "Novel 封面格式无效" }
            SafeArchives.readBounded(requireNotNull(response.body).byteStream(), 4L * 1024 * 1024)
        }
    suspend fun download(c: CatalogConnection, path: List<String>, target: File, query: List<Pair<String, String>> = emptyList()) = execute(c, url(c, path, query), "GET", null) { response ->
        val input = requireNotNull(response.body).byteStream()
        input.use { source -> target.outputStream().use { sink ->
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (true) {
                val count = source.read(buffer); if (count < 0) break
                total += count; require(total <= 1024L * 1024 * 1024) { "阅读包超过 1 GiB" }
                require(target.parentFile!!.usableSpace > 32L * 1024 * 1024) { "存储空间不足" }
                sink.write(buffer, 0, count)
            }
        } }
    }
}

data class NovelQuery(
    val view: String = "city", val q: String = "", val sort: String = "recent",
    val categories: List<String> = emptyList(), val genres: List<String> = emptyList(),
    val excluded: List<String> = emptyList(), val allGenres: Boolean = false,
    val serialStatus: String = "", val availability: String = "", val status: String = "",
    val group: String = "", val personalTags: List<String> = emptyList(), val minPopularity: String = "",
    val hasUpdates: Boolean = false, val precise: Boolean = false,
) {
    fun parameters(): List<Pair<String, String>> = buildList {
        add("view" to view); add("sort" to sort)
        if (precise) add("precise" to "true")
        if (hasUpdates) add("has_updates" to "true")
        listOf("q" to q, "serial_status" to serialStatus, "availability" to availability, "status" to status,
            "group" to group, "min_popularity" to minPopularity).filter { it.second.isNotBlank() }.forEach(::add)
        categories.forEach { add("category_id" to it) }; genres.forEach { add("genre_tag" to it) }
        excluded.forEach { add("exclude_genre_tag" to it) }; personalTags.forEach { add("personal_tag" to it) }
        add("tag_mode" to if (allGenres) "all" else "any")
    }
}

/** Product responses can contain server paths and URLs. Never copy them into persistent caches. */
internal fun novelCacheValue(value: Any?): Any? = when (value) {
    is JSONObject -> JSONObject().also { clean -> value.keys().forEach { key ->
        if (key !in setOf("url", "cover_url", "author_url", "filter_link", "file_path", "source_ref", "source_url", "status_url")) clean.put(key, novelCacheValue(value.opt(key)))
    } }
    is JSONArray -> JSONArray().also { clean -> (0 until value.length()).forEach { clean.put(novelCacheValue(value.opt(it))) } }
    else -> value
}
