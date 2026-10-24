package com.shadow.media.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Optional server warm-up. Only installed, verified local windows count towards device downloads. */
class NovelCacheJobs(private val api: NovelApi) {
    private val supported = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    suspend fun available(c: CatalogConnection): Boolean {
        supported[novelScope(c)]?.let { return it }
        val value = try { api.json(c, listOf("integration")).optJSONObject("capabilities")?.optBoolean("reading_cache_jobs") == true }
        catch (e: NovelApiException) { if (e.status in setOf(404, 405)) false else throw e }
        supported[novelScope(c)] = value
        return value
    }

    suspend fun warm(c: CatalogConnection, work: String, version: String, chapters: List<String>, previousJob: String? = null,
        onJob: suspend (String) -> Unit, onProgress: suspend (Int, Int) -> Unit) {
        require(chapters.isNotEmpty() && chapters.size <= 20 && chapters.distinct().size == chapters.size)
        var id = previousJob
        if (id == null) {
            val receipt = api.json(c, listOf("works", work, "cache-jobs"), method = "POST", body = JSONObject()
                .put("version_id", version).put("chapter_ids", JSONArray(chapters)))
            require(receipt.optString("version_id") == version && receipt.optInt("total") == chapters.size) { "服务器缓存任务版本不匹配" }
            requireNovelWork(api, c, work, receipt.getString("work_id"))
            id = receipt.getString("job_id")
            onJob(id)
        }
        val jobId = requireNotNull(id)
        while (true) {
            currentCoroutineContext().ensureActive()
            val job = api.json(c, listOf("jobs", jobId))
            val payload = job.getJSONObject("payload")
            require(job.optString("kind") == "reading_cache" && payload.optString("version_id") == version &&
                payload.novelStrings("chapter_ids").toSet() == chapters.toSet()) { "服务器缓存任务选择不匹配" }
            val returned = payload.getString("work_id")
            if (returned != work) {
                val expectedCanonical = api.json(c, listOf("works", work)).getString("id")
                val actualCanonical = api.json(c, listOf("works", returned)).getString("id")
                require(expectedCanonical == actualCanonical) { "服务器缓存任务作品不匹配" }
            }
            val result = job.optJSONObject("result")
            if (result != null && result.has("total")) {
                val completed = result.getInt("completed"); val failed = result.getInt("failed")
                require(result.getString("version_id") == version && result.getInt("total") == chapters.size &&
                    completed >= 0 && failed >= 0 && completed + failed <= chapters.size &&
                    result.novelObjects("items").all { it.getString("chapter_id") in chapters }) { "服务器缓存任务进度不匹配" }
                onProgress(completed, failed)
            }
            if (job.optString("state") in setOf("succeeded", "failed", "cancelled")) return
            require(job.optString("state") in setOf("queued", "running")) { "服务器缓存任务状态无效" }
            delay(1_000)
        }
    }
}
