package com.shadow.media.library

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

fun readableNovelSource(row: JSONObject): Boolean =
    row.novelStrings("capabilities").containsAll(listOf("detail", "toc", "chapter"))

/** Online results and job state stay in memory; their source URLs are only sent back to Novel. */
class NovelSourceSearch(private val api: NovelApi) {
    suspend fun search(connection: CatalogConnection, query: String, onUpdate: (JSONObject) -> Unit): JSONObject {
        val keyword = query.trim()
        require(keyword.isNotBlank() && keyword.length <= 200) { "请输入 1～200 字的书名或作者" }
        val job = api.json(connection, listOf("search-jobs"), method = "POST", body = JSONObject().put("q", keyword))
        return awaitJob(connection, job.getString("job_id"), onUpdate)
    }

    suspend fun bind(connection: CatalogConnection, workId: String, row: JSONObject): JSONObject {
        require(workId.isNotBlank() && readableNovelSource(row)) { "此来源暂无可读内容" }
        val body = JSONObject().put("work_id", workId).put("provider_id", row.getString("provider_id"))
            .put("url", row.getString("bookUrl"))
        val binding = api.json(connection, listOf("source-bindings"), method = "POST", body = body)
        require(binding.getString("work_id") == workId) { "关联结果与当前作品不一致，请刷新详情" }
        awaitJob(connection, binding.getString("job_id")) {}
        return binding
    }

    private suspend fun awaitJob(connection: CatalogConnection, id: String, onUpdate: (JSONObject) -> Unit): JSONObject = withTimeout(60_000) {
        // Read live state directly: a cached running job must never be treated as current progress.
        var job = api.json(connection, listOf("jobs", id))
        onUpdate(job)
        while (job.optString("state") !in setOf("succeeded", "failed", "cancelled")) {
            delay(1_000)
            job = api.json(connection, listOf("jobs", id))
            onUpdate(job)
        }
        when (job.optString("state")) {
            "failed" -> error(job.optString("error").ifBlank { "书源操作失败，请重试" })
            "cancelled" -> error("书源操作已取消，可重试")
        }
        job
    }
}
