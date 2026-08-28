package top.cylunex.shadowmedia.network

import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.BrowseRequest
import top.cylunex.shadowmedia.model.ExternalSourceSummary
import top.cylunex.shadowmedia.model.ExternalSourceImport
import top.cylunex.shadowmedia.model.ExternalCatalogSite
import top.cylunex.shadowmedia.model.ExternalMediaEntry
import top.cylunex.shadowmedia.model.MediaItem
import top.cylunex.shadowmedia.model.MediaLibrary
import top.cylunex.shadowmedia.model.MediaPage
import top.cylunex.shadowmedia.model.MediaSection
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

interface ExternalSourceStore {
    fun loadAll(): List<ExternalSourceSummary>
    fun load(sourceId: String): ExternalSourceImport?
    fun save(source: ExternalSourceImport)
    fun remove(sourceId: String)
}

interface ExternalSourceRepository {
    suspend fun importFromUrl(url: String, allowInsecureHttp: Boolean): ExternalSourceImport
    fun importPayload(url: String, payload: String, displayName: String? = null): ExternalSourceImport
    fun entries(source: ExternalSourceImport): List<ExternalMediaEntry>
    fun catalogSites(source: ExternalSourceImport): List<ExternalCatalogSite>
}

interface EmbyRepository {
    suspend fun login(request: LoginRequest): EmbySession
    suspend fun libraries(session: EmbySession): List<MediaLibrary>
    suspend fun recentVideos(
        session: EmbySession,
        libraryId: String,
    ): List<MediaItem>

    suspend fun home(session: EmbySession, libraryIds: List<String>): List<MediaSection>
    suspend fun browse(session: EmbySession, request: BrowseRequest): MediaPage
    suspend fun children(session: EmbySession, parentId: String): List<MediaItem>
    suspend fun setFavorite(session: EmbySession, itemId: String, favorite: Boolean)

    suspend fun playbackPlan(session: EmbySession, itemId: String): PlaybackPlan
    suspend fun reportPlayback(session: EmbySession, report: PlaybackReport)
    suspend fun deleteItem(session: EmbySession, itemId: String)
    suspend fun logout(session: EmbySession)
}
