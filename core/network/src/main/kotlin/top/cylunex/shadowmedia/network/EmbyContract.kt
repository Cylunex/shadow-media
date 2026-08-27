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
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String,
    val positionTicks: Long,
    val isPaused: Boolean,
    val canSeek: Boolean = true,
    val event: PlaybackEvent,
    val playMethod: PlayMethod,
)

interface PlaybackOutbox {
    val pendingCount: kotlinx.coroutines.flow.StateFlow<Int>
    suspend fun submit(session: EmbySession, report: PlaybackReport)
    suspend fun flush(session: EmbySession)
    suspend fun discard(session: EmbySession)
}

interface SessionStore {
    fun load(): EmbySession?
    fun loadAll(): List<EmbySession>
    fun save(session: EmbySession)
    fun select(session: EmbySession): Boolean
    fun remove(session: EmbySession)
    fun clearAll()
}

interface EmbyRepository {
    suspend fun login(request: LoginRequest): EmbySession
    suspend fun libraries(session: EmbySession): List<MediaLibrary>
    suspend fun recentVideos(
        session: EmbySession,
        libraryId: String,
    ): List<MediaItem>

    suspend fun playbackPlan(session: EmbySession, itemId: String): PlaybackPlan
    suspend fun reportPlayback(session: EmbySession, report: PlaybackReport)
    suspend fun deleteItem(session: EmbySession, itemId: String)
    suspend fun logout(session: EmbySession)
}
