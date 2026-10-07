package top.cylunex.shadowmedia.model

data class ExternalSubtitle(val uri: String, val mimeType: String, val label: String)

fun subtitleMimeType(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "srt" -> "application/x-subrip"
    "ass", "ssa" -> "text/x-ssa"
    "vtt" -> "text/vtt"
    else -> null
}

/** Match exact stems and optional language suffixes without matching other movies. */
fun isCompanionSubtitle(videoName: String, subtitleName: String): Boolean {
    if (subtitleMimeType(subtitleName) == null) return false
    val video = videoName.substringBeforeLast('.').lowercase()
    val subtitle = subtitleName.substringBeforeLast('.').lowercase()
    return subtitle == video || subtitle.startsWith("$video.")
}

fun gestureSeekPosition(positionMs: Long, durationMs: Long, fraction: Float): Long =
    (positionMs + (fraction * 120_000L).toLong()).coerceIn(0, durationMs.coerceAtLeast(0))
