package com.shadow.media.library

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import com.shadow.media.database.*
import com.shadow.media.model.scopedContentId

class NovelRepository(private val context: Context, private val library: LibraryRepository = LibraryRepository(context), api: NovelApi? = null,
    private val database: ShadowMediaDatabase = ShadowMediaDatabase.create(context), connectionLoader: (() -> List<CatalogConnection>)? = null) {
    // Reuse the existing encrypted connection format; this vault exclusively holds Novel roots.
    val store = CatalogConnectionStore(context, "novel_connections", "shadow_novel_connections_v1")
    val oidc = NovelOidcTokens(context, store)
    private val api = api ?: NovelApi(bearer = oidc)
    val sourceSearch = NovelSourceSearch(this.api)
    val tools = NovelLibraryTools(this.api)
    suspend fun live(c: CatalogConnection, path: List<String>, query: List<Pair<String, String>> = emptyList()) = this.api.json(c, path, query)
    private val connections = connectionLoader ?: store::load
    fun availableConnections(): List<CatalogConnection> = connections()
    private val covers = object : android.util.LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    suspend fun cover(c: CatalogConnection, workId: String, revision: String): ByteArray? {
        val key = scopedContentId(novelScope(c), workId, revision)
        covers.get(key)?.let { return it }
        if (LibraryResources.offlineOnly) return null
        return try { api.cover(c, workId).also { covers.put(key, it) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }
    val dao = database.novelDao()
    val cache = NovelReadingCache(context, library, this.api, dao, database.libraryStateDao())
    val downloads = NovelDownloads(context)
    val cacheJobs = NovelCacheJobs(this.api)
    fun connection(scope: String) = connections().singleOrNull { novelScope(it) == scope } ?: error("Novel 连接已移除，离线内容与进度仍保留")
    suspend fun discover(c: CatalogConnection): JSONObject = api.json(c, listOf("integration")).also {
        require(it.optString("schema") == "shadow.novel.integration/v1" && it.optInt("api_version") == 1) { "此服务不支持 Novel 原生接入 v1" }
        require(it.optJSONObject("capabilities")?.optBoolean("epub_windows") == true && it.optJSONObject("capabilities")?.optBoolean("progress_sync") == true) { "此 Novel 服务尚未提供原生阅读包与进度同步能力" }
    }
    suspend fun get(c: CatalogConnection, path: List<String>, query: List<Pair<String, String>> = emptyList()): JSONObject {
        val key = "novel-page:" + hash(scopedContentId(novelScope(c), path.joinToString("/"), query.toString()))
        val cache = database.libraryStateDao()
        if (LibraryResources.offlineOnly) return cache.catalogPage(key)?.let { JSONObject(it.payload).put("_cached_at", it.updatedAt) } ?: error("此页面尚未缓存，已下载内容可从离线阅读打开")
        try {
            return api.json(c, path, query).also { value ->
                val clean = (novelCacheValue(value) as JSONObject).toString()
                if (clean.toByteArray().size <= 2 * 1024 * 1024) {
                    val now = System.currentTimeMillis()
                    cache.cacheCatalogPage(CatalogPageEntity(key, clean, now))
                    if (path.size == 2 && path.first() == "works") value.novelId("id")?.takeIf { it != path[1] }?.let { canonical ->
                        val canonicalKey = "novel-page:" + hash(scopedContentId(novelScope(c), "works/$canonical", query.toString()))
                        cache.cacheCatalogPage(CatalogPageEntity(canonicalKey, clean, now))
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Authentication and application errors must stay visible, not be disguised as stale success.
            if (e is NovelApiException) throw e
            return cache.catalogPage(key)?.let { JSONObject(it.payload).put("_cached_at", it.updatedAt) } ?: throw e
        }
    }
    suspend fun mutate(c: CatalogConnection, path: List<String>, method: String, body: JSONObject? = null): JSONObject = api.json(c, path, method = method, body = body)
    suspend fun downloaded(scope: String): List<LibraryAssetEntity> {
        return dao.publications(scope).groupBy { it.workId to it.versionId }.mapNotNull { (identity, publications) ->
            val saved = dao.state(stateId(scope, identity.first))?.localPosition?.takeIf(String::isNotBlank)?.let(::JSONObject)
            val preferred = saved?.takeIf { it.optString("version_id") == identity.second }?.optString("asset_id")
            val prior = publications.associate { it.assetId to (library.dao.progress(it.assetId)?.updatedAt ?: 0) }
            publications.sortedWith(compareByDescending<NovelPublicationEntity> { it.assetId == preferred }
                .thenByDescending { prior[it.assetId] ?: 0 }
                .thenBy { JSONObject(it.mappingJson).optInt("offset") }).firstNotNullOfOrNull { pub ->
                    library.dao.asset(pub.assetId)?.takeIf { runCatching { library.localFile(it).isFile }.getOrDefault(false) }
                }
        }.sortedByDescending { it.addedAt }
    }

    fun observe(scope: String, workId: String) = dao.observe(stateId(scope, workId))
    suspend fun cachedContents(publication: NovelPublicationEntity): List<JSONObject> =
        cache.chapters(publication.scope, publication.workId, publication.versionId)

    suspend fun nextChapter(publication: NovelPublicationEntity): String? {
        val current = NovelPublicationMap(JSONObject(publication.mappingJson))
        if (current.json.isNull("next_offset")) return null
        val offset = current.json.getInt("next_offset")
        val cached = dao.publications(publication.scope).firstOrNull { it.workId == publication.workId && it.versionId == publication.versionId &&
            JSONObject(it.mappingJson).optInt("offset") == offset && library.dao.asset(it.assetId)?.let { asset -> runCatching { library.localFile(asset).isFile }.getOrDefault(false) } == true }
        if (cached != null) return NovelPublicationMap(JSONObject(cached.mappingJson)).chapters.first().getString("id")
        var start = offset
        while (true) {
            val page = get(connection(publication.scope), listOf("works", publication.workId, "contents"), listOf("version_id" to publication.versionId, "offset" to start.toString(), "limit" to "100"))
            page.novelObjects("items").firstOrNull { it.optBoolean("readable") }?.let { return it.getString("id") }
            start += 100
            if (start >= page.optInt("total")) return null
        }
    }

    data class Prepared(val asset: LibraryAssetEntity, val locator: String?)
    /** A logical shelf card can point at an older window; resume the work's newest local locator. */
    suspend fun resumeCached(publication: NovelPublicationEntity): Prepared? {
        val position = dao.state(stateId(publication.scope, publication.workId))?.localPosition
            ?.takeIf(String::isNotBlank)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val candidates = dao.publications(publication.scope).filter { it.workId == publication.workId }
        val recent = candidates.map { it to library.dao.progress(it.assetId) }
            .sortedWith(compareByDescending<Pair<NovelPublicationEntity, ContentProgressEntity?>> { it.first.assetId == position?.optString("asset_id") }
                .thenByDescending { it.second?.updatedAt ?: 0 })
        for ((window, progress) in recent) {
            val asset = library.dao.asset(window.assetId) ?: continue
            if (!runCatching { library.localFile(asset).isFile }.getOrDefault(false)) continue
            val locator = if (window.assetId == position?.optString("asset_id"))
                NovelPublicationMap(JSONObject(window.mappingJson)).locator(position)?.toString() else progress?.locatorJson
            return Prepared(asset, locator)
        }
        return null
    }

    suspend fun cachedChapter(scope: String, workId: String, versionId: String, chapterId: String): Prepared? {
        val existing = dao.publications(scope).firstOrNull { it.workId == workId && it.versionId == versionId &&
            NovelPublicationMap(JSONObject(it.mappingJson)).chapter(chapterId) != null && library.dao.asset(it.assetId)?.let { asset -> runCatching { library.localFile(asset).isFile }.getOrDefault(false) } == true } ?: return null
        val mapping = NovelPublicationMap(JSONObject(existing.mappingJson))
        return Prepared(requireNotNull(library.dao.asset(existing.assetId)), mapping.locator(JSONObject().put("version_id", versionId).put("chapter_id", chapterId).put("ratio", 0))?.toString())
    }
    suspend fun prepare(c: CatalogConnection, workId: String, versionId: String? = null, chapterId: String? = null, useRemote: Boolean = false): Prepared = readingLock.withLock {
        ProgressWriter.flush()
        val scope = novelScope(c)
        val state = dao.state(stateId(scope, workId))
        val local = state?.localPosition?.takeIf { it.isNotBlank() }?.let(::JSONObject)
        if (chapterId != null && versionId != null) {
            cachedChapter(scope, workId, versionId, chapterId)?.let { return@withLock it }
        }
        var localPrepared: Prepared? = null
        if (!useRemote && chapterId == null && local != null && (versionId == null || local.optString("version_id") == versionId)) {
            local.optString("asset_id").takeIf(String::isNotBlank)?.let { id ->
                val asset = library.dao.asset(id)
                val mapping = dao.publication(id)?.let { NovelPublicationMap(JSONObject(it.mappingJson)) }
                if (asset != null && mapping != null && runCatching { library.localFile(asset).isFile }.getOrDefault(false)) {
                    localPrepared = Prepared(asset, mapping.locator(local)?.toString())
                }
            }
        }
        if (localPrepared != null && (state?.pending == true || LibraryResources.offlineOnly)) {
            schedule(context)
            return@withLock localPrepared!!
        }
        if (LibraryResources.offlineOnly) {
            val map = dao.publications(scope).firstOrNull { it.workId == workId && (versionId == null || it.versionId == versionId) &&
                (chapterId == null || NovelPublicationMap(JSONObject(it.mappingJson)).chapter(chapterId) != null) } ?: error("此章节尚未下载")
            val asset = requireNotNull(library.dao.asset(map.assetId))
            val position = JSONObject().put("version_id", map.versionId).put("chapter_id", chapterId ?: NovelPublicationMap(JSONObject(map.mappingJson)).chapters.first().getString("id")).put("ratio", 0)
            return@withLock Prepared(asset, NovelPublicationMap(JSONObject(map.mappingJson)).locator(position)?.toString())
        }
        val open = try { api.json(c, listOf("works", workId, "open"), listOfNotNull(versionId?.let { "version_id" to it })) }
        catch (e: CancellationException) { throw e }
        catch (e: java.io.IOException) {
            if (e !is NovelApiException && localPrepared != null) return@withLock localPrepared!!
            throw e
        }
        val selectedVersion = open.getString("version_id")
        val remote = open.optJSONObject("position")
        if (chapterId == null && open.optString("quality") != "exact") throw NovelVersionSelectionRequired(workId, selectedVersion)
        val chosen = chapterId ?: remote?.optString("chapter_id")?.takeIf(String::isNotBlank) ?: open.getString("first_chapter_id")
        val chapters = open.novelObjects("chapters")
        val offset = chapters.indexOfFirst { it.getString("id") == chosen && it.optBoolean("readable") }
        require(offset >= 0) { "章节不属于所选版本" }
        val cached = try { cache.window(c, workId, selectedVersion, offset, WINDOW, chosen, allowPartial = true) }
        catch (e: NovelCachedChapterUnreadable) {
            if (e.chapterId == chosen) throw e
            cache.window(c, workId, selectedVersion, offset, 1, chosen)
        }
        val asset = cached.asset
        val mapping = cached.mapping
        val position = (if (chapterId == null) remote else null) ?: JSONObject().put("version_id", selectedVersion).put("chapter_id", chosen).put("ratio", 0)
        val initialPosition = JSONObject(position.toString()).put("asset_id", asset.id)
        val initialLocator = mapping.locator(position)?.toString()
        database.withTransaction {
            val latest = dao.state(stateId(scope, workId))
            if (latest == null) dao.putState(NovelReadingStateEntity(stateId(scope, workId), scope, workId, UUID.randomUUID().toString(), revision = open.optLong("revision"), localPosition = initialPosition.toString()))
            else if (!latest.pending && latest.conflictJson.isBlank()) dao.putState(latest.copy(revision = open.optLong("revision"), localPosition = initialPosition.toString()))
            if (initialLocator != null) library.dao.seedProgress(ContentProgressEntity(asset.id, locatorType = "text", locatorJson = initialLocator, updatedAt = System.currentTimeMillis()))
        }
        Prepared(asset, initialLocator)
    }

    /** A downloaded book becomes a reading session only when the reader intentionally opens it. */
    suspend fun beginReading(publication: NovelPublicationEntity, locator: JSONObject?) {
        val id = stateId(publication.scope, publication.workId)
        if (dao.state(id) != null) return
        val revision = if (LibraryResources.offlineOnly) 0 else try {
            withTimeout(1_500) { api.json(connection(publication.scope), listOf("me", "progress", publication.workId)).optLong("revision") }
        } catch (_: TimeoutCancellationException) { 0 } catch (e: CancellationException) { throw e } catch (_: Exception) { 0 }
        val mapping = NovelPublicationMap(JSONObject(publication.mappingJson))
        val position = locator?.let(mapping::position)?.put("asset_id", publication.assetId)?.toString().orEmpty()
        database.withTransaction {
            if (dao.state(id) == null) dao.putState(NovelReadingStateEntity(id, publication.scope, publication.workId, UUID.randomUUID().toString(), revision = revision, localPosition = position))
        }
    }

    /** At most one process-owned sync loop; HTTP never holds a Room transaction. */
    suspend fun sync(): Boolean = syncLock.withLock {
        if (LibraryResources.offlineOnly) return@withLock true
        var retry = false
        for (c in connections()) for (initial in dao.pending(novelScope(c))) {
            var rounds = 0
            while (rounds++ < 8) {
                val state = database.withTransaction {
                    val current = dao.state(initial.id) ?: return@withTransaction null
                    if (!current.pending || current.conflictJson.isNotBlank()) return@withTransaction null
                    if (current.requestJson.isNotBlank()) return@withTransaction current
                    val position = JSONObject(current.localPosition)
                    val request = JSONObject().put("version_id", position.getString("version_id")).put("chapter_id", position.getString("chapter_id"))
                        .put("ratio", position.getDouble("ratio")).put("anchor", position.getJSONObject("anchor"))
                        .put("expected_revision", current.revision).put("client_id", current.clientId).put("client_seq", current.nextClientSeq + 1)
                        .put("mutation_id", UUID.randomUUID().toString())
                    current.copy(requestJson = request.toString(), requestSequence = current.localSequence, nextClientSeq = current.nextClientSeq + 1).also { dao.putState(it) }
                } ?: break
                // Scope changes or removals during a request never redirect its data to another account.
                if (connections().none { it.id == c.id && it.retainsAccountOf(c) }) break
                try {
                    val response = api.json(c, listOf("me", "progress", state.workId), method = "PUT", body = JSONObject(state.requestJson))
                    database.withTransaction {
                        val current = dao.state(state.id) ?: return@withTransaction
                        if (current.requestJson == state.requestJson) dao.putState(current.acknowledged(response.getLong("revision")))
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    database.withTransaction {
                        val current = dao.state(state.id) ?: return@withTransaction
                        if (current.requestJson != state.requestJson) return@withTransaction
                        val conflict = (e as? NovelApiException)?.takeIf { it.status == 409 }
                        dao.putState(current.copy(conflictJson = conflict?.detail?.toString().orEmpty(), message = when {
                            conflict != null -> "另一设备已有进度，等待选择"
                            e is NovelApiException && e.status in setOf(401, 403) -> "Novel 认证失败，本机位置已保留，请检查连接凭据"
                            e is NovelApiException && e.status in 400..499 && e.status != 429 -> "Novel 拒绝进度（HTTP ${e.status}），本机位置已保留"
                            else -> "进度尚未同步，可稍后重试"
                        }))
                    }
                    if (e !is NovelApiException || e.status >= 500 || e.status == 429) retry = true
                    break
                }
            }
            if (rounds >= 8) retry = true
        }
        !retry
    }

    suspend fun keepLocal(scope: String, workId: String) {
        database.withTransaction {
            val state = requireNotNull(dao.state(stateId(scope, workId)))
            val conflict = JSONObject(state.conflictJson)
            require(conflict.optString("code") == "progress_conflict") { "此同步错误需要重新连接或选择有效版本" }
            val remote = conflict.optJSONObject("current")
            dao.putState(state.copy(revision = remote?.optLong("revision") ?: 0, requestJson = "", requestSequence = 0, conflictJson = "", message = ""))
        }
        schedule(context)
    }
    suspend fun useRemote(scope: String, workId: String): Prepared {
        ProgressWriter.flush()
        val state = requireNotNull(dao.state(stateId(scope, workId)))
        val remote = JSONObject(state.conflictJson).optJSONObject("current") ?: error("另一设备没有可恢复的位置")
        val prepared = prepare(connection(scope), workId, remote.getString("version_id"), remote.getString("chapter_id"), useRemote = true)
        val mapping = NovelPublicationMap(JSONObject(requireNotNull(dao.publication(prepared.asset.id)).mappingJson))
        val locator = requireNotNull(mapping.locator(remote)).toString()
        database.withTransaction {
            val current = requireNotNull(dao.state(state.id))
            require(current.localSequence == state.localSequence && current.conflictJson == state.conflictJson) { "阅读位置刚刚变化，请重新选择" }
            dao.putState(current.copy(revision = remote.getLong("revision"), acknowledgedSequence = current.localSequence,
                localPosition = JSONObject(remote.toString()).put("asset_id", prepared.asset.id).toString(), requestJson = "", requestSequence = 0, conflictJson = "", message = ""))
            library.dao.resetProgress(prepared.asset.id)
            library.dao.putProgress(ContentProgressEntity(prepared.asset.id, locatorType = "text", locatorJson = locator, updatedAt = System.currentTimeMillis()))
        }
        return prepared.copy(locator = locator)
    }

    companion object {
        const val WINDOW = 3
        private val readingLock = Mutex()
        private val syncLock = Mutex()
        fun stateId(scope: String, work: String) = scopedContentId(scope, work)
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniqueWork("novel-progress", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<NovelProgressWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build())
        }
        /** Called inside the same Room transaction as the accepted versioned reader snapshot. */
        internal suspend fun record(database: ShadowMediaDatabase, asset: LibraryAssetEntity, locator: JSONObject) {
            val dao = database.novelDao()
            val publication = dao.publication(asset.id) ?: return
            val position = NovelPublicationMap(JSONObject(publication.mappingJson)).position(locator) ?: return
            val id = stateId(publication.scope, publication.workId)
            val state = dao.state(id) ?: return
            position.put("asset_id", asset.id)
            // ReaderActivity excludes its initial emission; every accepted movement is a new local snapshot.
            dao.putState(state.copy(localPosition = position.toString(), localSequence = state.localSequence + 1, updatedAt = System.currentTimeMillis()))
        }
    }
}

class NovelProgressWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (NovelRepository(applicationContext).sync()) Result.success() else Result.retry()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
}
