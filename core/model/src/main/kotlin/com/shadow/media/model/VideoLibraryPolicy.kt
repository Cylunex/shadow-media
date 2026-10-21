package com.shadow.media.model

val MediaLibrary.isMixedVideoLibrary: Boolean get() = collectionType.isNullOrBlank() || collectionType.equals("mixed", true) || collectionType.equals("homevideos", true)

/** Applied inside one account only. An unfinished episode takes precedence over its next episode. */
fun mergeContinueAndNextUp(continuing: List<MediaItem>, following: List<MediaItem>): List<MediaItem> {
    val resume = continuing.filter { !videoCompleted(it.playbackPositionTicks, it.runTimeTicks, it.played) && !it.isFolder }.distinctBy { it.id }
    val activeSeries = resume.mapNotNull { it.seriesId }.toSet()
    return (resume + following.filter { !videoCompleted(it.playbackPositionTicks, it.runTimeTicks, it.played) && !it.isFolder && it.seriesId !in activeSeries }).distinctBy { it.id }
}

data class VideoQueueEntry(val entryId: String, val key: MediaKey, val title: String)

/** Entries identify occurrences, so adding one movie twice never aliases edits or progress. */
data class VideoQueue(
    val entries: List<VideoQueueEntry> = emptyList(),
    val currentEntryId: String? = null,
    val positionMs: Long = 0,
    val repeat: Boolean = false,
) {
    val currentIndex: Int get() = entries.indexOfFirst { it.entryId == currentEntryId }
    val current: VideoQueueEntry? get() = entries.getOrNull(currentIndex)
    fun select(id: String) = if (entries.any { it.entryId == id }) copy(currentEntryId = id, positionMs = 0) else this
    fun move(id: String, delta: Int): VideoQueue {
        val index = entries.indexOfFirst { it.entryId == id }
        if (index < 0) return this
        val target = (index + delta).coerceIn(0, entries.lastIndex)
        val ordered = entries.toMutableList().apply { add(target, removeAt(index)) }
        return copy(entries = ordered)
    }
    fun remove(id: String): VideoQueue {
        val index = entries.indexOfFirst { it.entryId == id }
        val remaining = entries.filterNot { it.entryId == id }
        return if (id != currentEntryId) copy(entries = remaining) else copy(entries = remaining,
            currentEntryId = remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))?.entryId, positionMs = 0)
    }
    fun next(): VideoQueue? {
        if (entries.isEmpty() || currentIndex < 0) return null
        val target = currentIndex + 1
        return if (target < entries.size) select(entries[target].entryId) else if (repeat) select(entries.first().entryId) else null
    }
}

fun videoCompleted(position: Long, duration: Long?, played: Boolean = false): Boolean = played || (duration != null && duration > 0 && position.toDouble() >= duration.toDouble() * .92)
