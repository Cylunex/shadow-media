package com.shadow.media.library

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray

fun readableNovelSource(row: JSONObject): Boolean =
    row.novelStrings("capabilities").containsAll(listOf("detail", "toc", "chapter"))

/** Online results and job state stay in memory; their source URLs are only sent back to Novel. */
class NovelSourceSearch(private val api: NovelApi) {
    suspend fun search(connection: CatalogConnection, query: String, onUpdate: (JSONObject) -> Unit): JSONObject {
        return search(connection, query, NovelSearchOptions(), onUpdate = onUpdate)
    }

    suspend fun search(connection: CatalogConnection, query: String, options: NovelSearchOptions,
        onJob: (String) -> Unit = {}, onUpdate: (JSONObject) -> Unit): JSONObject {
        val keyword = query.trim()
        require(keyword.isNotBlank() && keyword.length <= 200) { "请输入 1～200 字的书名或作者" }
        val job = api.json(connection, listOf("search-jobs"), method = "POST", body = options.body(keyword))
        onJob(job.getString("job_id"))
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

    suspend fun preview(connection: CatalogConnection, row: JSONObject, includeToc: Boolean = false): JSONObject {
        val receipt = api.json(connection, listOf("source-previews"), method = "POST", body = JSONObject()
            .put("provider_id", row.getString("provider_id")).put("url", row.getString("bookUrl")).put("include_toc", includeToc))
        val id = receipt.novelId("job_id") ?: return receipt
        return awaitJob(connection, id) {}.getJSONObject("result")
    }

    suspend fun collect(connection: CatalogConnection, row: JSONObject): JSONObject {
        val body = JSONObject().put("provider_id", row.getString("provider_id")).put("url", row.getString("bookUrl"))
            .put("title", row.getString("name").take(512)).put("author", row.optString("author").take(256)).put("intro", row.optString("intro").take(2000))
        row.optJSONObject("popularity")?.let { body.put("popularity", it) }
        // Collection is idempotent. Keep the receipt even if a subsequent TOC job fails.
        return api.json(connection, listOf("source-results", "collect"), method = "POST", body = body)
    }

    suspend fun cancel(connection: CatalogConnection, id: String) = api.json(connection, listOf("jobs", id, "cancel"), method = "POST")

    suspend fun awaitJob(connection: CatalogConnection, id: String, onUpdate: (JSONObject) -> Unit): JSONObject = withTimeout(60_000) {
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


data class NovelSearchOptions(
    val precise: Boolean = false, val groups: List<String> = emptyList(), val providerIds: List<String>? = null,
    val excludeIds: List<String> = emptyList(), val page: Int = 1,
) {
    fun body(keyword: String): JSONObject {
        require(page in 1..100 && (providerIds == null || providerIds.size in 1..24)) { "书源或页码超出范围" }
        return JSONObject().put("q", keyword).put("precise", precise).put("group_ids", JSONArray(groups))
            .put("exclude_ids", JSONArray(excludeIds)).put("page", page).put("limit", 24).also {
                providerIds?.let { ids -> it.put("provider_ids", JSONArray(ids)) }
            }
    }
}
