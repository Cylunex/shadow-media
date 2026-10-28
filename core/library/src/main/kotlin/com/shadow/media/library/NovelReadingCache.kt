package com.shadow.media.library

import android.content.Context
import android.util.AtomicFile
import com.shadow.media.database.NovelDao
import com.shadow.media.database.NovelPublicationEntity
import com.shadow.media.database.LibraryAssetEntity
import com.shadow.media.database.LibraryStateDao
import com.shadow.media.database.CatalogPageEntity
import com.shadow.media.model.scopedContentId
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class NovelCachedWindow(val asset: LibraryAssetEntity, val publication: NovelPublicationEntity, val mapping: NovelPublicationMap)
data class NovelCachedAdjacency(val chapter: JSONObject?)
class NovelCachedChapterUnreadable(val chapterId: String, cause: Exception) : IllegalStateException("章节正文无法用于原生阅读", cause)

/** Content acquisition never opens a reading session, seeds a locator, or writes progress. */
class NovelReadingCache(private val context: Context, private val library: LibraryRepository, private val api: NovelApi, private val dao: NovelDao, private val pages: LibraryStateDao) {
    val changes = changed.asStateFlow()
    private fun directoryFile(scope: String, work: String, version: String) = File(context.filesDir, "novel-directories/${LibraryRepository.digest(scopedContentId(scope, work, version))}.json")
    private fun pagePrefix(scope: String, work: String, version: String) = "novel-toc:${LibraryRepository.digest(scopedContentId(scope, work, version))}:"

    suspend fun savedDirectory(scope: String, work: String, version: String): JSONObject? = directoryLock(scope, work, version).withLock {
        readDirectory(scope, work, version)
    }
    private fun directoryLock(scope: String, work: String, version: String) = directoryLocks[("$scope/$work/$version".hashCode() and Int.MAX_VALUE) % directoryLocks.size]
    private suspend fun readDirectory(scope: String, work: String, version: String): JSONObject? = withContext(Dispatchers.IO) {
        val file = directoryFile(scope, work, version)
        if (!file.isFile) return@withContext null
        runCatching { JSONObject(AtomicFile(file).openRead().use { SafeArchives.readBounded(it, 32L * 1024 * 1024) }.toString(Charsets.UTF_8)) }
            .getOrNull()?.takeIf { it.optString("version_id") == version && it.optInt("total", -1) == it.novelObjects("items").size }
    }

    /** Save the complete fixed-version TOC independently of the evictable online page cache. */
    suspend fun directory(c: CatalogConnection, work: String, version: String, refresh: Boolean = false): JSONObject = directoryLock(novelScope(c), work, version).withLock {
        loadDirectory(c, work, version, refresh)
    }
    private suspend fun loadDirectory(c: CatalogConnection, work: String, version: String, refresh: Boolean): JSONObject {
        if (!refresh) readDirectory(novelScope(c), work, version)?.let { return it }
        val rows = mutableListOf<JSONObject>()
        var offset = 0
        var first: JSONObject? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = manifest(c, work, version, offset, 500)
            if (first == null) first = page
            require(page.getInt("total") == first.getInt("total") && page.getString("work_id") == first.getString("work_id")) { "目录身份或长度发生变化" }
            val entries = page.novelObjects("items")
            require(entries.isNotEmpty() || page.getInt("total") == 0) { "目录分页未前进" }
            rows += entries.map { row -> JSONObject().put("id", row.getString("id")).put("title", row.optString("title"))
                .put("ordinal", row.getInt("ordinal")).put("readable", row.getBoolean("readable")) }
            require(rows.size <= 100_000) { "目录超过十万节，请分卷阅读" }
            val next = page.opt("next_offset")
            if (next == null || next == JSONObject.NULL) break
            val value = page.getInt("next_offset")
            require(value == offset + entries.size && value < page.getInt("total")) { "目录分页无效" }
            offset = value
        }
        val metadata = requireNotNull(first)
        require(rows.size == metadata.getInt("total") && rows.map { it.getString("id") }.distinct().size == rows.size &&
            rows.map { it.getInt("ordinal") } == rows.indices.toList()) { "目录缺少章节或包含重复章节" }
        val result = JSONObject().put("work_id", metadata.getString("work_id")).put("version_id", version)
            .put("title", metadata.getString("title")).put("version_complete", metadata.getBoolean("version_complete"))
            .put("total", rows.size).put("readable_total", rows.count { it.getBoolean("readable") }).put("items", JSONArray(rows))
        withContext(Dispatchers.IO) {
            val file = directoryFile(novelScope(c), work, version)
            file.parentFile!!.mkdirs()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(result.toString().toByteArray()); currentCoroutineContext().ensureActive(); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
        }
        changed.update { it + 1 }
        return result
    }

    internal suspend fun manifest(c: CatalogConnection, work: String, version: String, offset: Int, limit: Int): JSONObject {
        require(version.isNotBlank() && offset >= 0 && limit in 1..500)
        return api.json(c, listOf("works", work, "reading-manifest"), parameters(version, offset, limit)).also { value ->
            require(value.optString("schema") == "shadow.novel.reading/v1" && value.optString("version_id") == version &&
                value.optInt("offset", -1) == offset && value.optInt("total", -1) >= 0 && value.novelObjects("items").size <= limit) { "阅读清单身份不匹配" }
            requireNovelWork(api, c, work, value.getString("work_id"))
            value.novelObjects("items").forEachIndexed { index, row ->
                require(row.getInt("ordinal") == offset + index) { "阅读清单章节顺序不匹配" }
                if (row.getBoolean("readable")) require(row.getString("href") == "chapters/${row.getString("id")}.xhtml") { "阅读清单章节路径不匹配" }
            }
            val key = pagePrefix(novelScope(c), work, version) + offset
            val rows = value.novelObjects("items").map { row -> JSONObject().put("id", row.getString("id")).put("title", row.optString("title"))
                .put("ordinal", row.getInt("ordinal")).put("readable", row.getBoolean("readable")) }
            pageLocks[(key.hashCode() and Int.MAX_VALUE) % pageLocks.size].withLock {
                val old = pages.catalogPage(key)?.let { JSONObject(it.payload) }
                if (rows.size >= (old?.novelObjects("items")?.size ?: 0)) {
                    val snapshot = JSONObject().put("version_id", version).put("total", value.getInt("total")).put("items", JSONArray(rows))
                    pages.cacheCatalogPage(CatalogPageEntity(key, snapshot.toString(), System.currentTimeMillis()))
                }
            }
        }
    }

    /** Skip only known volume headings; an unknown/missing readable chapter is never guessed away. */
    suspend fun adjacent(scope: String, work: String, version: String, ordinal: Int, direction: Int): NovelCachedAdjacency? {
        require(direction == 1 || direction == -1)
        val directory = savedDirectory(scope, work, version)
        val snapshots = if (directory != null) listOf(directory) else pages.catalogPages(pagePrefix(scope, work, version) + "%").map { JSONObject(it.payload) }
        val total = snapshots.firstOrNull()?.optInt("total") ?: return null
        val known = snapshots.flatMap { it.novelObjects("items") }.associateBy { it.getInt("ordinal") }
        var next = ordinal + direction
        while (next in 0 until total) {
            val row = known[next] ?: return null
            if (row.getBoolean("readable")) return NovelCachedAdjacency(row)
            next += direction
        }
        return NovelCachedAdjacency(null)
    }

    /** Bounded windows share a lock, while unrelated foreground reading remains independent. */
    suspend fun window(c: CatalogConnection, work: String, version: String, offset: Int, limit: Int = NovelRepository.WINDOW,
        requiredChapter: String? = null, allowPartial: Boolean = false): NovelCachedWindow {
        val key = "${novelScope(c)}/$work/$version/$offset/$limit"
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            cachedWindow(novelScope(c), work, version, offset, limit)?.let { return@withLock it }
            var size = limit
            var page = manifest(c, work, version, offset, size)
            var rows = page.novelObjects("items").filter { it.getBoolean("readable") }
            require(rows.isNotEmpty() && (requiredChapter == null || rows.any { it.getString("id") == requiredChapter })) { "窗口内没有所选可读章节" }
            // Fetch only missing bodies. A following chapter failure cannot block an intentional open.
            for (row in rows.filterNot { it.optBoolean("cached") }) {
                currentCoroutineContext().ensureActive()
                try {
                    val body = api.json(c, listOf("chapters", row.getString("id")))
                    require(body.optString("id") == row.getString("id") && body.optString("version_id") == version && body.optString("content").isNotBlank()) { "正文身份不匹配或内容为空" }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (!allowPartial || requiredChapter == null || row.getString("id") == requiredChapter) throw e
                    size = 1
                    page = manifest(c, work, version, offset, size)
                    rows = page.novelObjects("items").filter { it.getBoolean("readable") }
                    break
                }
            }
            val temp = withContext(Dispatchers.IO) { File.createTempFile("novel-cache-", ".epub", context.cacheDir) }
            try {
                try { api.download(c, listOf("works", work, "publication.epub"), temp, parameters(version, offset, size)) }
                catch (e: NovelApiException) {
                    if (e.code != "publication_not_cached") throw e
                    val missing = e.detail.novelStrings("missing_chapter_ids")
                    require(missing.isNotEmpty() && missing.size <= size && missing.all { id -> rows.any { it.getString("id") == id } }) { "服务端请求窗口以外的正文" }
                    for (id in missing) {
                        try { api.json(c, listOf("chapters", id)) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            if (!allowPartial || requiredChapter == null || id == requiredChapter) throw failure
                            size = 1; page = manifest(c, work, version, offset, size)
                            rows = page.novelObjects("items").filter { it.getBoolean("readable") }; break
                        }
                    }
                    api.download(c, listOf("works", work, "publication.epub"), temp, parameters(version, offset, size))
                }
                val mapping = withContext(Dispatchers.IO) {
                    SafeArchives.validate(temp)
                    NovelPublicationMap(JSONObject(SafeArchives.read(temp, "shadow-novel.json", 2L * 1024 * 1024).toString(Charsets.UTF_8)))
                }
                require(mapping.workId == page.getString("work_id") && mapping.versionId == version && mapping.json.getInt("offset") == offset &&
                    mapping.json.getInt("total") == page.getInt("total") && mapping.json.getInt("limit") == size &&
                    mapping.chapters.map { it.getString("id") } == rows.map { it.getString("id") } &&
                    (requiredChapter == null || mapping.chapter(requiredChapter) != null)) { "阅读包与所选目录不一致" }
                withContext(Dispatchers.IO) { mapping.chapters.forEach { row ->
                    ensureActive()
                    try {
                        val xml = SafeArchives.xml(SafeArchives.read(temp, row.getString("href")))
                        val paragraphs = xml.getElementsByTagNameNS("*", "p")
                        val text = (0 until paragraphs.length).joinToString("\n") { paragraphs.item(it).textContent }
                        check(text.isNotBlank() && text.length <= 512_000) { "章节正文为空或超过原生阅读器上限" }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { throw NovelCachedChapterUnreadable(row.getString("id"), e) }
                } }
                currentCoroutineContext().ensureActive()
                val revision = fileDigest(temp)
                val id = LibraryRepository.digest(scopedContentId(novelScope(c), work, version, revision))
                installLock.withLock {
                    val existing = dao.windows(novelScope(c), work, version)
                    val asset = library.importPrepared(id, novelScope(c), version, page.getString("title"), "epub", temp)
                    val publication = NovelPublicationEntity(id, novelScope(c), work, version, mapping.json.toString(), revision)
                    dao.putPublication(publication)
                    // Transport windows are one book, rather than a new shelf book for every prefetch.
                    library.dao.seedPersonalState(id)
                    if (existing.any { it.assetId != id && valid(it) }) library.dao.hideCacheWindow(id)
                    changed.update { it + 1 }
                    NovelCachedWindow(asset, publication, mapping)
                }
            } finally { temp.delete() }
        }
    }

    private suspend fun cachedWindow(scope: String, work: String, version: String, offset: Int, limit: Int): NovelCachedWindow? {
        for (pub in dao.windows(scope, work, version)) {
            val mapping = NovelPublicationMap(JSONObject(pub.mappingJson))
            if (mapping.json.optInt("offset", -1) != offset || mapping.json.optInt("limit", -1) != limit) continue
            val asset = library.dao.asset(pub.assetId) ?: continue
            if (valid(pub, verify = true)) return NovelCachedWindow(asset, pub, mapping)
        }
        return null
    }

    private suspend fun valid(pub: NovelPublicationEntity, verify: Boolean = false): Boolean {
        val asset = library.dao.asset(pub.assetId) ?: return false
        return try {
            val file = library.localFile(asset)
            file.isFile && (!verify || fileDigest(file) == pub.packageRevision)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }

    suspend fun chapters(scope: String, work: String, version: String, verify: Boolean = false): List<JSONObject> = dao.windows(scope, work, version)
        .filter { valid(it, verify) }
        .flatMap { NovelPublicationMap(JSONObject(it.mappingJson)).chapters }.distinctBy { it.getString("id") }.sortedBy { it.getInt("ordinal") }
        .map { JSONObject(it.toString()).put("readable", true) }

    /** Keep three *readable* chapters ahead, starting as soon as a chapter is displayed. */
    suspend fun prefetch(c: CatalogConnection, publication: NovelPublicationEntity, ordinal: Int, ahead: Int = 3) {
        require(ahead in 1..10)
        if (LibraryResources.offlineOnly) return
        val wanted = mutableListOf<JSONObject>()
        var start = ordinal + 1
        var scans = 0
        while (wanted.size < ahead && scans++ < 8) {
            val page = manifest(c, publication.workId, publication.versionId, start, 50)
            wanted += page.novelObjects("items").filter { it.getBoolean("readable") }.take(ahead - wanted.size)
            if (page.isNull("next_offset")) break
            val next = page.getInt("next_offset")
            require(next > start) { "预取目录未前进" }; start = next
        }
        val cached = chapters(publication.scope, publication.workId, publication.versionId).map { it.getString("id") }.toSet()
        var failure: Exception? = null
        for (row in wanted.filterNot { it.getString("id") in cached }) {
            currentCoroutineContext().ensureActive()
            // Small immutable packages are immediately usable offline; no reader or progress side effects.
            try {
                com.shadow.media.network.ResourceScheduler.process.run(com.shadow.media.network.ResourcePriority.ADJACENT) {
                    window(c, publication.workId, publication.versionId, row.getInt("ordinal"), 1, row.getString("id"))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
        }
        failure?.let { throw it }
    }

    companion object {
        private val locks = List(64) { Mutex() }
        private val directoryLocks = List(32) { Mutex() }
        private val pageLocks = List(32) { Mutex() }
        private val installLock = Mutex()
        private val changed = MutableStateFlow(0L)
        private fun parameters(version: String, offset: Int, limit: Int) = listOf("version_id" to version, "offset" to "$offset", "limit" to "$limit")
    }
}

internal suspend fun fileDigest(file: File): String = withContext(Dispatchers.IO) { file.inputStream().use { stream ->
    val digest = java.security.MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
    while (true) { ensureActive(); val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    digest.digest().joinToString("") { "%02x".format(it) }
} }

/** Redirected work identity must be proven by the requested work's detail, never by title matching. */
internal suspend fun requireNovelWork(api: NovelApi, c: CatalogConnection, requested: String, returned: String) {
    require(returned.isNotBlank()) { "作品身份缺失" }
    if (requested != returned) require(api.json(c, listOf("works", requested)).optString("id") == returned) { "作品身份不匹配" }
}
