@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package top.cylunex.shadowmedia.audio
import top.cylunex.shadowmedia.network.ResourceScheduler
import top.cylunex.shadowmedia.network.ResourcePriority

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.cylunex.shadowmedia.database.*
import top.cylunex.shadowmedia.library.*
import top.cylunex.shadowmedia.playback.RoutingDataSource
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/** A separate, non-evicting cache contains only explicit offline downloads. */
class MediaOfflineStore private constructor(private val context: Context) {
    private val db = ShadowMediaDatabase.create(context)
    private val library = LibraryRepository(context)
    private val databaseProvider = StandaloneDatabaseProvider(context)
    internal val cache = SimpleCache(File(context.filesDir, "offline-media"), NoOpCacheEvictor(), databaseProvider)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    internal val manager = DownloadManager(context, databaseProvider, cache, DataSource.Factory { OfflineLeaseDataSource(context, db) }, Executor { it.run() })
    init {
        scope.launch { for (write in writes) try { write() } catch (e: CancellationException) { throw e } catch (_: Exception) { AudioQueueStatus.message.value = "离线状态暂时无法保存" } }
        manager.pauseDownloads()
        manager.maxParallelDownloads = 1
        manager.requirements = Requirements(Requirements.NETWORK_UNMETERED or Requirements.DEVICE_STORAGE_NOT_LOW)
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                scope.launch {
                    manager.downloadIndex.getDownloads().use { cursor -> while (cursor.moveToNext()) publish(cursor.download) }
                    // Reconcile intent saved immediately before a process died or a service start failed.
                    db.resourceTaskDao().activeMedia().forEach { task ->
                        if (manager.downloadIndex.getDownload(task.operationId) == null) {
                            try { command(task, "enqueue") } catch (_: Exception) {
                                db.resourceTaskDao().update(task.assetId, task.operationId, "OFFLINE_FAILED", task.bytes, task.totalBytes, "任务未能恢复，请重试", System.currentTimeMillis())
                            }
                        }
                    }
                }
            }
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                publish(download)
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                val id = download.request.uri.host ?: return
                writes.trySend { db.resourceTaskDao().delete(id, download.request.id) }
            }
        })
        scope.launch {
            while (isActive) {
                delay(1000)
                val tasks = db.resourceTaskDao().activeMedia()
                val allowed = ResourceDevicePolicy.backgroundAllowed(context)
                withContext(Dispatchers.Main.immediate) {
                    applyRequirements(tasks)
                    if (allowed) manager.resumeDownloads() else manager.pauseDownloads()
                    manager.currentDownloads.forEach(::publish)
                }
            }
        }
    }
    fun setOfflineMode(enabled: Boolean) { if (enabled || !ResourceDevicePolicy.backgroundAllowed(context)) manager.pauseDownloads() else manager.resumeDownloads() }
    private fun applyRequirements(tasks: List<ResourceTaskEntity>) {
        // DownloadManager has one global constraint set: use the strictest active intent.
        val flags = (if (tasks.any { it.unmetered }) Requirements.NETWORK_UNMETERED else Requirements.NETWORK) or
            Requirements.DEVICE_STORAGE_NOT_LOW or (if (tasks.any { it.charging }) Requirements.DEVICE_CHARGING else 0)
        val requirements = Requirements(flags)
        if (manager.requirements != requirements) manager.requirements = requirements
    }
    fun installPlaybackRoute() { RoutingDataSource.offlineFactory = DataSource.Factory { OfflinePlaybackDataSource(context) } }
    suspend fun available(asset: LibraryAssetEntity): Boolean = cached(asset)?.let { it.close(); true } ?: false
    private fun publish(download: Download) {
        val id = download.request.uri.host ?: return
        val state = when (download.state) {
            Download.STATE_COMPLETED -> "OFFLINE_AVAILABLE"
            Download.STATE_FAILED -> "OFFLINE_FAILED"
            Download.STATE_STOPPED -> "OFFLINE_PAUSED"
            else -> "OFFLINE_PENDING"
        }
        val bytes = download.bytesDownloaded; val total = download.contentLength
        writes.trySend { db.resourceTaskDao().update(id, download.request.id, state, bytes, total,
            if (state == "OFFLINE_FAILED") "离线下载失败；重试会先清理未完成分片" else "", System.currentTimeMillis()) }
    }
    suspend fun command(task: ResourceTaskEntity, action: String): Unit = withContext(Dispatchers.Main.immediate) {
        if (db.resourceTaskDao().task(task.assetId)?.operationId != task.operationId) return@withContext
        val type = MediaDownloadService::class.java
        when (action) {
            "enqueue" -> {
                val asset = requireNotNull(library.dao.asset(task.assetId)) { "作品已移除" }
                require(asset.format !in setOf("iso", "m3u8", "mpd")) { "此资源暂不支持明确离线保存" }
                applyRequirements(db.resourceTaskDao().activeMedia())
                val uri = Uri.parse("shadow-offline://${asset.id}/resource.${asset.format}?operation=${task.operationId}")
                val request = DownloadRequest.Builder(task.operationId, uri).setCustomCacheKey(task.cacheKey()).build()
                DownloadService.sendAddDownload(context, type, request, false)
            }
            "pause" -> {
                db.resourceTaskDao().update(task.assetId, task.operationId, "OFFLINE_PAUSED", task.bytes, task.totalBytes, "", System.currentTimeMillis())
                DownloadService.sendSetStopReason(context, type, task.operationId, 1, false)
            }
            "resume" -> {
                if (task.state in setOf("OFFLINE_FAILED", "OFFLINE_STALE")) {
                    // Never concatenate an unvalidated partial with a newly resolved representation.
                    DownloadService.sendRemoveDownload(context, type, task.operationId, false)
                    val fresh = task.copy(revision = requireNotNull(library.dao.asset(task.assetId)).revision, operationId = java.util.UUID.randomUUID().toString(), state = "OFFLINE_PENDING", validator = "", bytes = 0, totalBytes = -1)
                    db.resourceTaskDao().put(fresh)
                    command(fresh, "enqueue")
                } else {
                    db.resourceTaskDao().update(task.assetId, task.operationId, "OFFLINE_PENDING", task.bytes, task.totalBytes, "", System.currentTimeMillis())
                    applyRequirements(db.resourceTaskDao().activeMedia())
                    DownloadService.sendSetStopReason(context, type, task.operationId, Download.STOP_REASON_NONE, false)
                }
            }
            "remove" -> {
                db.resourceTaskDao().delete(task.assetId, task.operationId)
                DownloadService.sendRemoveDownload(context, type, task.operationId, false)
            }
        }
    }
    suspend fun cached(asset: LibraryAssetEntity): DataSource? = withContext(Dispatchers.IO) {
        val task = db.resourceTaskDao().task(asset.id)?.takeIf { it.transport == "MEDIA3" && it.state == "OFFLINE_AVAILABLE" } ?: return@withContext null
        if (task.revision != asset.revision) {
            db.resourceTaskDao().update(asset.id, task.operationId, "OFFLINE_STALE", task.bytes, task.totalBytes, "来源版本已变化，请重新保存", System.currentTimeMillis())
            return@withContext null
        }
        val download = manager.downloadIndex.getDownload(task.operationId)
        val key = task.cacheKey()
        if (download?.state != Download.STATE_COMPLETED || download.contentLength <= 0 || !cache.isCached(key, 0, download.contentLength)) {
            db.resourceTaskDao().update(asset.id, task.operationId, "OFFLINE_FAILED", task.bytes, task.totalBytes, "离线文件不完整，请重新保存", System.currentTimeMillis())
            return@withContext null
        }
        CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(null)
            .setCacheKeyFactory { key }.createDataSource()
    }
    companion object {
        @Volatile private var instance: MediaOfflineStore? = null
        fun get(context: Context): MediaOfflineStore = instance ?: synchronized(this) { instance ?: MediaOfflineStore(context.applicationContext).also { instance = it } }
    }
}
private fun ResourceTaskEntity.cacheKey() = "offline:$assetId:$operationId"

private class OfflineLeaseDataSource(private val context: Context, private val db: ShadowMediaDatabase) : DataSource {
    private var source: DataSource? = null
    private var permit: ResourceScheduler.Permit? = null
    private val listeners = mutableListOf<TransferListener>()
    private val baseClient = OkHttpClient.Builder().addInterceptor(top.cylunex.shadowmedia.network.OfflineModeInterceptor())
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
    override fun open(dataSpec: DataSpec): Long {
        permit = runBlocking(Dispatchers.IO) { ResourceScheduler.process.acquire(ResourcePriority.OFFLINE) }
        try { return openLease(dataSpec) } catch (e: Throwable) { close(); throw e }
    }
    private fun openLease(dataSpec: DataSpec): Long {
        val id = dataSpec.uri.host ?: throw IOException("离线引用无效")
        val operation = dataSpec.uri.getQueryParameter("operation") ?: throw IOException("离线任务标识缺失")
        val task = runBlocking(Dispatchers.IO) { db.resourceTaskDao().task(id) }?.takeIf { it.operationId == operation } ?: throw IOException("离线任务已取消")
        val asset = runBlocking(Dispatchers.IO) { db.libraryDao().asset(id) }?.takeIf { it.revision == task.revision } ?: throw IOException("资源版本已变化")
        check(!LibraryResources.offlineOnly) { "仅离线模式已开启" }
        val candidate = if (asset.localUri.isNotBlank()) null else runBlocking(Dispatchers.IO) { LibraryResources.resolve(asset) }
        if (candidate == null) return DefaultDataSource.Factory(context).createDataSource().also { source = it; listeners.forEach(it::addTransferListener) }
            .open(dataSpec.buildUpon().setUri(asset.localUri).build())
        if (candidate.url.startsWith("shadow-smb:") && dataSpec.position > 0) throw IOException("SMB 离线副本需重新完整下载")
        if (candidate.isDiscImage || candidate.url.substringBefore('?').endsWith(".m3u8", true) || candidate.url.substringBefore('?').endsWith(".mpd", true)) throw IOException("此流类型尚不支持明确离线保存")
        val origin = (candidate.credentialOrigin ?: candidate.url).toHttpUrlOrNull()
        val client = baseClient.newBuilder().addNetworkInterceptor { chain ->
            check(!LibraryResources.offlineOnly) { "仅离线模式已开启" }
            val request = chain.request(); val url = request.url; val builder = request.newBuilder()
            if (origin?.isHttps == true && !url.isHttps) throw IOException("拒绝降级到 HTTP")
            if (origin == null || origin.scheme != url.scheme || origin.host != url.host || origin.port != url.port) {
                listOf("Authorization", "Cookie", "X-Emby-Token", "X-Emby-Authorization", "X-MediaBrowser-Token", "X-MediaBrowser-Authorization", "X-API-Key").forEach(builder::removeHeader)
            }
            if (dataSpec.position > 0 && task.validator.isNotBlank()) builder.header("If-Range", task.validator.removePrefix("E:").removePrefix("L:"))
            val outgoing = builder.header("Accept-Encoding", "identity").build()
            val response = chain.proceed(outgoing)
            if (response.isSuccessful) {
                val validator = response.header("ETag")?.takeUnless { it.startsWith("W/") }?.let { "E:$it" }
                    ?: response.header("Last-Modified")?.let { "L:$it" }.orEmpty()
                if (!top.cylunex.shadowmedia.model.ResumeValidation.accepts(dataSpec.position, response.code, outgoing.header("Range") != null,
                        response.header("Content-Range"), response.header("Content-Length")?.toLongOrNull(), task.validator, validator)) {
                    response.close(); throw IOException("资源不支持可靠续传，需要重新下载")
                }
                if (dataSpec.position == 0L) runBlocking(Dispatchers.IO) { db.resourceTaskDao().validator(id, operation, validator) }
            }
            response
        }.build()
        val delegate = RoutingDataSource(OkHttpDataSource.Factory(client).setDefaultRequestProperties(candidate.requiredHeaders).createDataSource(), LibraryResources.networkStorage)
        source = delegate; listeners.forEach(delegate::addTransferListener)
        return delegate.open(dataSpec.buildUpon().setUri(candidate.url).build())
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (LibraryResources.offlineOnly) throw IOException("仅离线模式已开启")
        if (context.filesDir.usableSpace < 64L * 1024 * 1024) throw IOException("存储空间不足")
        return source?.read(buffer, offset, length) ?: -1
    }
    override fun getUri(): Uri? = source?.uri
    override fun getResponseHeaders() = source?.responseHeaders.orEmpty()
    override fun close() {
        try { source?.close() } finally {
            source = null
            val granted = permit; permit = null
            if (granted != null) runBlocking(Dispatchers.IO) { granted.release() }
        }
    }
}

class MediaDownloadService : DownloadService(92, 1000) {
    override fun onCreate() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("offline", "离线保存", NotificationManager.IMPORTANCE_LOW))
        super.onCreate()
    }
    override fun getDownloadManager() = MediaOfflineStore.get(this).manager
    override fun getScheduler(): Scheduler = PlatformScheduler(this, 93)
    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this, "offline").buildProgressNotification(this, android.R.drawable.stat_sys_download, null, "Shadow Media 离线保存", downloads, notMetRequirements)
}
