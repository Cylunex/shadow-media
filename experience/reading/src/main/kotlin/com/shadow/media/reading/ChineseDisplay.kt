package com.shadow.media.reading

import android.content.res.AssetManager
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class ChineseDisplay(val label: String, val dictionary: String) {
    ORIGINAL("原文", ""), SIMPLIFIED("简体", "TS"), TRADITIONAL("繁体", "ST");
}

internal class ChineseProjection(
    val original: String,
    val rendered: String,
    private val toOriginal: IntArray,
    private val toRendered: IntArray,
    private val rangeEnds: Map<Int, Int>,
) {
    fun canonical(offset: Int, end: Boolean = false): Int {
        val point = safeTextOffset(rendered, offset)
        val canonical = if (end) rangeEnds[point] ?: toOriginal[point] else toOriginal[point]
        return safeTextOffset(original, canonical)
    }

    fun display(offset: Int): Int = safeTextOffset(rendered, toRendered[safeTextOffset(original, offset)])
}

internal class ChineseDictionary(private val words: Map<String, String>) {
    private val lengths = words.keys.groupBy { it.first() }.mapValues { (_, keys) ->
        keys.map(String::length).distinct().sortedDescending()
    }

    fun project(text: String): ChineseProjection {
        val output = StringBuilder(text.length)
        val toOriginal = ArrayList<Int>(text.length + 1).apply { add(0) }
        val toRendered = IntArray(text.length + 1)
        val rangeEnds = mutableMapOf<Int, Int>()
        var start = 0
        while (start < text.length) {
            val word = lengths[text[start]].orEmpty().firstNotNullOfOrNull { size ->
                if (start + size > text.length) null
                else words[text.substring(start, start + size)]?.let { size to it }
            }
            val size = word?.first ?: Character.charCount(Character.codePointAt(text, start))
            val replacement = word?.second ?: text.substring(start, start + size)
            val displayStart = output.length
            val end = start + size
            output.append(replacement)
            for (index in 1 until replacement.length) {
                toOriginal += if (size == replacement.length) start + index else start
                if (size != replacement.length) rangeEnds[displayStart + index] = end
            }
            toOriginal += end
            for (index in 1 until size) {
                toRendered[start + index] = if (size == replacement.length) displayStart + index else displayStart
            }
            toRendered[end] = output.length
            start = end
        }
        return ChineseProjection(text, output.toString(), toOriginal.toIntArray(), toRendered, rangeEnds)
    }
}

internal class ChineseDisplayConverter(private val assets: AssetManager) {
    private val dictionaries = mutableMapOf<ChineseDisplay, ChineseDictionary>()
    private val projections = LruCache<Pair<String, ChineseDisplay>, ChineseProjection>(4)

    suspend fun chapter(chapter: NativeTextChapter, mode: ChineseDisplay): NativeTextChapter {
        if (mode == ChineseDisplay.ORIGINAL) return chapter
        return withContext(Dispatchers.IO) { convert(chapter, mode) }
    }

    private fun convert(chapter: NativeTextChapter, mode: ChineseDisplay): NativeTextChapter {
        val projection = projections[chapter.textHash to mode] ?: run {
            val dictionary = synchronized(dictionaries) {
                dictionaries.getOrPut(mode) {
                    val words = buildMap {
                        for (kind in listOf("Characters", "Phrases")) {
                            assets.open("opencc/${mode.dictionary}$kind.txt").bufferedReader().useLines { lines ->
                                for (line in lines) {
                                    val key = line.substringBefore('\t', "")
                                    val value = line.substringAfter('\t', "").substringBefore(' ')
                                    if (key.isNotEmpty() && value.isNotEmpty()) put(key, value)
                                }
                            }
                        }
                    }
                    ChineseDictionary(words)
                }
            }
            dictionary.project(chapter.text).also { projections.put(chapter.textHash to mode, it) }
        }
        return chapter.copy(text = projection.rendered, projection = projection)
    }
}
