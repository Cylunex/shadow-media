package com.shadow.media.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.AtomicFile
import androidx.work.*
import com.shadow.media.network.ResourcePriority
import com.shadow.media.network.ResourceScheduler
import com.shadow.media.model.scopedContentId
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class NovelDownloadTask(
    val scope: String, val workId: String, val versionId: String, val title: String,
    val operation: String = UUID.randomUUID().toString(), val start: Int = 0, val end: Int? = null,
    val wifiOnly: Boolean = true, val state: String = "queued", val completed: Int = 0, val total: Int = 0,
    val failed: Int = 0, val message: String = "", val versionComplete: Boolean = false,
    val serverJobId: String = "", val serverSelection: String = "",
) {
    init { require(start >= 0 && (end == null || end > start)) }
    val active get() = state in setOf("queued", "running")
    val fullBook get() = start == 0 && end == null
    internal val key get() = LibraryRepository.digest(scopedContentId(scope, workId, versionId))
    internal fun json() = JSONObject().put("scope", scope).put("work_id", workId).put("version_id", versionId).put("title", title)
        .put("operation", operation).put("start", start).put("end", end ?: JSONObject.NULL).put("wifi_only", wifiOnly)
        .put("state", state).put("completed", completed).put("total", total).put("failed", failed).put("message", message).put("version_complete", versionComplete)
        .put("server_job_id", serverJobId).put("server_selection", serverSelection)
    companion object {
        internal fun parse(value: JSONObject) = NovelDownloadTask(value.getString("scope"), value.getString("work_id"), value.getString("version_id"), value.getString("title"),
            value.getString("operation"), value.getInt("start"), if (value.isNull("end")) null else value.getInt("end"), value.getBoolean("wifi_only"),
            value.getString("state"), value.getInt("completed"), value.getInt("total"), value.getInt("failed"), value.optString("message"), value.optBoolean("version_complete"),
            value.optString("server_job_id"), value.optString("server_selection"))
    }
}

/** IDs and counters only. WorkManager persists scheduling; atomic JSON preserves pause/retry UI state. */
class NovelDownloads(private val context: Context) {
    private fun file(key: String) = File(context.filesDir, "novel-downloads/$key.json")
    private fun key(scope: String, work: String, version: String) = LibraryRepository.digest(scopedContentId(scope, work, version))
    private fun workName(key: String) = "novel-download:$key"
    internal suspend fun read(key: String): NovelDownloadTask? = withContext(Dispatchers.IO) {
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock { readFile(key) }
    }
    private fun readFile(key: String): NovelDownloadTask? = runCatching {
        NovelDownloadTask.parse(JSONObject(AtomicFile(file(key)).openRead().use { SafeArchives.readBounded(it, 64 * 1024) }.toString(Charsets.UTF_8)))
    }.getOrNull()
    fun observe(scope: String, work: String, version: String): Flow<NovelDownloadTask?> = changes.map { read(key(scope, work, version)) }

    internal suspend fun save(task: NovelDownloadTask, expectedOperation: String? = null): Boolean = withContext(Dispatchers.IO) {
        locks[(task.key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val current = readFile(task.key)
            if (expectedOperation != null && (current?.operation != expectedOperation || !current.active)) return@withLock false
            val target = file(task.key); target.parentFile!!.mkdirs()
            val atomic = AtomicFile(target); val output = atomic.startWrite()
            try { output.write(task.json().toString().toByteArray()); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
            changes.update { it + 1 }
            true
        }
    }
    suspend fun start(scope: String, work: String, version: String, title: String, start: Int = 0, end: Int? = null, wifiOnly: Boolean = true) {
        require(!LibraryResources.offlineOnly) { "请关闭仅离线模式后开始下载" }
        val task = NovelDownloadTask(scope, work, version, title, start = start, end = end, wifiOnly = wifiOnly)
        controls.withLock {
            require(read(task.key)?.active != true) { "该版本已有下载任务，可先暂停后更换范围" }
            enqueue(task)
        }
    }
    private suspend fun enqueue(task: NovelDownloadTask) {
        save(task)
        val request = OneTimeWorkRequestBuilder<NovelDownloadWorker>()
            .setInputData(workDataOf("key" to task.key, "operation" to task.operation))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (task.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        try { withContext(Dispatchers.IO) { WorkManager.getInstance(context).enqueueUniqueWork(workName(task.key), ExistingWorkPolicy.REPLACE, request).result.get() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { save(task.copy(state = "failed", message = "无法提交后台下载，请重试"), task.operation); throw e }
    }
    suspend fun pause(task: NovelDownloadTask) = controls.withLock {
        val current = read(task.key)?.takeIf { it.operation == task.operation && it.active } ?: return@withLock
        if (save(current.copy(state = "paused", message = "已暂停，已下载章节继续保留"), task.operation)) {
            withContext(Dispatchers.IO) { WorkManager.getInstance(context).cancelUniqueWork(workName(task.key)).result.get() }
            if (current.serverJobId.isNotBlank()) {
                try { withTimeout(2_500) {
                    val repository = NovelRepository(context)
                    repository.mutate(repository.connection(current.scope), listOf("jobs", current.serverJobId, "cancel"), "POST")
                } } catch (_: TimeoutCancellationException) { /* A disconnected remote batch is bounded to 20 chapters. */ }
                catch (e: CancellationException) { throw e } catch (_: Exception) { /* Local pause remains effective. */ }
            }
        }
    }
    suspend fun resume(task: NovelDownloadTask) = controls.withLock {
        require(!LibraryResources.offlineOnly) { "请关闭仅离线模式后继续下载" }
        val current = read(task.key) ?: return@withLock
        if (current.operation != task.operation || current.active) return@withLock
        enqueue(current.copy(operation = UUID.randomUUID().toString(), state = "queued", failed = 0, message = ""))
    }
    companion object {
        private val locks = List(32) { Mutex() }
        private val controls = Mutex()
        private val changes = MutableStateFlow(0L)
    }
}

class NovelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("novel-download", "小说缓存与下载", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(applicationContext, "novel-download").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载小说").setContentText("可在阅读目录中查看进度、暂停或继续").setOngoing(true).build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(id.hashCode(), notification)
    }

    override suspend fun doWork(): Result {
        val key = inputData.getString("key") ?: return Result.failure()
        val operation = inputData.getString("operation") ?: return Result.failure()
        val downloads = NovelDownloads(applicationContext)
        var task = downloads.read(key)?.takeIf { it.operation == operation && it.active } ?: return Result.success()
        try {
            setForeground(getForegroundInfo())
            val repository = NovelRepository(applicationContext)
            val c = repository.connection(task.scope)
            check(!LibraryResources.offlineOnly) { "仅离线模式已开启" }
            task = downloadNovelContents(repository, c, task) { value ->
                task = value
                check(downloads.save(value, operation)) { "下载任务已暂停或被替换" }
            }

            return if (task.failed == 0) Result.success() else Result.failure()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            downloads.save(task.copy(state = if (LibraryResources.offlineOnly) "paused" else "failed", message = if (e is NovelApiException && e.status in setOf(401, 403)) "登录已失效，请重新连接后继续" else "下载失败，请检查网络、书源和剩余空间后重试"), operation)
            return Result.failure()
        }
    }
}


/** All cached windows are verified on restart; only missing chapters are retried. */
internal suspend fun downloadNovelContents(repository: NovelRepository, c: CatalogConnection, initial: NovelDownloadTask,
    onUpdate: suspend (NovelDownloadTask) -> Unit): NovelDownloadTask {
    var task = initial
    val directory = repository.cache.directory(c, task.workId, task.versionId)
    val end = task.end?.coerceAtMost(directory.getInt("total")) ?: directory.getInt("total")
    val targets = directory.novelObjects("items").filter { it.getBoolean("readable") && it.getInt("ordinal") in task.start until end }
    require(targets.isNotEmpty()) { "所选范围没有可读章节" }
    val saved = repository.cache.chapters(task.scope, task.workId, task.versionId, verify = true).map { it.getString("id") }.toMutableSet()
    val targetIds = targets.map { it.getString("id") }.toSet()
    val groups = targets.groupBy { task.start + (it.getInt("ordinal") - task.start) / 20 * 20 }
    var completedCount = saved.count { it in targetIds }
    fun downloaded(ids: List<String>) { ids.forEach { if (saved.add(it) && it in targetIds) completedCount++ } }
    val failures = mutableSetOf<String>()
    suspend fun publish(state: String = "running", message: String = "") {
        task = task.copy(state = state, completed = completedCount, total = targets.size,
            failed = failures.size, message = message, versionComplete = directory.getBoolean("version_complete"), title = directory.getString("title"))
        onUpdate(task)
    }
    publish()
    val serverCache = repository.cacheJobs.available(c)
    for (start in task.start until end step 20) {
        currentCoroutineContext().ensureActive()
        check(!LibraryResources.offlineOnly) { "仅离线模式已开启" }
        // Connection removal/replacement must stop this account's task, including mid-download.
        check(repository.connection(task.scope).retainsAccountOf(c)) { "Novel 连接已变化" }
        val selected = groups[start].orEmpty().filter { it.getString("id") !in saved }
        if (selected.isEmpty()) continue
        try {
            if (serverCache) {
                val ids = selected.map { it.getString("id") }
                val selection = LibraryRepository.digest(ids.joinToString("/"))
                repository.cacheJobs.warm(c, task.workId, task.versionId, ids,
                    previousJob = task.serverJobId.takeIf { it.isNotBlank() && task.serverSelection == selection },
                    onJob = { id -> task = task.copy(serverJobId = id, serverSelection = selection); publish(message = "正在缓存书源正文") },
                    onProgress = { completed, failed ->
                        check(repository.connection(task.scope).retainsAccountOf(c)) { "Novel 连接已变化" }
                        publish(message = "书源正文缓存 $completed / ${ids.size}" + if (failed > 0) " · $failed 章失败" else "")
                    })
                task = task.copy(serverJobId = "", serverSelection = "")
            }
            val window = ResourceScheduler.process.run(ResourcePriority.OFFLINE) {
                repository.cache.window(c, task.workId, task.versionId, start, minOf(20, end - start))
            }
            downloaded(window.mapping.chapters.map { it.getString("id") })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (LibraryResources.offlineOnly || e is IllegalArgumentException || (e is java.io.IOException && e !is NovelApiException) ||
                (e is NovelApiException && e.status in setOf(401, 403, 429))) throw e
            // Isolate broken chapters so one source error cannot discard the rest of a book.
            for (row in selected) {
                currentCoroutineContext().ensureActive()
                try {
                    val window = ResourceScheduler.process.run(ResourcePriority.OFFLINE) {
                        repository.cache.window(c, task.workId, task.versionId, row.getInt("ordinal"), 1, row.getString("id"))
                    }
                    downloaded(window.mapping.chapters.map { it.getString("id") })
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (failure is IllegalArgumentException || (failure is java.io.IOException && failure !is NovelApiException) ||
                        (failure is NovelApiException && failure.status in setOf(401, 403, 429))) throw failure
                    failures += row.getString("id")
                }
                publish()
            }
        }
        publish()
    }
    publish(if (failures.isEmpty()) "completed" else "partial", if (failures.isEmpty()) "已保存到本机，可离线阅读" else "部分章节失败，可重试未完成章节")
    return task
}
