package top.cylunex.shadowmedia.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.w3c.dom.Element
import top.cylunex.shadowmedia.database.*
import top.cylunex.shadowmedia.model.*

class LibraryRepository(context: Context) {
    private val context = context.applicationContext
    val dao = ShadowMediaDatabase.create(this.context).libraryDao()
    fun cacheProvider(provider: top.cylunex.shadowmedia.provider.MediaProvider): top.cylunex.shadowmedia.provider.MediaProvider =
        top.cylunex.shadowmedia.network.CachedMediaProvider(provider, ShadowMediaDatabase.create(context).libraryStateDao())
    val assets = dao.assets()
    val progress = dao.progress()
    private val root = File(this.context.filesDir, "publications").apply { mkdirs() }

    suspend fun addRemote(item: UnifiedMediaItem, format: String, collected: Boolean = true): LibraryAssetEntity = ShadowMediaDatabase.create(context).withTransaction {
        val states = ShadowMediaDatabase.create(context).libraryStateDao()
        val seededFavorite = states.userState(item.key.providerId, item.key.itemId)?.favorite ?: item.favorite
        val id = digest("${item.key.providerId.length}:${item.key.providerId}:${item.key.itemId}")
        val asset = dao.assetForKey(item.key.providerId, item.key.itemId) ?: LibraryAssetEntity(id, item.key.providerId, item.key.itemId, item.title,
            author = item.subtitle.orEmpty(), kind = contentKind(item.type).name, format = format, favorite = seededFavorite, addedAt = System.currentTimeMillis()).also {
            dao.putAsset(it)
            if (contentKind(item.type) == ContentKind.AUDIOBOOK && (item.progressMs > 0 || item.played)) dao.seedProgress(ContentProgressEntity(id,
                locatorType = "time", locatorJson = JSONObject().put("trackId", id).put("positionMs", item.progressMs).put("durationMs", item.durationMs).toString(),
                completed = item.played, updatedAt = System.currentTimeMillis()))
        }
        states.seedCollection(UserCollectionEntity(assetId = asset.id, collected = collected, favorite = asset.favorite, addedAt = asset.addedAt))
        if (collected) states.collect(asset.id)
        asset
    }

    suspend fun fetchRemote(asset: LibraryAssetEntity, candidate: PlaybackCandidate, onProgress: (Long, Long?) -> Unit = { _, _ -> }): LibraryAssetEntity {
        val temp = File.createTempFile("resource-", ".part", folder(asset.id))
        return try {
            ResourceDownloader().download(candidate, temp, onProgress = onProgress)
            importPrepared(asset.id, asset.providerId, asset.itemId, "${asset.title}.${asset.format}", asset.format, temp)
        } finally { temp.delete() }
    }

    suspend fun importDocument(uri: Uri, audioMode: AudioMode? = null): LibraryAssetEntity = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请使用系统文件选择器" }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "未命名"
        val format = name.substringAfterLast('.', "").lowercase()
        val inferred = contentKindForFile(name)
        val kind = if (inferred == ContentKind.AUDIOBOOK && audioMode != null) ContentKind.valueOf(audioMode.name) else inferred
        require(kind in setOf(ContentKind.BOOK, ContentKind.COMIC, ContentKind.AUDIOBOOK, ContentKind.MUSIC, ContentKind.PODCAST)) { "支持 EPUB、TXT、PDF、CBZ 和常见音频文件" }
        val id = digest("local:${uri}")
        val folder = folder(id)
        // Audio stays in the user-selected storage; large audiobooks need not be copied.
        if (kind.isAudio()) {
            dao.asset(id)?.let { old ->
                if (audioMode == null || old.kind == kind.name) return@withContext old
                dao.resetProgress(id)
                return@withContext old.copy(kind = kind.name).also { dao.putAsset(it) }
            }
            return@withContext LibraryAssetEntity(id, "local", uri.toString(), name.substringBeforeLast('.'), kind = kind.name,
                format = format, localUri = uri.toString(), addedAt = System.currentTimeMillis()).also { dao.putAsset(it) }
        }
        val temp = File.createTempFile("incoming-", ".part", folder)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("文件权限已失效")
            input.use { stream -> temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 1024L * 1024 * 1024) { "单本图书超过 1 GiB，请通过分页服务读取" }
                    require(folder.usableSpace > 32 * 1024 * 1024) { "存储空间不足" }
                    output.write(buffer, 0, count)
                }
            } }
            importPrepared(id, "local", uri.toString(), name, format, temp)
        } finally { temp.delete() }
    }

    /** Snapshot one explicitly selected comic folder. Never traverses sibling directories. */
    suspend fun importImageDirectory(uri: Uri): LibraryAssetEntity = withContext(Dispatchers.IO) {
        val directory = requireNotNull(DocumentFile.fromTreeUri(context, uri)) { "目录授权无效" }
        require(directory.isDirectory) { "请选择漫画图片所在目录" }
        val files = directory.listFiles().filter { it.isFile && (it.name?.substringAfterLast('.')?.lowercase() in setOf("jpg", "jpeg", "png", "webp") || it.name == "ComicInfo.xml") }
        require(files.any { it.name != "ComicInfo.xml" }) { "此目录没有图片，请直接选择图片所在的章节目录" }
        require(files.size <= 30000) { "单章页面过多，请分章导入" }
        val id = digest("local-directory:$uri")
        val temp = File.createTempFile("directory-", ".part", folder(id))
        try {
            var total = 0L
            java.util.zip.ZipOutputStream(temp.outputStream()).use { zip ->
                zip.setLevel(0)
                files.forEach { document ->
                    ensureActive()
                    val name = requireNotNull(document.name)
                    require('/' !in name && '\\' !in name && ':' !in name && name !in setOf(".", ".."))
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    requireNotNull(context.contentResolver.openInputStream(document.uri)).use { input ->
                        val buffer = ByteArray(64 * 1024); var size = 0L
                        while (true) {
                            ensureActive(); val n = input.read(buffer); if (n < 0) break
                            size += n; total += n
                            require(size <= SafeArchives.MAX_ENTRY_BYTES && total <= 1024L * 1024 * 1024) { "漫画超过单页 64 MiB 或单章 1 GiB 限制" }
                            require(temp.parentFile!!.usableSpace > 32 * 1024 * 1024) { "存储空间不足" }
                            zip.write(buffer, 0, n)
                        }
                    }
                    zip.closeEntry()
                }
            }
            importPrepared(id, "local", uri.toString(), "${directory.name ?: "漫画"}.cbz", "cbz", temp)
        } finally { temp.delete() }
    }

    fun textEncoding(id: String): String = context.getSharedPreferences("text_encoding", Context.MODE_PRIVATE).getString(id, "自动") ?: "自动"
    suspend fun setTextEncoding(id: String, encoding: String) {
        require(encoding in setOf("自动", "UTF-8", "GB18030", "Big5", "UTF-16LE", "UTF-16BE"))
        context.getSharedPreferences("text_encoding", Context.MODE_PRIVATE).edit().putString(id, encoding).apply()
        // Different decoding can change chapter boundaries; don't reuse incompatible coordinates.
        dao.resetProgress(id)
    }

    /** Only the caller's scoped downloader may supply this file. No URL/headers are persisted. */
    suspend fun importPrepared(id: String, providerId: String, itemId: String, name: String, format: String, input: File): LibraryAssetEntity = withContext(Dispatchers.IO) {
        val kind = contentKindForFile("item.$format")
        require(kind in setOf(ContentKind.BOOK, ContentKind.COMIC, ContentKind.AUDIOBOOK, ContentKind.MUSIC, ContentKind.PODCAST))
        if (format in setOf("epub", "cbz", "zip")) SafeArchives.validate(input)
        val revision = input.inputStream().use { stream ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { ensureActive(); val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        val target = File(folder(id), "content-$revision.$format")
        var title = name.substringBeforeLast('.')
        var author = ""
        var cover = ""
        if (format == "epub") {
            val xml = SafeArchives.xml(SafeArchives.read(input, "META-INF/container.xml", 512 * 1024))
            val path = (xml.getElementsByTagNameNS("*", "rootfile").item(0) as? Element)?.getAttribute("full-path") ?: error("EPUB 目录无效")
            val opf = SafeArchives.xml(SafeArchives.read(input, path, 4 * 1024 * 1024))
            title = opf.getElementsByTagNameNS("*", "title").item(0)?.textContent?.takeIf { it.isNotBlank() } ?: title
            author = opf.getElementsByTagNameNS("*", "creator").item(0)?.textContent.orEmpty()
            val items = opf.getElementsByTagNameNS("*", "item")
            for (i in 0 until items.length) {
                val item = items.item(i) as Element
                if (item.getAttribute("properties").split(' ').contains("cover-image")) {
                    val coverEntry = java.net.URI(path).resolve(item.getAttribute("href")).path
                    runCatching { saveCover(id, revision, SafeArchives.read(input, coverEntry, 12L * 1024 * 1024)) }.getOrNull()?.let { cover = it }
                    break
                }
            }
        } else if (kind == ContentKind.COMIC) {
            ZipFile(input).use { zip ->
                zip.getEntry("ComicInfo.xml")?.let {
                    val xml = SafeArchives.xml(SafeArchives.read(input, it.name, 1024 * 1024))
                    title = xml.getElementsByTagName("Title").item(0)?.textContent?.takeIf(String::isNotBlank) ?: title
                    author = xml.getElementsByTagName("Writer").item(0)?.textContent.orEmpty()
                }
                comicPages(input).firstOrNull()?.let { entry ->
                    runCatching { saveCover(id, revision, SafeArchives.read(input, entry, 12L * 1024 * 1024)) }.getOrNull()?.let { cover = it }
                }
            }
        }
        if (!target.exists()) {
            val staging = File.createTempFile("install-", ".part", folder(id))
            try {
                input.inputStream().use { stream -> staging.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive(); val count = stream.read(buffer); if (count < 0) break
                        require(staging.parentFile!!.usableSpace > 32L * 1024 * 1024) { "存储空间不足" }
                        output.write(buffer, 0, count)
                    }
                } }
                ensureActive()
                check(staging.renameTo(target)) { "无法安装图书副本，旧版本未修改" }
            } finally { staging.delete() }
        }
        val old = dao.asset(id)
        val storedKind = old?.kind?.takeIf { contentKind(it).isAudio() && kind.isAudio() } ?: kind.name
        LibraryAssetEntity(id, providerId, itemId, title, author.ifBlank { old?.author.orEmpty() }, storedKind, format,
            Uri.fromFile(target).toString(), cover.ifBlank { old?.coverPath.orEmpty() }, revision, old?.addedAt ?: System.currentTimeMillis(), old?.favorite ?: false).also { dao.installAsset(it) }
    }

    suspend fun publicationFile(asset: LibraryAssetEntity): File = withContext(Dispatchers.IO) {
        val source = localFile(asset)
        if (asset.format == "epub") {
            val safe = File(folder(asset.id), "safe-v2-${asset.revision}.epub")
            if (!safe.exists() || safe.lastModified() < source.lastModified()) {
                val tmp = File.createTempFile("safe-", ".part", folder(asset.id))
                try { PublicationSanitizer.sanitize(source, tmp); check(tmp.renameTo(safe)) } finally { tmp.delete() }
            }
            return@withContext safe
        }
        if (asset.format != "txt") return@withContext source
        val encoding = textEncoding(asset.id)
        val epub = File(folder(asset.id), "text-v3-${encoding}-${asset.revision}.epub")
        if (!epub.exists() || epub.lastModified() < source.lastModified()) {
            val tmp = File.createTempFile("text-", ".part", folder(asset.id))
            try { TextPublication.convert(source, tmp, asset.title, encoding.takeUnless { it == "自动" }); check(tmp.renameTo(epub)) } finally { tmp.delete() }
        }
        epub
    }

    fun localFile(asset: LibraryAssetEntity): File {
        val uri = Uri.parse(asset.localUri)
        require(uri.scheme == "file") { "此文件尚未保存到本机" }
        return File(requireNotNull(uri.path)).canonicalFile.also {
            require(it.path.startsWith(root.canonicalPath + File.separator) && it.isFile) { "本地副本不存在，请重新导入" }
        }
    }

    suspend fun saveProgress(session: ProgressSessionEntity, type: String, locator: JSONObject, fraction: Double? = null, completed: Boolean = false) {
        val id = session.assetId
        val asset = dao.asset(id)?.takeIf { it.revision == session.resourceRevision } ?: return
        val previous = dao.progress(id)?.takeIf { it.locatorType == type }?.let { runCatching { JSONObject(it.locatorJson) }.getOrNull() }
        val position = if (type == "time") ProgressPolicy.timeSnapshot(locator, previous) else locator
        val effectiveFraction = if (completed) 1.0 else fraction ?: if (type == "time" && position.optLong("durationMs") > 0) position.optLong("positionMs").toDouble() / position.optLong("durationMs") else null
        val record = ContentProgressEntity(id, locatorType = type, locatorJson = position.toString(),
            progression = effectiveFraction?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0), completed = completed, updatedAt = System.currentTimeMillis())
        val operation = asset.takeIf { it.providerId.startsWith("catalog:KOMGA:") || it.providerId.startsWith("catalog:AUDIOBOOKSHELF:") ||
            (it.providerId.startsWith("emby:") && it.localUri.isNotBlank() && it.kind == "AUDIOBOOK") }?.let {
            val target = if (it.providerId.startsWith("catalog:AUDIOBOOKSHELF:")) "abs-book:${ProgressPolicy.absBookId(it.itemId)}" else id
            SyncOperationEntity(UUID.randomUUID().toString(), it.providerId, target, if (it.providerId.startsWith("emby:")) "offline-audio" else "progress", JSONObject(position.toString()).put("assetId", id).put("completed", completed).toString(), System.currentTimeMillis())
        }
        dao.saveAndEnqueue(record, operation, session)
    }

    suspend fun bookmark(id: String, locator: String, note: String) = dao.putAnnotation(
        ContentAnnotationEntity(UUID.randomUUID().toString(), id, locator, note, System.currentTimeMillis())
    )

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val tasks = ShadowMediaDatabase.create(context).resourceTaskDao()
        tasks.task(id)?.let { OfflineRepository(context, this@LibraryRepository).remove(it) }
        dao.removeFromShelf(id)
        // Only application-owned files for this resolved asset; never the source document.
        folder(id).deleteRecursively()
    }

    fun folder(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "资源标识无效" }
        return File(root, id).apply { mkdirs() }
    }

    private fun saveCover(id: String, revision: String, data: ByteArray): String = File(folder(id), "cover-$revision.img").apply { writeBytes(data) }.path

    companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun comicPages(file: File): List<String> = ZipFile(file).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "avif") }
                .map { it.name }.filterNot { it.startsWith("__MACOSX/") }.sortedWith(SafeArchives.naturalOrder).toList()
        }
    }
}
