package top.cylunex.shadowmedia.library

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

object SafeArchives {
    const val MAX_ENTRIES = 30_000
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024

    fun validate(file: File) = ZipFile(file).use { zip ->
        var total = 0L
        var count = 0
        val names = hashSetOf<String>()
        zip.entries().asSequence().forEach { entry ->
            require(++count <= MAX_ENTRIES) { "压缩包条目过多" }
            require(names.add(entry.name)) { "压缩包包含重名资源" }
            requireSafeName(entry.name)
            require(entry.size in 0..MAX_ENTRY_BYTES) { "压缩包单项过大或长度未知" }
            total += entry.size
            require(total <= MAX_EXPANDED_BYTES) { "压缩包展开内容过大" }
            require(entry.size <= 1024 * 1024 || entry.size / entry.compressedSize.coerceAtLeast(1) <= 500) { "压缩比异常" }
        }
    }

    fun requireSafeName(name: String) {
        require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name && '\u0000' !in name && ':' !in name &&
            name.split('/').none { it == ".." }) { "压缩包含有不安全路径" }
    }

    fun read(file: File, name: String, limit: Long = MAX_ENTRY_BYTES): ByteArray {
        requireSafeName(name)
        return ZipFile(file).use { zip ->
            val entry = zip.getEntry(name) ?: error("压缩包资源不存在")
            require(entry.size in 0..limit) { "资源超过读取预算" }
            zip.getInputStream(entry).use { readBounded(it, limit) }
        }
    }

    fun readBounded(input: InputStream, limit: Long): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "资源超过读取预算" }
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    fun xml(bytes: ByteArray): Document {
        // Android's XML factory does not implement every Xerces feature. Reject DTDs before
        // parsing and install a rejecting resolver, even when a hardening flag is unsupported.
        val probe = bytes.toString(Charsets.ISO_8859_1).replace("\u0000", "")
        require(!probe.contains("<!DOCTYPE", true) && !probe.contains("<!ENTITY", true)) { "不允许 XML 外部实体或 DTD" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { isXIncludeAware = false }
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> throw org.xml.sax.SAXException("外部 XML 资源被阻止") } }.parse(bytes.inputStream())
    }

    val naturalOrder = Comparator<String> { a, b ->
        val chunks = Regex("\\d+|\\D+")
        val left = chunks.findAll(a).map { it.value }.toList()
        val right = chunks.findAll(b).map { it.value }.toList()
        var comparison = 0
        for (index in 0 until minOf(left.size, right.size)) {
            val l = left[index]; val r = right[index]
            comparison = if (l.first().isDigit() && r.first().isDigit())
                l.toBigInteger().compareTo(r.toBigInteger()) else l.compareTo(r, ignoreCase = true)
            if (comparison != 0) break
        }
        if (comparison != 0) comparison else left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}

object TextPublication {
    fun decode(bytes: ByteArray, encoding: String? = null): String {
        if (encoding != null) return bytes.toString(charset(encoding)).removePrefix("\uFEFF")
        if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) return bytes.toString(Charsets.UTF_16LE).removePrefix("\uFEFF")
        if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) return bytes.toString(Charsets.UTF_16BE).removePrefix("\uFEFF")
        return runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { bytes.toString(charset("GB18030")) }.removePrefix("\uFEFF")
    }

    fun convert(input: File, target: File, title: String, encoding: String? = null) {
        require(input.length() <= 32L * 1024 * 1024) { "TXT 大于 32 MiB，请拆分后导入" }
        val text = decode(input.readBytes(), encoding)
        val chapters = mutableListOf<Pair<String, String>>()
        val heading = Regex("^\\s*(第[零一二三四五六七八九十百千万两0-9]+[章节卷回部].{0,60}|序章|前言|后记|尾声|Chapter\\s+\\d+.{0,60})\\s*$", RegexOption.IGNORE_CASE)
        var name = "正文"
        val buffer = StringBuilder()
        fun flush() { if (buffer.isNotEmpty()) { chapters += name to buffer.toString(); buffer.clear() } }
        text.lineSequence().forEach { line ->
            if (heading.matches(line)) { flush(); name = line.trim() }
            buffer.append("<p>").append(escape(line)).append("</p>\n")
            if (buffer.length >= 48_000) { flush(); name = "$name · 续" }
        }
        flush()
        require(chapters.isNotEmpty()) { "TXT 没有可读正文" }
        ZipOutputStream(target.outputStream()).use { zip ->
            fun entry(path: String, value: String) { zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray()); zip.closeEntry() }
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size; crc = java.util.zip.CRC32().apply { update(mime) }.value })
            zip.write(mime); zip.closeEntry()
            entry("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("book.opf", """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:shadow:txt</dc:identifier><dc:title>${escape(title)}</dc:title><dc:language>zh</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>${chapters.indices.joinToString("") { "<item id=\"c$it\" href=\"c$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }}</manifest><spine>${chapters.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }}</spine></package>""")
            entry("nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol>${chapters.mapIndexed { index, chapter -> "<li><a href=\"c$index.xhtml\">${escape(chapter.first)}</a></li>" }.joinToString("")}</ol></nav></body></html>""")
            chapters.forEachIndexed { index, chapter -> entry("c$index.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${escape(chapter.first)}</title></head><body><h1>${escape(chapter.first)}</h1>${chapter.second}</body></html>""") }
        }
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
