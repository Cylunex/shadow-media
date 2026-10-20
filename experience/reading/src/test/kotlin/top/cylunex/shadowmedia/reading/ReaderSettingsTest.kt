package top.cylunex.shadowmedia.reading

import org.junit.Assert.*
import org.junit.Test

class ReaderSettingsTest {
    @Test fun centerAlwaysOpensMenuAndDisabledEdgesDoNotTurn() {
        for (x in listOf(0.3f, 0.5f, 0.7f)) {
            assertEquals(0, readerTap(x, true, false, false))
            assertEquals(0, readerTap(x, true, false, true))
        }
        assertEquals(0, readerTap(0f, false, false, false))
        assertEquals(0, readerTap(1f, false, false, true))
    }
    @Test fun rightToLeftBooksReversePhysicalTapEdges() {
        assertEquals(-1, readerTap(0.1f, true, false, false))
        assertEquals(1, readerTap(0.9f, true, false, false))
        assertEquals(1, readerTap(0.1f, true, false, true))
        assertEquals(-1, readerTap(0.9f, true, false, true))
    }
    @Test fun continuousScrollLeavesEveryTapAvailableForMenu() {
        for (x in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) assertEquals(0, readerTap(x, true, true, false))
    }
    @Test fun smallOrVerticalDragsNeverTurnPages() {
        assertEquals(0, readerSwipe(9f, 0f, 10f, false))
        assertEquals(0, readerSwipe(20f, 60f, 10f, false))
        assertEquals(0, readerSwipe(-20f, -60f, 10f, true))
        assertEquals(0, readerSwipe(30f, 30f, 10f, false))
    }
    @Test fun horizontalSwipesRespectBookDirection() {
        assertEquals(1, readerSwipe(-100f, 5f, 10f, false))
        assertEquals(-1, readerSwipe(100f, 5f, 10f, false))
        assertEquals(-1, readerSwipe(-100f, 5f, 10f, true))
        assertEquals(1, readerSwipe(100f, 5f, 10f, true))
    }
    @Test fun invalidOrOutOfRangeTypographyCannotReachEngine() {
        val value = ReaderSettings(fontSize = Float.NaN, lineHeight = Float.POSITIVE_INFINITY,
            paragraphSpacing = -50f, letterSpacing = 99f, margins = 0f, brightness = Float.NaN).normalized()
        assertEquals(115f, value.fontSize)
        assertEquals(1.7f, value.lineHeight)
        assertEquals(0f, value.paragraphSpacing)
        assertEquals(0.15f, value.letterSpacing)
        assertEquals(0.4f, value.margins)
        assertEquals(-1f, value.brightness)
    }
    @Test fun brightnessCanFollowSystemOrStayWithinReadableBounds() {
        assertEquals(-1f, ReaderSettings(brightness = -1f).normalized().brightness)
        assertEquals(0.02f, ReaderSettings(brightness = 0f).normalized().brightness)
        assertEquals(1f, ReaderSettings(brightness = 10f).normalized().brightness)
    }
    @Test fun pinchCannotMakeTextUnreadablySmallOrLarge() {
        assertEquals(80f, ReaderSettings(fontSize = 1f).normalized().fontSize)
        assertEquals(220f, ReaderSettings(fontSize = 9999f).normalized().fontSize)
    }
}
