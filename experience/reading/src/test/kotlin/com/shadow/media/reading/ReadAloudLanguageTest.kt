package com.shadow.media.reading

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudLanguageTest {
    @Test
    fun matchesEngineLanguageCodesWithoutSubstitutingUnrelatedVoices() {
        assertTrue(sameSpeechLanguage(Locale.forLanguageTag("zho-CN"), Locale.SIMPLIFIED_CHINESE))
        assertTrue(sameSpeechLanguage(Locale.forLanguageTag("chi"), Locale.TRADITIONAL_CHINESE))
        assertTrue(sameSpeechLanguage(Locale.forLanguageTag("cmn"), Locale.SIMPLIFIED_CHINESE))
        assertTrue(sameSpeechLanguage(Locale.forLanguageTag("eng-US"), Locale.US))
        assertFalse(sameSpeechLanguage(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE))
        assertFalse(sameSpeechLanguage(Locale.forLanguageTag("yue-HK"), Locale.SIMPLIFIED_CHINESE))
    }
}
