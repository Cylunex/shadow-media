package top.cylunex.shadowmedia.library

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import top.cylunex.shadowmedia.database.LibraryAssetEntity
import top.cylunex.shadowmedia.model.PlaybackCandidate
import top.cylunex.shadowmedia.network.NetworkStorageRepository

/** Installed by the application composition root; only stable identities live in Room. */
object LibraryResources {
    var offlineOnly: Boolean
        get() = top.cylunex.shadowmedia.model.NetworkPolicy.offlineOnly
        set(value) { top.cylunex.shadowmedia.model.NetworkPolicy.offlineOnly = value }
    var mediaOffline: (suspend (top.cylunex.shadowmedia.database.ResourceTaskEntity, String) -> Unit)? = null
    var hasOfflineMedia: (suspend (LibraryAssetEntity) -> Boolean)? = null
    var resolver: (suspend (LibraryAssetEntity) -> PlaybackCandidate)? = null
    var networkStorage: NetworkStorageRepository? = null
    var pageManifest: (suspend (LibraryAssetEntity) -> List<String>)? = null
    var pageReader: (suspend (LibraryAssetEntity, String, File) -> Unit)? = null
    var audioEvent: ((AudioProgressSnapshot) -> Unit)? = null
    var audioResolver: (suspend (LibraryAssetEntity, String) -> List<PlaybackCandidate>)? = null
    var audioCandidateSelected: ((String, PlaybackCandidate) -> Unit)? = null
    suspend fun resolve(asset: LibraryAssetEntity): PlaybackCandidate {
        check(!offlineOnly) { "仅离线模式：此内容尚无可用的本机副本" }
        return ResourceScheduler.process.run(ResourcePriority.FOREGROUND) { requireNotNull(resolver) { "来源服务尚未初始化" }(asset) }
    }
    suspend fun resolveAudio(asset: LibraryAssetEntity, entryId: String): List<PlaybackCandidate> {
        check(!offlineOnly) { "仅离线模式：此音频尚无可用的本机副本" }
        val resolver = audioResolver ?: return listOf(resolve(asset))
        return ResourceScheduler.process.run(ResourcePriority.FOREGROUND) { resolver(asset, entryId) }
    }
}

data class AudioProgressSnapshot(val assetId: String, val positionMs: Long, val paused: Boolean, val canSeek: Boolean, val ready: Boolean, val stopped: Boolean, val entryId: String = assetId)

/** No preflight probes; one bounded GET. Credentials are reapplied only at their original origin. */
class ResourceDownloader(private val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(top.cylunex.shadowmedia.network.OfflineModeInterceptor()).build()) {
    suspend fun download(candidate: PlaybackCandidate, destination: File, maxBytes: Long = 1024L * 1024 * 1024, onProgress: (Long, Long?) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        var written = 0L
        fun write(buffer: ByteArray, size: Int, output: java.io.OutputStream, total: Long?) {
            check(!LibraryResources.offlineOnly) { "仅离线模式已开启" }
            ensureActive(); written += size
            require(written <= maxBytes) { "文件超过允许大小" }
            require(destination.parentFile!!.usableSpace > 32L * 1024 * 1024) { "可用存储空间不足" }
            output.write(buffer, 0, size); onProgress(written, total)
        }
        try {
            if (candidate.url.startsWith("shadow-smb:")) {
                val handle = requireNotNull(LibraryResources.networkStorage).openSmbResource(candidate.url, 0)
                handle.use { source -> destination.outputStream().use { output ->
                    val total = source.remainingLength
                    require(total == null || total <= maxBytes) { "文件超过允许大小" }
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) break; write(buffer, n, output, total) }
                    require(total == null || total == written) { "资源下载不完整" }
                } }
            } else {
                val initial = requireNotNull(candidate.url.toHttpUrlOrNull()) { "只支持 HTTP(S) 资源" }
                val origin = candidate.credentialOrigin?.toHttpUrlOrNull() ?: initial
                val scoped = client.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                    .addNetworkInterceptor { chain ->
                        val request = chain.request()
                        val allowed = request.url.scheme == origin.scheme && request.url.host == origin.host && request.url.port == origin.port
                        val builder = request.newBuilder()
                        if (!allowed) listOf("Authorization", "Cookie", "X-Emby-Token", "X-Emby-Authorization", "X-MediaBrowser-Token", "X-MediaBrowser-Authorization", "X-API-Key").forEach(builder::removeHeader)
                        if (initial.isHttps && !request.url.isHttps) throw IOException("拒绝将图书资源降级到明文连接")
                        chain.proceed(builder.build())
                    }.build()
                val request = Request.Builder().url(initial).apply { candidate.requiredHeaders.forEach { (key, value) -> if (!key.equals("Range", true)) header(key, value) } }.build()
                val call = scoped.newCall(request)
                val cancellation = launch { try { awaitCancellation() } finally { call.cancel() } }
                try {
                    call.execute().use { response ->
                        require(response.isSuccessful) { "资源请求失败 HTTP ${response.code}" }
                        require(response.code != 206) { "来源仅返回了部分文件，请重新下载" }
                        val body = requireNotNull(response.body)
                        val total = body.contentLength().takeIf { it >= 0 }
                        require(total == null || total <= maxBytes) { "文件超过允许大小" }
                        body.byteStream().use { input -> destination.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) { val n = input.read(buffer); if (n < 0) break; write(buffer, n, output, total) }
                        } }
                        require(total == null || total == written) { "资源下载不完整" }
                    }
                } finally { cancellation.cancel() }
            }
            require(written > 0) { "资源为空" }
        } catch (e: Exception) { destination.delete(); throw e }
    }
}
