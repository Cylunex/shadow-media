package top.cylunex.shadowmedia.model

data class LyricLine(val timeMs: Long?, val text: String)
/** LRC supports multiple timestamps, centiseconds/milliseconds and the global offset tag. */
fun parseLyrics(value: String): List<LyricLine> {
    val timestamp = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0
    val timed = mutableListOf<LyricLine>()
    val plain = mutableListOf<LyricLine>()
    value.take(512 * 1024).lineSequence().forEach { line ->
        val matches = timestamp.findAll(line).toList()
        val text = line.replace(timestamp, "").trim()
        if (matches.isNotEmpty()) matches.forEach { match ->
            val seconds = match.groupValues[2].toLong()
            val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
            if (seconds < 60 && minutes < 100_000) {
                val fraction = match.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0
                timed += LyricLine((minutes * 60000 + seconds * 1000 + fraction + offset.coerceIn(-86_400_000, 86_400_000)).coerceAtLeast(0), text)
            }
        } else if (text.isNotBlank() && !Regex("^\\[[a-zA-Z]+:.*]$").matches(text)) plain += LyricLine(null, text)
    }
    return if (timed.isNotEmpty()) timed.sortedBy { it.timeMs } else plain
}
