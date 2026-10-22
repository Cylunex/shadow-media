@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.shadow.media.reading

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReaderReadiumPreferencesTest {
    @Test fun paperAndTypographySurviveAStoreRoundTripIncludingTheLastDayTheme() {
        val preferences = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("paper-test", 0)
        preferences.edit().clear().commit()
        val store = ReaderSettingsStore(preferences)
        assertEquals(ReaderSettings(), store.load())
        val custom = ReaderSettings(paper = ReaderPaper.GREEN, paperTexture = .3f, justify = false,
            comfortableWidth = false, showProgress = false).toggleNight()
        store.save(custom)
        assertEquals(custom, ReaderSettingsStore(preferences).load())
        assertEquals(ReaderPaper.GREEN, store.load().toggleNight().paper)
        assertEquals(org.readium.r2.navigator.preferences.TextAlign.START, custom.epubPreferences().textAlign)
    }
    @Test fun defaultSettingsConstructValidReadiumPreferences() {
        val preferences = ReaderSettings().epubPreferences()
        assertEquals(1.15, preferences.fontSize!!, 0.00001)
        assertEquals(1.0, preferences.fontWeight!!, 0.0)
    }

    @Test fun boldAndAllFontSizesUseReadiumMultipliers() {
        for (bold in listOf(false, true)) for (font in listOf(80f, 115f, 150f, 220f)) {
            val preferences = ReaderSettings(fontSize = font, bold = bold).epubPreferences()
            assertEquals(font / 100.0, preferences.fontSize!!, 0.00001)
            assertEquals(if (bold) 1.75 else 1.0, preferences.fontWeight!!, 0.0)
        }
    }
    @Test fun everyPaperAndPageModeConstructsValidPreferences() {
        for (paper in ReaderPaper.entries) for (mode in ReaderPageMode.entries) {
            val preferences = ReaderSettings(paper = paper, pageMode = mode).epubPreferences()
            assertEquals(paper.background, preferences.backgroundColor!!.int)
            assertEquals(mode == ReaderPageMode.SCROLL, preferences.scroll)
        }
    }
    @Test fun invalidStoredValuesAreNormalizedBeforeReadiumConstruction() {
        val preferences = ReaderSettings(fontSize = Float.NaN, lineHeight = Float.NaN,
            paragraphSpacing = -4f, letterSpacing = Float.POSITIVE_INFINITY, margins = -3f).epubPreferences()
        assertEquals(1.15, preferences.fontSize!!, 0.00001)
        assertEquals(1.7, preferences.lineHeight!!, 0.00001)
        assertEquals(0.0, preferences.paragraphSpacing!!, 0.0)
        assertEquals(0.0, preferences.letterSpacing!!, 0.0)
        assertEquals(0.4, preferences.pageMargins!!, 0.00001)
    }
}
