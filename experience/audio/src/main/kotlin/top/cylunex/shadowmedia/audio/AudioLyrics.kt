@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package top.cylunex.shadowmedia.audio

import androidx.media3.common.Player
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import top.cylunex.shadowmedia.model.id3Lyrics

fun Player.embeddedLyrics(): String? {
    for (group in currentTracks.groups.filter { it.isSelected }) for (track in 0 until group.length) {
        val metadata = group.getTrackFormat(track).metadata ?: continue
        for (i in 0 until metadata.length()) {
            val text = when (val entry = metadata[i]) {
                is BinaryFrame -> id3Lyrics(entry.id, entry.data)
                is TextInformationFrame -> if (entry.id == "TXXX" && entry.description?.contains("lyrics", true) == true) entry.values.joinToString("\n") else null
                is VorbisComment -> if (entry.key.equals("LYRICS", true) || entry.key.equals("UNSYNCEDLYRICS", true)) entry.value else null
                else -> null
            }
            if (!text.isNullOrBlank() && text.length <= 512 * 1024) return text
        }
    }
    return null
}
