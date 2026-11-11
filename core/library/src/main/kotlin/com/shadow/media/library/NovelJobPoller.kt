package com.shadow.media.library

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class NovelJobPendingException(val jobId: String, val latest: JSONObject?) : IOException(
    "任务仍在服务端处理中，可继续查看原任务。")

/** Conditional state belongs to one connection and one polling session, never an offline cache. */
class NovelJobPoller(private val api: NovelApi, private val pause: suspend (Long) -> Unit = { delay(it) }) {
    suspend fun poll(c: CatalogConnection, id: String, includePayload: Boolean = false,
        onUpdate: suspend (JSONObject) -> Unit): JSONObject {
        var snapshot: NovelConditionalJson? = null
        var previous: String? = null
        var idle = 0
        while (true) {
            val next = api.conditionalJson(c, listOf("jobs", id),
                if (includePayload) emptyList() else listOf("include_payload" to "false"), snapshot)
            val job = next.value
            require(!job.has("id") || job.optString("id") == id) { "服务器返回了其他任务" }
            val state = job.optString("state")
            require(state in setOf("queued", "running", "succeeded", "failed", "cancelled")) { "服务器任务状态无效" }
            val revision = next.etag ?: withContext(Dispatchers.Default) { job.toString() }
            val changed = !next.unchanged && revision != previous
            idle = if (changed) 0 else (idle + 1).coerceAtMost(3)
            snapshot = next
            previous = revision
            if (changed) onUpdate(job)
            if (state in setOf("succeeded", "failed", "cancelled")) return job
            pause(minOf(5_000L, 1_000L shl idle))
        }
    }

    suspend fun await(c: CatalogConnection, id: String, timeoutMillis: Long = 60_000,
        allowCancelled: Boolean = false, onUpdate: (JSONObject) -> Unit): JSONObject {
        var latest: JSONObject? = null
        suspend fun waiting() = poll(c, id) { latest = it; onUpdate(it) }
        val job = if (timeoutMillis > 0) {
            withTimeoutOrNull(timeoutMillis) { waiting() } ?: throw NovelJobPendingException(id, latest)
        } else waiting()
        when (job.optString("state")) {
            "failed" -> error(job.optString("error").ifBlank { "书源操作失败，请重试" })
            "cancelled" -> if (!allowCancelled) error("书源操作已取消，可重试")
        }
        return job
    }
}
