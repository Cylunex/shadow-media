package com.shadow.media.library

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchTextTest {
    @Test fun chineseAndWidthFoldingKeepsOriginalOffsetsAndBothSearchDirections() {
        val original = "𠀀😀龍門ＡＢＣ　歸來"
        assertEquals("𠀀😀龙门ABC 归来", SearchText.fold(original))
        assertEquals(original.length, SearchText.fold(original).length)
        assertTrue(SearchText.contains(original, "龙门abc"))
        assertTrue(SearchText.contains("龙门ABC", "龍門ＡＢＣ"))
        assertTrue(SearchText.contains("100%_字面", "%_"))
        assertFalse(SearchText.contains("其他故事", "龙门"))
        assertEquals("苎苎苎", SearchText.fold("薴苧苎"))
        assertEquals(SearchText.fold(original), SearchText.fold(SearchText.fold(original)))
    }

    @Test fun offlineContentsFindsTraditionalTitlesAcrossWindowsWithoutChangingThem() {
        val chapters = listOf("龍門歸來", "其他", "龙门归来").mapIndexed { i, title ->
            JSONObject().put("id", "c$i").put("ordinal", i * 300).put("title", title).put("readable", true)
        }
        val page = novelOfflineContents(chapters, NovelContentsQuery("v1", "龙门归来", true, limit = 1))
        assertEquals(2, page.getInt("total"))
        assertEquals(600, page.novelObjects("items").single().getInt("ordinal"))
        assertEquals("龍門歸來", chapters.first().getString("title"))
        assertTrue(page.getBoolean("_downloaded_only"))
        assertEquals(1, novelContentsNext(page))
    }
}
