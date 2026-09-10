package top.cylunex.shadowmedia.model

enum class FeedPreloadTarget { NONE, TRACKS, NEXT_THREE_SECONDS }
object FeedPreloadPolicy {
    fun target(index: Int, current: Int, settled: Boolean, unmetered: Boolean, allowed: Boolean): FeedPreloadTarget = when {
        !settled || !unmetered || !allowed -> FeedPreloadTarget.NONE
        index == current + 1 -> FeedPreloadTarget.NEXT_THREE_SECONDS
        index == current - 1 -> FeedPreloadTarget.TRACKS
        else -> FeedPreloadTarget.NONE
    }
    fun eligible(plan: PlaybackPlan, resolutionMs: Long): Boolean =
        resolutionMs < 1500 && plan.primary.method != PlayMethod.TRANSCODE && !plan.primary.isDiscImage &&
            !plan.container.orEmpty().lowercase().contains("iso") && !plan.videoType.orEmpty().lowercase().contains("iso") &&
            !plan.primary.url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".mpd") || it.endsWith(".iso") }
}
