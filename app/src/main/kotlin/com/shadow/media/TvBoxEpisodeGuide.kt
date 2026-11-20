package com.shadow.media

import com.shadow.media.database.MediaHistoryEntity
import com.shadow.media.model.MediaKey
import com.shadow.media.model.UnifiedMediaItem

internal data class TvBoxEpisodeGuide(
    val lines: List<String>,
    val line: String?,
    val episodes: List<UnifiedMediaItem>,
    val target: UnifiedMediaItem?,
    val action: String,
)

internal fun tvBoxEpisodeGuide(
    series: UnifiedMediaItem,
    children: List<UnifiedMediaItem>,
    history: List<MediaHistoryEntity>,
    selectedLine: String?,
): TvBoxEpisodeGuide {
    val records = history.filter { it.completed || it.positionMs > 0 }.associateBy { MediaKey(it.providerId, it.itemId) }
    val lines = children.map { it.subtitle.orEmpty() }.distinct()
    fun lastWatched(items: List<UnifiedMediaItem>) = items
        .filter { it.key in records }
        .maxByOrNull { records.getValue(it.key).lastPlayedAtEpochMs }
    val line = selectedLine?.takeIf { it in lines } ?: lastWatched(children)?.let { it.subtitle.orEmpty() }
        ?: lines.firstOrNull()
    val episodes = children.filter { it.subtitle.orEmpty() == line }.map { item ->
        val record = records[item.key] ?: return@map item
        item.copy(progressMs = if (record.completed) 0 else record.positionMs.coerceAtLeast(0), played = record.completed)
    }
    val latest = lastWatched(episodes)
    val next = if (latest?.played == true) {
        val watchedTitles = episodes.filter { it.played }.mapTo(mutableSetOf()) { it.title }
        episodes.drop(episodes.indexOf(latest) + 1).firstOrNull { it.title !in watchedTitles }
    } else null
    val target = when {
        latest == null -> episodes.firstOrNull()
        !latest.played -> latest
        else -> next ?: lastWatched(episodes.filter { !it.played }) ?: latest
    }
    val label = target?.title.orEmpty().removePrefix(series.title + " · ")
    val action = when {
        target == null -> "没有可播放分集"
        target.progressMs > 0 -> "继续 $label · ${formatDuration(target.progressMs)}"
        next != null -> "接着看 $label"
        target.played -> "重播 $label"
        else -> "播放 $label"
    }
    return TvBoxEpisodeGuide(lines, line, episodes, target, action)
}
