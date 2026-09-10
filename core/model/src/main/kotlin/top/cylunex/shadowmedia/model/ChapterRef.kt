package top.cylunex.shadowmedia.model

data class ChapterRef(val resourceRevision: String, val trackId: String, val chapterId: String,
    val title: String, val startMs: Long, val endMs: Long?)
data class BookChapter(val id: String, val title: String, val startMs: Long, val endMs: Long)
/** Book time -> physical track time. A chapter spanning files keeps its id in each clipped segment. */
fun trackChapters(chapters: List<BookChapter>, trackId: String, revision: String, offsetMs: Long, durationMs: Long): List<ChapterRef> {
    require(offsetMs >= 0 && durationMs > 0 && offsetMs <= Long.MAX_VALUE - durationMs)
    return chapters.filter { it.startMs >= 0 && it.endMs > it.startMs && it.endMs > offsetMs && it.startMs < offsetMs + durationMs }
        .sortedBy { it.startMs }.map { ChapterRef(revision, trackId, it.id, it.title,
            (it.startMs - offsetMs).coerceAtLeast(0), (it.endMs - offsetMs).coerceAtMost(durationMs)) }
}
