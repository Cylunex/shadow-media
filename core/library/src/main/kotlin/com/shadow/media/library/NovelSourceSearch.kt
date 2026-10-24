package com.shadow.media.library

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray

fun readableNovelSource(row: JSONObject): Boolean =
    row.novelStrings("capabilities").containsAll(listOf("detail", "toc", "chapter"))

data class NovelSourceFeatures(val searchModes: Boolean = false, val chapterOpen: Boolean = false, val intakeReuse: Boolean = false)
class NovelVersionSelectionRequired(val workId: String, val versionId: String) : IllegalStateException("版本间位置无法精确恢复，请从目录选择章节")

data class NovelSourceOpening(val workId: String, val versionId: String, val chapterId: String? = null)

/** Online results and job state stay in memory; their source URLs are only sent back to Novel. */
class NovelSourceSearch(private val api: NovelApi) {
    suspend fun features(connection: CatalogConnection): NovelSourceFeatures {
        val caps = try { api.json(connection, listOf("integration")).optJSONObject("capabilities") }
        catch (e: NovelApiException) { if (e.status in setOf(404, 405)) null else throw e }
        return NovelSourceFeatures(caps?.optBoolean("source_search_modes") == true,
            caps?.optBoolean("source_chapter_open") == true, caps?.optBoolean("source_intake_reuse") == true)
    }
    suspend fun search(connection: CatalogConnection, query: String, onUpdate: (JSONObject) -> Unit): JSONObject {
        return search(connection, query, NovelSearchOptions(), onUpdate = onUpdate)
    }

    suspend fun search(connection: CatalogConnection, query: String, options: NovelSearchOptions,
        onJob: (String) -> Unit = {}, onUpdate: (JSONObject) -> Unit): JSONObject {
        val keyword = query.trim()
        require(keyword.isNotBlank() && keyword.length <= 200) { "请输入 1～200 字的书名或作者" }
        val job = api.json(connection, listOf("search-jobs"), method = "POST", body = options.body(keyword))
        onJob(job.getString("job_id"))
        return resumeSearch(connection, job.getString("job_id"), onUpdate)
    }

    // A search across thousands of sources may outlive a screen visit. Cancellation still stops HTTP polling.
    suspend fun resumeSearch(connection: CatalogConnection, id: String, onUpdate: (JSONObject) -> Unit): JSONObject = pollJob(connection, id, onUpdate)

    suspend fun bind(connection: CatalogConnection, workId: String, row: JSONObject): JSONObject {
        require(workId.isNotBlank() && readableNovelSource(row)) { "此来源暂无可读内容" }
        val body = JSONObject().put("work_id", workId).put("provider_id", row.getString("provider_id"))
            .put("url", row.getString("bookUrl"))
        val binding = api.json(connection, listOf("source-bindings"), method = "POST", body = body)
        requireNovelWork(api, connection, workId, binding.getString("work_id"))
        awaitJob(connection, binding.getString("job_id")) {}
        return binding
    }

    suspend fun preview(connection: CatalogConnection, row: JSONObject, includeToc: Boolean = false): JSONObject {
        val receipt = api.json(connection, listOf("source-previews"), method = "POST", body = JSONObject()
            .put("provider_id", row.getString("provider_id")).put("url", row.getString("bookUrl")).put("include_toc", includeToc))
        val id = receipt.novelId("job_id") ?: return receipt
        return awaitJob(connection, id) {}.getJSONObject("result")
    }

    suspend fun collect(connection: CatalogConnection, row: JSONObject, workId: String? = null,
        createNew: Boolean = false, features: NovelSourceFeatures = NovelSourceFeatures()): JSONObject {
        require(workId == null || workId.isNotBlank())
        require(!createNew || (workId == null && features.intakeReuse)) { "当前服务不支持单独收录选择" }
        if (workId != null && !features.intakeReuse) {
            require(readableNovelSource(row)) { "此服务暂不支持将纯资料来源关联到已有作品" }
            return api.json(connection, listOf("source-bindings"), method = "POST", body = JSONObject()
                .put("work_id", workId).put("provider_id", row.getString("provider_id")).put("url", row.getString("bookUrl")))
                .also { requireNovelWork(api, connection, workId, it.getString("work_id")) }
        }
        val body = JSONObject().put("provider_id", row.getString("provider_id")).put("url", row.getString("bookUrl"))
            .put("title", row.getString("name").take(512)).put("author", row.optString("author").take(256)).put("intro", row.optString("intro").take(2000))
        row.optJSONObject("popularity")?.let { body.put("popularity", it) }
        workId?.let { body.put("work_id", it) }
        if (createNew) body.put("create_new", true)
        // Collection is idempotent. Keep the receipt even if a subsequent TOC job fails.
        return api.json(connection, listOf("source-results", "collect"), method = "POST", body = body).also {
            require(it.novelId("work_id") != null) { "收录结果与作品身份不一致" }
            if (workId != null) requireNovelWork(api, connection, workId, it.getString("work_id"))
        }
    }

    suspend fun collectionVersion(connection: CatalogConnection, receipt: JSONObject): String {
        val job = requireNotNull(receipt.novelId("job_id")) { "此来源没有可读目录任务" }
        return requireNotNull(awaitJob(connection, job) {}.optJSONObject("result")?.novelId("version_id")) { "目录尚无可读版本，请重试获取目录" }
    }

    suspend fun openChapter(connection: CatalogConnection, receipt: JSONObject, version: String, chapterUrl: String): NovelSourceOpening {
        require(version.isNotBlank() && chapterUrl.isNotBlank()) { "所选章节缺少固定版本或地址" }
        val body = JSONObject().put("binding_id", receipt.getString("binding_id")).put("version_id", version).put("chapter_url", chapterUrl)
        val result = api.json(connection, listOf("source-results", "open-chapter"), method = "POST", body = body)
        require(result.novelId("version_id") == version && result.novelId("chapter_id") != null) { "章节身份与所选作品版本不一致" }
        requireNovelWork(api, connection, receipt.getString("work_id"), result.getString("work_id"))
        return NovelSourceOpening(result.getString("work_id"), version, result.getString("chapter_id"))
    }

    suspend fun cancel(connection: CatalogConnection, id: String) = api.json(connection, listOf("jobs", id, "cancel"), method = "POST")

    suspend fun awaitJob(connection: CatalogConnection, id: String, onUpdate: (JSONObject) -> Unit): JSONObject = withTimeout(60_000) { pollJob(connection, id, onUpdate) }

    private suspend fun pollJob(connection: CatalogConnection, id: String, onUpdate: (JSONObject) -> Unit): JSONObject {
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
        return job
    }
}


data class NovelSearchOptions(
    val precise: Boolean = false, val groups: List<String> = emptyList(), val providerIds: List<String>? = null,
    val excludeIds: List<String> = emptyList(), val page: Int = 1,
    val mode: String? = null, val limit: Int = 12,
) {
    fun body(keyword: String): JSONObject {
        require(mode == null || mode in setOf("batch", "auto", "all")) { "搜索模式无效" }
        require(page in 1..100 && limit in 1..24 && (providerIds == null || providerIds.size in 1..(if (mode == null) 24 else 5000))) { "书源或页码超出范围" }
        return JSONObject().put("q", keyword).put("precise", precise).put("group_ids", JSONArray(groups))
            .put("exclude_ids", JSONArray(excludeIds)).put("page", page).put("limit", limit).also {
                mode?.let { mode -> it.put("mode", mode) }
                providerIds?.let { ids -> it.put("provider_ids", JSONArray(ids)) }
            }
    }
}
