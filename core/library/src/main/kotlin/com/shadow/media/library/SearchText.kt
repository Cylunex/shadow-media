package com.shadow.media.library

/** Search-only folding. Every mapping keeps UTF-16 offsets in the original text. */
object SearchText {
    private val characters: Map<Int, Int> by lazy {
        val stream = checkNotNull(SearchText::class.java.getResourceAsStream("/com/shadow/media/search/TSCharacters.txt"))
        val raw = buildMap {
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                    val fields = line.split('\t')
                    val source = fields[0]
                    val target = fields[1].substringBefore(' ')
                    if (source.codePointCount(0, source.length) == 1 && target.codePointCount(0, target.length) == 1 && source.length == target.length)
                        put(source.codePointAt(0), target.codePointAt(0))
                }
            }
            for (code in 0xFF01..0xFF5E) put(code, code - 0xFEE0)
            put(0x3000, 0x20)
        }
        raw.mapValues { (source, value) ->
            val visited = mutableSetOf(source)
            var target = value
            while (target in raw && visited.add(target)) target = raw.getValue(target)
            target
        }
    }

    fun fold(text: String): String = buildString(text.length) {
        var offset = 0
        while (offset < text.length) {
            val code = text.codePointAt(offset)
            appendCodePoint(characters[code] ?: code)
            offset += Character.charCount(code)
        }
    }

    fun contains(text: String, query: String): Boolean = fold(text).contains(fold(query.trim()), ignoreCase = true)
}
