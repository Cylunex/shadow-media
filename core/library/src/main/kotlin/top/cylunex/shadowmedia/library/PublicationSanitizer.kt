package top.cylunex.shadowmedia.library

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Engine scripts are injected later by Readium; scripts from a publication never run. */
object PublicationSanitizer {
    fun sanitizeMarkup(markup: String): String {
        val doc = Jsoup.parse(markup, "", Parser.xmlParser())
        doc.getAllElements().filter { it.tagName().substringAfterLast(':').lowercase() in
            setOf("script", "iframe", "object", "embed", "foreignobject", "form", "base", "set", "animate", "animatemotion", "animatetransform") }.forEach { it.remove() }
        doc.select("meta").filter { it.attr("http-equiv").equals("refresh", true) || it.attr("http-equiv").equals("Content-Security-Policy", true) }.forEach { it.remove() }
        doc.getAllElements().forEach { element ->
            element.attributes().asList().forEach { attr ->
                val value = attr.value.trim().lowercase().filterNot { it.isWhitespace() || it.code <= 32 }
                if (attr.key.substringAfterLast(':').startsWith("on", true) || attr.key.equals("srcdoc", true) || value.startsWith("javascript:") || value.startsWith("vbscript:")) element.removeAttr(attr.key)
            }
        }
        doc.selectFirst("head")?.prependElement("meta")?.attr("http-equiv", "Content-Security-Policy")?.attr("content",
            "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; script-src 'self' 'unsafe-inline'; connect-src 'none'; frame-src 'none'; media-src 'self'; form-action 'none'; base-uri 'none'")
        return doc.outerHtml().replace(Regex("<\\?xml[^?]*\\?>", RegexOption.IGNORE_CASE), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    }

    fun sanitize(source: File, target: File) {
        SafeArchives.validate(source)
        ZipFile(source).use { zip ->
            val declaredMarkup = mutableSetOf<String>()
            val declaredScripts = mutableSetOf<String>()
            zip.entries().asSequence().filter { it.name.endsWith(".opf", true) }.forEach { opf ->
                val document = SafeArchives.xml(SafeArchives.read(source, opf.name, 4L * 1024 * 1024))
                val entries = document.getElementsByTagNameNS("*", "item")
                for (index in 0 until entries.length) {
                    val item = entries.item(index) as org.w3c.dom.Element
                    val type = item.getAttribute("media-type").lowercase()
                    val path = java.net.URI(opf.name).resolve(item.getAttribute("href")).path
                    if (type in setOf("application/xhtml+xml", "text/html", "image/svg+xml")) declaredMarkup += path
                    if (type in setOf("application/javascript", "text/javascript", "application/ecmascript")) declaredScripts += path
                }
            }
            ZipOutputStream(target.outputStream()).use { output ->
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { original ->
                // Original scripts are not needed by a non-interactive reading rendition.
                if (original.name in declaredScripts || original.name.substringAfterLast('.').lowercase() in setOf("js", "mjs")) return@forEach
                val extension = original.name.substringAfterLast('.').lowercase()
                val entry = ZipEntry(original.name)
                if (original.name == "mimetype") { entry.method = ZipEntry.STORED; entry.size = original.size; entry.crc = original.crc }
                output.putNextEntry(entry)
                zip.getInputStream(original).use { input ->
                    if (original.name in declaredMarkup || extension in setOf("xhtml", "html", "htm", "svg")) {
                        val bytes = SafeArchives.readBounded(input, 16L * 1024 * 1024)
                        output.write(sanitizeMarkup(TextPublication.decode(bytes)).toByteArray(Charsets.UTF_8))
                    } else input.copyTo(output)
                }
                output.closeEntry()
            }
        } }
    }
}
