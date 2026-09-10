package top.cylunex.shadowmedia.model

/** ID3 USLT and millisecond SYLT payloads; malformed tags are optional metadata, never playback errors. */
fun id3Lyrics(id: String, data: ByteArray): String? = runCatching {
    require(data.size in 5..512 * 1024)
    val encoding = data[0].toInt() and 255
    val charset = when (encoding) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> error("encoding") }
    val width = if (encoding == 1 || encoding == 2) 2 else 1
    fun end(start: Int): Int {
        var cursor = start
        while (cursor + width <= data.size) {
            if (data[cursor] == 0.toByte() && (width == 1 || data[cursor + 1] == 0.toByte())) return cursor
            cursor += width
        }
        error("unterminated text")
    }
    when (id) {
        "USLT" -> {
            val start = end(4) + width
            data.copyOfRange(start, data.size).toString(charset).trimEnd('\u0000').takeIf(String::isNotBlank)
        }
        "SYLT" -> {
            require(data.size >= 7 && data[4] == 2.toByte() && data[5] in byteArrayOf(0, 1, 2))
            var cursor = end(6) + width
            val lines = mutableListOf<String>()
            while (cursor < data.size) {
                val stop = end(cursor)
                val text = data.copyOfRange(cursor, stop).toString(charset)
                cursor = stop + width
                require(cursor + 4 <= data.size)
                var time = 0L
                repeat(4) { time = (time shl 8) or (data[cursor++].toLong() and 255) }
                lines += "[%02d:%02d.%03d]".format(java.util.Locale.ROOT, time / 60000, time / 1000 % 60, time % 1000) + text
            }
            lines.joinToString("\n").takeIf(String::isNotBlank)
        }
        else -> null
    }
}.getOrNull()
