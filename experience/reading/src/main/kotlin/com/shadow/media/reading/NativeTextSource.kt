package com.shadow.media.reading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.shadow.media.database.LibraryAssetEntity
import com.shadow.media.database.NovelPublicationEntity
import com.shadow.media.library.*

internal data class NativeChapterRef(val id: String, val title: String, val ordinal: Int, val href: String, val readable: Boolean = true)

internal data class NativeTextChapter(
    val ref: NativeChapterRef,
    val text: String,
    val version: String,
    val total: Int,
    val legacyHref: String? = null,
    val legacyStart: Int = 0,
    val legacyLength: Int = text.length,
) {
    val textHash = LibraryRepository.digest(text)
    fun locator(offset: Int): JSONObject {
        val safe = safeTextOffset(text, offset)
        return JSONObject().put("href", ref.href).put("type", "application/xhtml+xml").put("title", ref.title)
            .put("locations", JSONObject().put("progression", safe.toDouble() / text.length.coerceAtLeast(1)))
            .put("native_text", JSONObject().put("schema", 1).put("chapter_id", ref.id).put("version_id", version)
                .put("text_hash", textHash).put("offset", safe))
            .put("text", JSONObject().put("after", text.substring(safe, (safe + 80).coerceAtMost(text.length))))
    }
    fun restore(locator: JSONObject?): Int {
        if (locator == null) return 0
        val anchor = locator.optJSONObject("native_text")
        if (anchor != null && anchor.optInt("schema") == 1 && anchor.optString("chapter_id") == ref.id &&
            anchor.optString("version_id") == version && anchor.optString("text_hash") == textHash) {
            return safeTextOffset(text, anchor.optInt("offset", 0))
        }
        val ratio = locator.optJSONObject("locations")?.optDouble("progression", 0.0) ?: 0.0
        val fraction = ratio.takeIf { it.isFinite() }.orZero().coerceIn(0.0, 1.0)
        val oldSection = legacyHref != null && locator.optString("href").substringAfterLast('/') == legacyHref
        val target = if (oldSection) (legacyLength * fraction).toInt() - legacyStart else (text.length * fraction).toInt()
        return safeTextOffset(text, target)
    }
}
private fun Double?.orZero() = this ?: 0.0
internal fun safeTextOffset(text: String, offset: Int): Int {
    var safe = offset.coerceIn(0, text.length)
    if (safe in 1 until text.length && text[safe].isLowSurrogate() && text[safe - 1].isHighSurrogate()) safe--
    return safe
}

/** TXT is decoded directly; Novel's immutable EPUB window is only a transport/cache container. */
internal class NativeTextSource(private val library: LibraryRepository, private val novel: NovelRepository) {
    private var localAsset: String? = null
    private var localChapters = emptyList<NativeTextChapter>()
    suspend fun local(asset: LibraryAssetEntity): List<NativeTextChapter> = withContext(Dispatchers.IO) {
        if (localAsset != asset.id) {
            val file = library.localFile(asset)
            require(file.length() <= 32L * 1024 * 1024) { "TXT 大于 32 MiB，请拆分后导入" }
            val text = TextPublication.decode(file.readBytes(), library.textEncoding(asset.id).takeUnless { it == "自动" })
            localChapters = parseTxt(text, asset.revision)
            localAsset = asset.id
        }
        localChapters
    }
    fun invalidate() { localAsset = null; localChapters = emptyList() }
    suspend fun searchEntries(asset: LibraryAssetEntity, publication: NovelPublicationEntity?): List<NativeSearchEntry> = withContext(Dispatchers.IO) {
        if (publication == null) return@withContext local(asset).map { content -> NativeSearchEntry(asset.id, content.ref) { content } }
        // Snapshot package metadata once. Overlapping windows must not duplicate search hits.
        novel.dao.publications(publication.scope)
            .filter { it.workId == publication.workId && it.versionId == publication.versionId }
            .flatMap { pub ->
                ensureActive()
                val item = library.dao.asset(pub.assetId)
                if (item == null || !runCatching { library.localFile(item).isFile }.getOrDefault(false)) emptyList()
                else NovelPublicationMap(JSONObject(pub.mappingJson)).chapters.map { json ->
                    val ref = NativeChapterRef(json.getString("id"), json.optString("title"), json.getInt("ordinal"), json.getString("href"))
                    NativeSearchEntry(item.id, ref) { chapter(item, pub, ref.href) }
                }
            }.distinctBy { it.ref.id }.sortedBy { it.ref.ordinal }
    }
    suspend fun chapter(asset: LibraryAssetEntity, publication: NovelPublicationEntity?, href: String?, legacyRatio: Double? = null): NativeTextChapter = withContext(Dispatchers.IO) {
        if (publication == null) {
            val all = local(asset)
            val path = href?.substringAfterLast('/')?.substringBefore('#')
            val legacy = all.filter { it.legacyHref == path }
            if (legacyRatio != null && legacy.isNotEmpty()) {
                val oldOffset = (legacy.first().legacyLength * legacyRatio.coerceIn(0.0, 1.0)).toInt()
                legacy.lastOrNull { it.legacyStart <= oldOffset }?.let { return@withContext it }
            }
            return@withContext all.firstOrNull { it.ref.href == path } ?: all.first()
        }
        val mapping = NovelPublicationMap(JSONObject(publication.mappingJson))
        val ref = href?.let(mapping::chapterForHref) ?: mapping.chapters.first()
        val xml = SafeArchives.xml(SafeArchives.read(library.localFile(asset), ref.getString("href")))
        val paragraphs = xml.getElementsByTagNameNS("*", "p")
        val body = (0 until paragraphs.length).joinToString("\n") { paragraphs.item(it).textContent }
        require(body.isNotBlank()) { "此章节没有可读正文" }
        require(body.length <= 512_000) { "此章节超过 50 万字，请先在 Novel 中拆分章节" }
        NativeTextChapter(NativeChapterRef(ref.getString("id"), ref.getString("title"), ref.getInt("ordinal"), ref.getString("href")),
            body, publication.versionId, mapping.json.getInt("total"))
    }

    companion object {
        private val heading = Regex("^\\s*(第[零〇一二三四五六七八九十百千万两0-9]+[章节卷回部].{0,60}|序章|前言|楔子|后记|尾声|Chapter\\s+\\d+.{0,60})\\s*$", RegexOption.IGNORE_CASE)
        fun parseTxt(text: String, revision: String): List<NativeTextChapter> {
            // Keep legacy cN hrefs and the original converter's section boundary for old progress.
            val sections = mutableListOf<Pair<String, String>>()
            var title = "正文"
            var escapedSize = 0
            val buffer = StringBuilder()
            fun flush() { if (buffer.isNotEmpty()) { sections += title to buffer.toString(); buffer.clear(); escapedSize = 0 } }
            text.lineSequence().forEach { line ->
                if (heading.matches(line)) { flush(); title = line.trim() }
                buffer.append(line).append('\n')
                escapedSize += line.sumOf { when (it) { '&' -> 5; '<', '>' -> 4; '"' -> 6; else -> 1 } } + 8
                if (escapedSize >= 48_000) { flush(); title = "$title · 续" }
            }
            flush()
            require(sections.any { it.second.isNotBlank() }) { "TXT 没有可读正文" }
            val readable = sections.withIndex().filter { it.value.second.isNotBlank() }
            val chunks = readable.flatMap { indexed ->
                val (name, body) = indexed.value
                val legacyHref = "c${indexed.index}.xhtml"
                buildList {
                    var start = 0; var part = 0
                    while (start < body.length) {
                        val end = safeTextOffset(body, (start + 48_000).coerceAtMost(body.length))
                        val suffix = if (part == 0) "" else "-part$part"
                        val ref = NativeChapterRef("c${indexed.index}$suffix", if (part == 0) name else "$name · 续 $part", 0, "c${indexed.index}$suffix.xhtml")
                        add(NativeTextChapter(ref, body.substring(start, end), revision, 0, legacyHref, start, body.length))
                        start = end; part++
                    }
                }
            }
            return chunks.mapIndexed { ordinal, chapter -> chapter.copy(ref = chapter.ref.copy(ordinal = ordinal), total = chunks.size) }
        }
    }
}
