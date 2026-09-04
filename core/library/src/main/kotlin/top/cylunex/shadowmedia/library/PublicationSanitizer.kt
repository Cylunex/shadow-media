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
        doc.select("script, iframe, object, embed, foreignObject, form, base").remove()
        doc.select("meta").filter { it.attr("http-equiv").equals("refresh", true) || it.attr("http-equiv").equals("Content-Security-Policy", true) }.forEach { it.remove() }
        doc.getAllElements().forEach { element ->
            element.attributes().asList().forEach { attr ->
                val value = attr.value.trim().lowercase().filterNot(Char::isWhitespace)
                if (attr.key.startsWith("on", true) || attr.key.equals("srcdoc", true) || value.startsWith("javascript:") || value.startsWith("vbscript:")) element.removeAttr(attr.key)
            }
        }
        doc.selectFirst("head")?.prependElement("meta")?.attr("http-equiv", "Content-Security-Policy")?.attr("content",
            "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; script-src 'self' 'unsafe-inline'; connect-src 'none'; frame-src 'none'; media-src 'self'; form-action 'none'; base-uri 'none'")
        return doc.outerHtml()
    }

    fun sanitize(source: File, target: File) {
        SafeArchives.validate(source)
        ZipFile(source).use { zip -> ZipOutputStream(target.outputStream()).use { output ->
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { original ->
                // Original scripts are not needed by a non-interactive reading rendition.
                if (original.name.substringAfterLast('.').lowercase() in setOf("js", "mjs")) return@forEach
                val extension = original.name.substringAfterLast('.').lowercase()
                val entry = ZipEntry(original.name)
                if (original.name == "mimetype") { entry.method = ZipEntry.STORED; entry.size = original.size; entry.crc = original.crc }
                output.putNextEntry(entry)
                zip.getInputStream(original).use { input ->
                    if (extension in setOf("xhtml", "html", "htm", "svg")) {
                        val bytes = SafeArchives.readBounded(input, 16L * 1024 * 1024)
                        output.write(sanitizeMarkup(bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
                    } else input.copyTo(output)
                }
                output.closeEntry()
            }
        } }
    }
}
