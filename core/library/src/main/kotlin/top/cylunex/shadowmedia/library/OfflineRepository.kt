package top.cylunex.shadowmedia.library

import android.content.Context
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.cylunex.shadowmedia.database.*
import java.io.File
import java.util.UUID

class OfflineRepository(private val context: Context, private val library: LibraryRepository) {
    val dao = ShadowMediaDatabase.create(context).resourceTaskDao()
    private val work = WorkManager.getInstance(context)
    suspend fun enqueue(asset: LibraryAssetEntity, unmetered: Boolean = context.getSharedPreferences("resource_policy", 0).getBoolean("unmetered", true), charging: Boolean = context.getSharedPreferences("resource_policy", 0).getBoolean("charging", false)) = OfflineLocks.forAsset(asset.id).withLock {
        require(!LibraryResources.offlineOnly) { "请关闭仅离线模式后下载" }
        val existing = dao.task(asset.id)
        if (existing?.state == "OFFLINE_AVAILABLE" && existing.revision == asset.revision) return@withLock
        if (existing != null) {
            if (existing.transport == "MEDIA3") requireNotNull(LibraryResources.mediaOffline).invoke(existing, "remove")
            else work.cancelUniqueWork("publication-offline:${asset.id}")
        }
        val media = asset.kind in setOf("MUSIC", "AUDIOBOOK", "PODCAST", "MOVIE", "EPISODE")
        start(ResourceTaskEntity(asset.id, UUID.randomUUID().toString(), asset.revision, "OFFLINE_PENDING", if (media) "MEDIA3" else "PUBLICATION", unmetered = unmetered, charging = charging, updatedAt = System.currentTimeMillis()))
    }
    private suspend fun start(task: ResourceTaskEntity) {
        dao.put(task)
        try {
            if (task.transport == "MEDIA3") requireNotNull(LibraryResources.mediaOffline) { "离线媒体服务尚未初始化" }.invoke(task, "enqueue")
            else schedule(task)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            dao.update(task.assetId, task.operationId, "OFFLINE_FAILED", 0, -1, "无法开始离线任务", System.currentTimeMillis())
            throw e
        }
    }
    suspend fun cleanTemporaryFiles(): Long = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        var bytes = 0L
        File(context.filesDir, "publications").walkTopDown().maxDepth(2).filter { it.isFile && it.extension == "part" && it.lastModified() < cutoff }.forEach {
            val length = it.length(); if (it.delete()) bytes += length
        }
        bytes
    }
    private fun schedule(task: ResourceTaskEntity) {
        val request = OneTimeWorkRequestBuilder<PublicationDownloadWorker>().setInputData(workDataOf("assetId" to task.assetId, "operation" to task.operationId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (task.unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresCharging(task.charging).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()).build()
        work.enqueueUniqueWork("publication-offline:${task.assetId}", ExistingWorkPolicy.REPLACE, request)
    }
    suspend fun pause(task: ResourceTaskEntity) = OfflineLocks.forAsset(task.assetId).withLock {
        val current = dao.task(task.assetId)?.takeIf { it.operationId == task.operationId && it.state == "OFFLINE_PENDING" } ?: return@withLock
        if (current.transport == "MEDIA3") LibraryResources.mediaOffline?.invoke(current, "pause")
        else {
            dao.update(current.assetId, current.operationId, "OFFLINE_PAUSED", current.bytes, current.totalBytes, "", System.currentTimeMillis())
            work.cancelUniqueWork("publication-offline:${current.assetId}")
        }
    }
    suspend fun retry(task: ResourceTaskEntity) = OfflineLocks.forAsset(task.assetId).withLock {
        require(!LibraryResources.offlineOnly) { "请关闭仅离线模式后下载" }
        val current = dao.task(task.assetId)?.takeIf { it.operationId == task.operationId } ?: return@withLock
        if (current.transport == "MEDIA3") LibraryResources.mediaOffline?.invoke(current, "resume")
        else {
            // A fresh operation rejects callbacks from a worker whose cancellation is still in flight.
            work.cancelUniqueWork("publication-offline:${task.assetId}")
            val asset = requireNotNull(library.dao.asset(task.assetId))
            start(current.copy(operationId = UUID.randomUUID().toString(), revision = asset.revision, state = "OFFLINE_PENDING", bytes = 0, totalBytes = -1, message = ""))
        }
    }
    suspend fun remove(task: ResourceTaskEntity) = OfflineLocks.forAsset(task.assetId).withLock {
        val current = dao.task(task.assetId)?.takeIf { it.operationId == task.operationId } ?: return@withLock
        if (current.transport == "MEDIA3") LibraryResources.mediaOffline?.invoke(current, "remove")
        else {
            work.cancelUniqueWork("publication-offline:${current.assetId}")
            dao.delete(current.assetId, current.operationId)
            val asset = library.dao.asset(current.assetId)
            if (asset != null && asset.providerId != "local" && asset.localUri.startsWith("file:")) {
                val file = File(Uri.parse(asset.localUri).path.orEmpty()).canonicalFile
                if (file.parentFile == library.folder(asset.id).canonicalFile) {
                    library.dao.clearLocalCopy(asset.id, asset.localUri)
                    library.folder(asset.id).listFiles()?.filter {
                        it.isFile && (it.name.startsWith("content-") || it.name.startsWith("safe-") || it.name.startsWith("text-"))
                    }?.forEach { it.delete() }
                }
            }
        }
    }
}

class PublicationDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("publication-offline", "图书离线保存", android.app.NotificationManager.IMPORTANCE_LOW))
        val notification = android.app.Notification.Builder(applicationContext, "publication-offline")
            .setContentTitle("正在保存图书离线副本").setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()
        val id = 1000 + (inputData.getString("assetId").orEmpty().hashCode() and 0x7fff)
        return if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, notification)
    }
    override suspend fun doWork(): Result {
        val id = inputData.getString("assetId") ?: return Result.failure()
        val operation = inputData.getString("operation") ?: return Result.failure()
        val library = LibraryRepository(applicationContext)
        val dao = ShadowMediaDatabase.create(applicationContext).resourceTaskDao()
        val task = dao.task(id)?.takeIf { it.operationId == operation && it.state == "OFFLINE_PENDING" } ?: return Result.success()
        return try {
            setForeground(getForegroundInfo())
            ResourceScheduler.process.run(ResourcePriority.OFFLINE) {
                val asset = requireNotNull(library.dao.asset(id)) { "作品已移除" }
                check(asset.revision == task.revision) { "来源版本已变化，请重新保存" }
                check(!LibraryResources.offlineOnly) { "仅离线模式已开启" }
                val candidate = LibraryResources.resolve(asset)
                val temp = File.createTempFile("offline-", ".part", library.folder(id))
                try {
                    var lastProgress = 0L
                    ResourceDownloader().download(candidate, temp) { bytes, total ->
                        check(ResourceDevicePolicy.backgroundAllowed(applicationContext)) { "设备当前不允许后台下载" }
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 1000) {
                            lastProgress = now
                            runBlocking(Dispatchers.IO) { dao.progress(id, operation, bytes, total ?: -1) }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    OfflineLocks.forAsset(id).withLock {
                        val current = dao.task(id)?.takeIf { it.operationId == operation && it.state == "OFFLINE_PENDING" } ?: return@withLock
                        val format = if (asset.format == "komga") "cbz" else asset.format
                        val length = temp.length()
                        val saved = library.importPrepared(id, asset.providerId, asset.itemId, "${asset.title}.$format", format, temp)
                        withContext(NonCancellable) {
                            dao.put(current.copy(revision = saved.revision, state = "OFFLINE_AVAILABLE", bytes = length, totalBytes = length, updatedAt = System.currentTimeMillis()))
                        }
                    }
                } finally { temp.delete() }
            }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            OfflineLocks.forAsset(id).withLock {
                val current = dao.task(id)?.takeIf { it.operationId == operation && it.state == "OFFLINE_PENDING" }
                if (current != null) dao.update(id, operation, if (LibraryResources.offlineOnly || !ResourceDevicePolicy.backgroundAllowed(applicationContext)) "OFFLINE_PAUSED" else "OFFLINE_FAILED", current.bytes, current.totalBytes, "下载暂停或失败，请检查网络、设备状态和剩余空间", System.currentTimeMillis())
            }
            Result.failure()
        }
    }
}

private object OfflineLocks {
    private val locks = Array(64) { Mutex() }
    fun forAsset(id: String) = locks[(id.hashCode() and Int.MAX_VALUE) % locks.size]
}
