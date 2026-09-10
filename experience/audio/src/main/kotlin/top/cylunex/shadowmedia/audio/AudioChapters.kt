@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package top.cylunex.shadowmedia.audio

import androidx.media3.common.Player
import androidx.media3.extractor.metadata.Chapter

data class AudioChapter(val title: String, val startMs: Long, val endMs: Long? = null)

/** Static MP4/ID3 chapters arrive with the selected track formats, without a second file read. */
fun Player.audioChapters(): List<AudioChapter> {
    val chapters = currentTracks.groups.filter { it.isSelected }.flatMap { group ->
        (0 until group.length).flatMap { track ->
            val metadata = group.getTrackFormat(track).metadata
            (0 until (metadata?.length() ?: 0)).mapNotNull { index ->
                (metadata?.get(index) as? Chapter)?.takeUnless { it.isHidden }?.let {
                    AudioChapter(it.title?.value.orEmpty(), it.startTimeMs, it.endTimeMs.takeIf { end -> end > it.startTimeMs })
                }
            }
        }
    }
    return normalizeChapters(chapters, duration.takeIf { it > 0 })
}

internal fun normalizeChapters(chapters: List<AudioChapter>, durationMs: Long?): List<AudioChapter> {
    val valid = chapters.filter { it.startMs >= 0 && (durationMs == null || it.startMs < durationMs) }
        .sortedBy { it.startMs }.distinctBy { it.startMs }
    return valid.mapIndexed { index, chapter ->
        val boundary = valid.getOrNull(index + 1)?.startMs ?: durationMs
        val end = listOfNotNull(chapter.endMs?.takeIf { it > chapter.startMs }, boundary).minOrNull()
        chapter.copy(title = chapter.title.trim().ifBlank { "章节 ${index + 1}" }, endMs = end)
    }
}

fun List<AudioChapter>.chapterAt(positionMs: Long): AudioChapter? = lastOrNull {
    positionMs >= it.startMs && (it.endMs == null || positionMs < it.endMs)
}
