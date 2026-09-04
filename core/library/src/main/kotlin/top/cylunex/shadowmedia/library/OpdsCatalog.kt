package top.cylunex.shadowmedia.library

import java.net.URI
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element

data class CatalogEntry(val id: String, val title: String, val author: String = "", val format: String = "", val navigation: String? = null,
    val acquisition: String? = null, val cover: String? = null, val locator: String = id)
data class CatalogPage(val title: String, val entries: List<CatalogEntry>, val next: String? = null)

object OpdsCatalog {
    fun parse(bytes: ByteArray, url: String): CatalogPage {
        val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return if (text.startsWith('{')) parseJson(JSONObject(text), url) else parseXml(bytes, url)
    }
    private fun resolve(base: String, target: String): String? = runCatching {
        URI(base).resolve(target).also { require(it.scheme in setOf("http", "https") && it.rawUserInfo == null) }.toASCIIString()
    }.getOrNull()
    fun locator(catalog: String, entryId: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject().put("catalog", catalog).put("entryId", entryId).toString().toByteArray())
    fun decodeLocator(locator: String): Pair<String, String> = JSONObject(Base64.getUrlDecoder().decode(locator).toString(Charsets.UTF_8)).let { it.getString("catalog") to it.getString("entryId") }
    fun format(type: String, href: String): String = when (type.substringBefore(';').lowercase()) {
        "application/epub+zip" -> "epub"
        "application/pdf" -> "pdf"
        "application/vnd.comicbook+zip", "application/x-cbz", "application/zip" -> "cbz"
        "text/plain" -> "txt"
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/x-m4b" -> "m4b"
        else -> href.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in setOf("epub", "pdf", "txt", "cbz", "zip", "m4b", "mp3", "m4a", "flac", "ogg") }.orEmpty()
    }
    private fun acquisition(rel: String) = rel in setOf("http://opds-spec.org/acquisition", "http://opds-spec.org/acquisition/open-access")
    private fun parseXml(bytes: ByteArray, url: String): CatalogPage {
        val root = SafeArchives.xml(bytes).documentElement
        fun Element.children(name: String) = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { (it.localName ?: it.tagName) == name }
        fun Element.value(name: String) = children(name).firstOrNull()?.textContent.orEmpty()
        fun Element.base(parent: String): String = getAttribute("xml:base").takeIf(String::isNotBlank)?.let { resolve(parent, it) } ?: parent
        val base = root.base(url)
        val entries = if ((root.localName ?: root.tagName) == "entry") listOf(root) else root.children("entry")
        return CatalogPage(root.value("title"), entries.mapNotNull { entry ->
            val entryBase = entry.base(base); val links = entry.children("link")
            val download = links.firstOrNull { acquisition(it.getAttribute("rel")) && format(it.getAttribute("type"), it.getAttribute("href")).isNotBlank() }
            val nav = links.firstOrNull { it.getAttribute("type").contains("atom+xml") || it.getAttribute("type").contains("opds+json") }
            if (download == null && nav == null) return@mapNotNull null
            val id = entry.value("id").ifBlank { (download ?: nav)!!.getAttribute("href") }
            val cover = links.firstOrNull { it.getAttribute("rel") in setOf("http://opds-spec.org/image", "http://opds-spec.org/image/thumbnail") }
            CatalogEntry(id, entry.value("title"), entry.children("author").joinToString("、") { it.value("name") },
                download?.let { format(it.getAttribute("type"), it.getAttribute("href")) }.orEmpty(),
                if (download == null) nav?.let { resolve(it.base(entryBase), it.getAttribute("href")) } else null,
                download?.let { resolve(it.base(entryBase), it.getAttribute("href")) }, cover?.let { resolve(it.base(entryBase), it.getAttribute("href")) }, locator(url, id))
        }, root.children("link").firstOrNull { it.getAttribute("rel") == "next" }?.let { resolve(it.base(base), it.getAttribute("href")) })
    }
    private fun parseJson(root: JSONObject, url: String): CatalogPage {
        fun JSONArray?.objects(): List<JSONObject> = this?.let { (0 until length()).mapNotNull { optJSONObject(it) } }.orEmpty()
        fun JSONObject.rels(): List<String> = when (val rel = opt("rel")) { is JSONArray -> (0 until rel.length()).map { rel.optString(it) }; is String -> listOf(rel); else -> emptyList() }
        val entries = root.optJSONArray("publications").objects().mapNotNull { pub ->
            val metadata = pub.optJSONObject("metadata") ?: return@mapNotNull null
            val download = pub.optJSONArray("links").objects().firstOrNull { it.rels().any(::acquisition) && format(it.optString("type"), it.optString("href")).isNotEmpty() } ?: return@mapNotNull null
            val id = metadata.optString("identifier").ifBlank { download.optString("href") }
            val author = when (val a = metadata.opt("author")) { is JSONArray -> a.objects().joinToString("、") { it.optString("name") }; is JSONObject -> a.optString("name"); is String -> a; else -> "" }
            CatalogEntry(id, metadata.optString("title"), author, format(download.optString("type"), download.optString("href")),
                acquisition = resolve(url, download.optString("href")), cover = pub.optJSONArray("images").objects().firstOrNull()?.let { resolve(url, it.optString("href")) }, locator = locator(url, id))
        } + root.optJSONArray("navigation").objects().mapNotNull { nav ->
            val target = resolve(url, nav.optString("href")) ?: return@mapNotNull null
            CatalogEntry(target, nav.optString("title"), navigation = target)
        }
        return CatalogPage(root.optJSONObject("metadata")?.optString("title").orEmpty(), entries,
            root.optJSONArray("links").objects().firstOrNull { "next" in it.rels() }?.let { resolve(url, it.optString("href")) })
    }
}
