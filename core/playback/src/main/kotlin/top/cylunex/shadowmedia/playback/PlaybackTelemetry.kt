package top.cylunex.shadowmedia.playback

data class PlaybackTelemetrySnapshot(
    val id: String,
    val providerId: String,
    val itemId: String,
    val playSessionId: String?,
    val method: String,
    val requestHost: String?,
    val candidateIndex: Int,
    val startedAtEpochMs: Long,
    val firstFrameMs: Long?,
    val bufferingCount: Int,
    val bufferingDurationMs: Long,
    val errorCode: String?,
    val errorMessage: String?,
    val completed: Boolean,
    val terminal: Boolean,
)

fun interface PlaybackTelemetrySink {
    fun record(snapshot: PlaybackTelemetrySnapshot)

    companion object {
        val NONE = PlaybackTelemetrySink { }
    }
}

data class PlaybackTracksState(
    val audioTracks: List<String> = emptyList(),
    val selectedAudioIndex: Int = -1,
    val subtitleTracks: List<String> = emptyList(),
    val selectedSubtitleIndex: Int = -1,
)
