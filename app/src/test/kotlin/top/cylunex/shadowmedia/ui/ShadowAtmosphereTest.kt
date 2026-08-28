package top.cylunex.shadowmedia.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShadowAtmosphereTest {
    @Test
    fun `external labels remove emoji without damaging chinese text`() {
        assertEquals("歌配置 中心", "\uD83D\uDC32歌配置  中心".withoutEmoji())
        assertEquals("电影频道", "电影频道".withoutEmoji())
    }
}
