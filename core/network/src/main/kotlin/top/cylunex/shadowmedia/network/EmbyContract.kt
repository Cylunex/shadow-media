package top.cylunex.shadowmedia.network

import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.MediaItem
import top.cylunex.shadowmedia.model.MediaLibrary
import top.cylunex.shadowmedia.model.PlayMethod
import top.cylunex.shadowmedia.model.PlaybackEvent
import top.cylunex.shadowmedia.model.PlaybackPlan

data class LoginRequest(
    val serverUrl: String,
    val userName: String,
    val password: String,
    val allowInsecureHttp: Boolean,
)

data class PlaybackReport(
    val plan: PlaybackPlan,
    val positionTicks: Long,
    val isPaused: Boolean,
    val event: PlaybackEvent,
    val playMethod: PlayMethod = plan.primary.method,
)

interface SessionStore {
    fun load(): EmbySession?
    fun save(session: EmbySession)
    fun clear()
}

interface EmbyRepository {
    suspend fun login(request: LoginRequest): EmbySession
    suspend fun libraries(session: EmbySession): List<MediaLibrary>
    suspend fun recentVideos(
        session: EmbySession,
        libraryId: String,
        limit: Int = 20,
    ): List<MediaItem>

    suspend fun playbackPlan(session: EmbySession, itemId: String): PlaybackPlan
    suspend fun reportPlayback(session: EmbySession, report: PlaybackReport)
    suspend fun logout(session: EmbySession)
}
