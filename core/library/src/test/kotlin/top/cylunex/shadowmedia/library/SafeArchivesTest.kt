package top.cylunex.shadowmedia.library

import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class SafeArchivesTest {
    @Test fun `natural page sorting`() {
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), listOf("10.jpg", "2.jpg", "1.jpg").sortedWith(SafeArchives.naturalOrder))
    }
    @Test(expected = IllegalArgumentException::class) fun `reject traversal`() { SafeArchives.requireSafeName("../../secret") }
    @Test(expected = IllegalArgumentException::class) fun `bounded stream rejects overflow`() { SafeArchives.readBounded(ByteArray(30).inputStream(), 20) }
    @Test fun `GB18030 is decoded`() { assertEquals("中文小说", TextPublication.decode("中文小说".toByteArray(charset("GB18030")))) }
    @Test fun `TXT becomes valid bounded EPUB with stable chapter files`() {
        val dir = Files.createTempDirectory("shadow-text-test").toFile()
        try {
            val source = java.io.File(dir, "sample.txt").apply { writeText("第一章 开始\n你好\n第二章 下一章\n正文") }
            val epub = java.io.File(dir, "sample.epub")
            TextPublication.convert(source, epub, "样本")
            SafeArchives.validate(epub)
            ZipFile(epub).use { assertNotNull(it.getEntry("c1.xhtml")); assertEquals(0, it.getEntry("mimetype").method) }
            assertTrue(SafeArchives.read(epub, "book.opf").decodeToString().contains("样本"))
        } finally { dir.deleteRecursively() }
    }
}
