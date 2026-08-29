package top.cylunex.shadowmedia.network

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

data class NfoMetadata(
    val title: String? = null,
    val originalTitle: String? = null,
    val sortTitle: String? = null,
    val plot: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val runtimeMinutes: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val genres: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val externalIds: Map<String, String> = emptyMap(),
)

/** Parses the Kodi/Jellyfin/Emby NFO subset without allowing external entities or DTD access. */
fun parseNfoMetadata(xml: String): NfoMetadata {
    require(xml.length <= MAX_NFO_CHARS) { "NFO 文件过大" }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isXIncludeAware = false
        setExpandEntityReferences(false)
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
    }
    val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        ?: throw IllegalArgumentException("NFO 缺少根节点")
    val ids = linkedMapOf<String, String>()
    root.text("imdbid")?.let { ids["Imdb"] = it }
    root.text("tmdbid")?.let { ids["Tmdb"] = it }
    root.elements("uniqueid").forEach { node ->
        val value = node.textContent.trim()
        val type = node.getAttribute("type").trim()
        if (value.isNotEmpty() && type.isNotEmpty()) ids[type.replaceFirstChar(Char::uppercase)] = value
    }
    return NfoMetadata(
        title = root.text("title"),
        originalTitle = root.text("originaltitle"),
        sortTitle = root.text("sorttitle"),
        plot = root.text("plot") ?: root.text("outline"),
        year = root.text("year")?.take(4)?.toIntOrNull(),
        rating = root.text("rating")?.toDoubleOrNull(),
        runtimeMinutes = root.text("runtime")?.filter(Char::isDigit)?.toIntOrNull(),
        season = root.text("season")?.toIntOrNull(),
        episode = root.text("episode")?.toIntOrNull(),
        genres = root.texts("genre"),
        actors = root.elements("actor").mapNotNull { it.text("name") }.distinct(),
        externalIds = ids,
    )
}

private fun Element.text(tag: String): String? = elements(tag).firstOrNull()?.textContent?.trim()?.takeIf(String::isNotEmpty)

private fun Element.texts(tag: String): List<String> = elements(tag)
    .map { it.textContent.trim() }
    .filter(String::isNotEmpty)
    .distinct()

private fun Element.elements(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return buildList(nodes.length) {
        for (index in 0 until nodes.length) (nodes.item(index) as? Element)?.let(::add)
    }
}

private const val MAX_NFO_CHARS = 2 * 1024 * 1024
