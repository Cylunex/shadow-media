package top.cylunex.shadowmedia.model

data class EmbySession(
    val serverUrl: String,
    val serverId: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
    val allowInsecureHttp: Boolean,
) {
    override fun toString(): String =
        "EmbySession(serverUrl=$serverUrl, serverId=$serverId, userId=$userId, " +
            "userName=$userName, accessToken=<redacted>, allowInsecureHttp=$allowInsecureHttp)"
}

data class MediaLibrary(
    val id: String,
    val name: String,
    val collectionType: String?,
)

data class MediaItem(
    val id: String,
    val name: String,
    val type: String,
    val seriesName: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val runTimeTicks: Long?,
    val playbackPositionTicks: Long,
    val played: Boolean,
    val favorite: Boolean,
)

enum class PlayMethod {
    DIRECT_PLAY,
    DIRECT_STREAM,
    TRANSCODE,
}

data class PlaybackCandidate(
    val url: String,
    val method: PlayMethod,
    val requiredHeaders: Map<String, String>,
)

data class PlaybackPlan(
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String,
    val candidates: List<PlaybackCandidate>,
    val container: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val runTimeTicks: Long?,
) {
    init {
        require(candidates.isNotEmpty()) { "Playback plan requires at least one candidate" }
    }

    val primary: PlaybackCandidate get() = candidates.first()
}

enum class PlaybackEvent(val wireName: String?) {
    STARTED(null),
    TIME_UPDATE("TimeUpdate"),
    PAUSE("Pause"),
    UNPAUSE("Unpause"),
    STOPPED(null),
}

const val EMBY_TICKS_PER_MILLISECOND: Long = 10_000L

fun Long.millisecondsToEmbyTicks(): Long = this * EMBY_TICKS_PER_MILLISECOND

fun Long.embyTicksToMilliseconds(): Long = this / EMBY_TICKS_PER_MILLISECOND
