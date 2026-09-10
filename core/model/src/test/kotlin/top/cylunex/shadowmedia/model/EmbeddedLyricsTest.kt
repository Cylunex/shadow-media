package top.cylunex.shadowmedia.model

import org.junit.Assert.*
import org.junit.Test

class EmbeddedLyricsTest {
    @Test fun utf8AndUtf16RespectDescriptorBoundaries() {
        assertEquals("中文歌词", id3Lyrics("USLT", byteArrayOf(3, 101, 110, 103, 0) + "中文歌词".toByteArray()))
        val utf16 = byteArrayOf(1, 101, 110, 103) + "说明".toByteArray(Charsets.UTF_16) + byteArrayOf(0, 0) + "歌词".toByteArray(Charsets.UTF_16)
        assertEquals("歌词", id3Lyrics("USLT", utf16))
    }
    @Test fun synchronizedTagUsesMediaMillisecondsAndRejectsTruncation() {
        val tag = byteArrayOf(3, 101, 110, 103, 2, 1, 0) + "line".toByteArray() + byteArrayOf(0, 0, 0, 3, -24)
        assertEquals("[00:01.000]line", id3Lyrics("SYLT", tag))
        assertNull(id3Lyrics("SYLT", tag.copyOf(tag.size - 1)))
        tag[4] = 1; assertNull(id3Lyrics("SYLT", tag))
    }
    @Test fun invalidTagsAreIgnored() {
        assertNull(id3Lyrics("USLT", byteArrayOf(3, 1, 2, 3, 65)))
        assertNull(id3Lyrics("USLT", ByteArray(512 * 1024 + 1)))
        assertNull(id3Lyrics("OTHER", byteArrayOf(0, 1, 2, 3, 0)))
    }
}
