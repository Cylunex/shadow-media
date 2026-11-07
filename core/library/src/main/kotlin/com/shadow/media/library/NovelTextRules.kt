package com.shadow.media.library

import org.json.JSONObject
import java.util.regex.Pattern

data class NovelPurifiedText(val text: String, val warning: String = "")

/** Originals stay in the transport cache. Derive display, search and speech from the same text. */
object NovelTextRules {
    private const val MAX_TEXT = 512_000
    private val groups = Regex("\\$\\$|\\$(\\d+)")
    fun apply(original: String, settings: JSONObject): NovelPurifiedText {
        var text = original
        val deadline = System.nanoTime() + 250_000_000L
        try {
            for (rule in settings.novelObjects("items").filter { it.optBoolean("enabled", true) }) {
                check(System.nanoTime() < deadline && text.length <= MAX_TEXT)
                val pattern = rule.getString("pattern")
                require(pattern.isNotBlank())
                val replacement = rule.optString("replacement")
                if (!rule.optBoolean("is_regex")) {
                    var start = 0
                    val output = StringBuilder()
                    while (true) {
                        check(System.nanoTime() < deadline)
                        val next = text.indexOf(pattern, start)
                        if (next < 0) break
                        output.append(text, start, next).append(replacement)
                        check(output.length <= MAX_TEXT)
                        start = next + pattern.length
                    }
                    text = output.append(text, start, text.length).toString()
                } else {
                    val until = minOf(deadline, System.nanoTime() + 50_000_000L)
                    val matcher = Pattern.compile(pattern).matcher(DeadlineText(text, until))
                    val output = StringBuilder()
                    var start = 0
                    while (matcher.find()) {
                        output.append(text, start, matcher.start())
                        output.append(groups.replace(replacement) { token ->
                            if (token.value == "\$\$") "\$" else matcher.group(token.groupValues[1].toInt()).orEmpty()
                        })
                        check(output.length <= MAX_TEXT)
                        start = matcher.end()
                    }
                    text = output.append(text, start, text.length).toString()
                }
                check(text.length <= MAX_TEXT)
            }
            check(System.nanoTime() < deadline)
            return NovelPurifiedText(text)
        } catch (_: Exception) {
            return NovelPurifiedText(original, "规则无效、超时或替换结果过长，已保留原文，请检查全局替换")
        } catch (_: StackOverflowError) {
            return NovelPurifiedText(original, "正则过于复杂，已保留原文，请检查全局替换")
        }
    }

    private class DeadlineText(private val value: String, private val deadline: Long) : CharSequence {
        override val length get() = value.length
        override fun get(index: Int): Char { check(System.nanoTime() < deadline); return value[index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = DeadlineText(value.substring(startIndex, endIndex), deadline)
        override fun toString() = value
    }
}
