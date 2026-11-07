package com.shadow.media.library

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovelTextRulesTest {
    private fun rule(pattern: String, replacement: String = "", regex: Boolean = false, enabled: Boolean = true) =
        JSONObject().put("pattern", pattern).put("replacement", replacement).put("is_regex", regex).put("enabled", enabled)
    private fun settings(vararg rules: JSONObject) = JSONObject().put("items", JSONArray(rules.toList()))

    @Test fun orderedRulesRemoveAdsAndSupportGroupsWithoutChangingOriginal() {
        val original = "广告123山海。😀"
        val rules = settings(rule("广告\\d+", regex = true), rule("(山)(海)", "\$2\$1-\$\$", true), rule("海山", "旅人"))
        val result = NovelTextRules.apply(original, rules)
        assertEquals("旅人-\$。😀", result.text)
        assertEquals("", result.warning)
        assertEquals("广告123山海。😀", original)
    }
    @Test fun disabledRulesAndLiteralMetacharactersRemainLiteral() {
        val text = "正文[a.*]广告"
        val result = NovelTextRules.apply(text, settings(rule("广告", enabled = false), rule("[a.*]", "\$1")))
        assertEquals("正文\$1广告", result.text)
    }
    @Test fun failedRuleRestoresWholeOriginalRatherThanPartialReplacement() {
        val result = NovelTextRules.apply("广告正文", settings(rule("广告"), rule("(", regex = true)))
        assertEquals("广告正文", result.text)
        assertTrue(result.warning.isNotBlank())
    }
    @Test fun pathologicalRegexAndExpansionCannotBlockReading() {
        val original = "广告" + "a".repeat(100_000) + "!"
        val start = System.nanoTime()
        val result = NovelTextRules.apply(original, settings(rule("广告"), rule("(a+)+$", regex = true)))
        assertEquals(original, result.text)
        assertTrue(result.warning.isNotBlank())
        assertTrue((System.nanoTime() - start) < 2_000_000_000L)
        val expanded = NovelTextRules.apply("a".repeat(1000), settings(rule("a", "b".repeat(1000))))
        assertEquals("a".repeat(1000), expanded.text)
        assertTrue(expanded.warning.isNotBlank())
    }
    @Test fun anEntireAdChapterMayBecomeEmptyAndDisablingRestoresIt() {
        assertEquals("", NovelTextRules.apply("广告", settings(rule("广告"))).text)
        assertEquals("广告", NovelTextRules.apply("广告", settings(rule("广告", enabled = false))).text)
    }
}
