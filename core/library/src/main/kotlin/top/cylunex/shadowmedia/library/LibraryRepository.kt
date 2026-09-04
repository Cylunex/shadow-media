package top.cylunex.shadowmedia.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
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
    val assets = dao.assets()
    val progress = dao.progress()
    private val root = File(this.context.filesDir, "publications").apply { mkdirs() }

    suspend fun addRemote(item: UnifiedMediaItem, format: String): LibraryAssetEntity {
        val id = digest("${item.key.providerId.length}:${item.key.providerId}:${item.key.itemId}")
        return dao.asset(id) ?: LibraryAssetEntity(id, item.key.providerId, item.key.itemId, item.title,
            author = item.subtitle.orEmpty(), kind = contentKind(item.type).name, format = format, addedAt = System.currentTimeMillis()).also { dao.putAsset(it) }
    }

    suspend fun fetchRemote(asset: LibraryAssetEntity, candidate: PlaybackCandidate, onProgress: (Long, Long?) -> Unit = { _, _ -> }): LibraryAssetEntity {
        val temp = File.createTempFile("resource-", ".part", folder(asset.id))
        return try {
            ResourceDownloader().download(candidate, temp, onProgress = onProgress)
            importPrepared(asset.id, asset.providerId, asset.itemId, "${asset.title}.${asset.format}", asset.format, temp)
        } finally { temp.delete() }
    }

    suspend fun importDocument(uri: Uri): LibraryAssetEntity = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请使用系统文件选择器" }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "未命名"
        val format = name.substringAfterLast('.', "").lowercase()
        val kind = contentKindForFile(name)
        require(kind in setOf(ContentKind.BOOK, ContentKind.COMIC, ContentKind.AUDIOBOOK)) { "支持 EPUB、TXT、PDF、CBZ 和常见音频文件" }
        val id = digest("local:${uri}")
        val folder = folder(id)
        // Audio stays in the user-selected storage; large audiobooks need not be copied.
        if (kind == ContentKind.AUDIOBOOK) {
            return@withContext LibraryAssetEntity(id, "local", uri.toString(), name.substringBeforeLast('.'), kind = kind.name,
                format = format, localUri = uri.toString(), addedAt = System.currentTimeMillis()).also { dao.putAsset(it) }
        }
        val temp = File(folder, "incoming.part")
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

    /** Only the caller's scoped downloader may supply this file. No URL/headers are persisted. */
    suspend fun importPrepared(id: String, providerId: String, itemId: String, name: String, format: String, input: File): LibraryAssetEntity = withContext(Dispatchers.IO) {
        val kind = contentKindForFile("item.$format")
        require(kind in setOf(ContentKind.BOOK, ContentKind.COMIC, ContentKind.AUDIOBOOK))
        val target = File(folder(id), "content.$format")
        if (format in setOf("epub", "cbz", "zip")) SafeArchives.validate(input)
        val revision = input.inputStream().use { stream ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
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
                    runCatching { saveCover(id, SafeArchives.read(input, coverEntry, 12L * 1024 * 1024)) }.getOrNull()?.let { cover = it }
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
                    runCatching { saveCover(id, SafeArchives.read(input, entry, 12L * 1024 * 1024)) }.getOrNull()?.let { cover = it }
                }
            }
        }
        input.copyTo(target, overwrite = true)
        val old = dao.asset(id)
        if (old != null && old.revision != revision) dao.removeProgress(id)
        LibraryAssetEntity(id, providerId, itemId, title, author, kind.name, format,
            Uri.fromFile(target).toString(), cover, revision, System.currentTimeMillis(), old?.favorite ?: false).also { dao.putAsset(it) }
    }

    suspend fun publicationFile(asset: LibraryAssetEntity): File = withContext(Dispatchers.IO) {
        val source = localFile(asset)
        if (asset.format == "epub") {
            val safe = File(folder(asset.id), "safe-v1.epub")
            if (!safe.exists() || safe.lastModified() < source.lastModified()) {
                val tmp = File(folder(asset.id), "safe.part")
                try { PublicationSanitizer.sanitize(source, tmp); check(tmp.renameTo(safe)) } finally { tmp.delete() }
            }
            return@withContext safe
        }
        if (asset.format != "txt") return@withContext source
        val epub = File(folder(asset.id), "text-v1.epub")
        if (!epub.exists() || epub.lastModified() < source.lastModified()) {
            val tmp = File(folder(asset.id), "text.part")
            try { TextPublication.convert(source, tmp, asset.title); check(tmp.renameTo(epub)) } finally { tmp.delete() }
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

    suspend fun saveProgress(id: String, type: String, locator: JSONObject, fraction: Double? = null, completed: Boolean = false) {
        val record = ContentProgressEntity(id, locatorType = type, locatorJson = locator.toString(),
            progression = fraction?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0), completed = completed, updatedAt = System.currentTimeMillis())
        val asset = dao.asset(id)
        val operation = asset?.takeIf { it.providerId.startsWith("catalog:KOMGA:") || it.providerId.startsWith("catalog:AUDIOBOOKSHELF:") }?.let {
            SyncOperationEntity(UUID.randomUUID().toString(), it.providerId, id, "progress", JSONObject(locator.toString()).put("completed", completed).toString(), System.currentTimeMillis())
        }
        dao.saveAndEnqueue(record, operation)
    }

    suspend fun bookmark(id: String, locator: String, note: String) = dao.putAnnotation(
        ContentAnnotationEntity(UUID.randomUUID().toString(), id, locator, note, System.currentTimeMillis())
    )

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        dao.removeFromShelf(id)
        // Only application-owned files for this resolved asset; never the source document.
        folder(id).deleteRecursively()
    }

    fun folder(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "资源标识无效" }
        return File(root, id).apply { mkdirs() }
    }

    private fun saveCover(id: String, data: ByteArray): String = File(folder(id), "cover.img").apply { writeBytes(data) }.path

    companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun comicPages(file: File): List<String> = ZipFile(file).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "avif") }
                .map { it.name }.filterNot { it.startsWith("__MACOSX/") }.sortedWith(SafeArchives.naturalOrder).toList()
        }
    }
}
