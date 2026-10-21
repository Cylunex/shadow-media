package com.shadow.media.network

import com.shadow.media.model.EmbySession
import com.shadow.media.model.BrowseRequest
import com.shadow.media.model.ExternalSourceSummary
import com.shadow.media.model.ExternalSourceImport
import com.shadow.media.model.ExternalCatalogSite
import com.shadow.media.model.IntegrationConnection
import com.shadow.media.model.IntegrationStatus
import com.shadow.media.model.ExternalMediaEntry
import com.shadow.media.model.MediaItem
import com.shadow.media.model.MediaLibrary
import com.shadow.media.model.MediaPage
import com.shadow.media.model.MediaSection
import com.shadow.media.model.PlayMethod
import com.shadow.media.model.PlaybackEvent
import com.shadow.media.model.PlaybackPlan

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

interface IntegrationStore {
    fun loadAll(): List<IntegrationConnection>
    fun save(connection: IntegrationConnection)
    fun remove(connectionId: String)
}

interface IntegrationRepository {
    suspend fun probe(connection: IntegrationConnection): IntegrationStatus
    suspend fun requestMedia(
        connection: IntegrationConnection,
        tmdbId: Int,
        mediaType: String,
    ): String

    fun virtualChannelUrls(connection: IntegrationConnection): Pair<String, String?>?
}

interface EmbyRepository {
    fun favoriteStates(session: EmbySession): kotlinx.coroutines.flow.Flow<Map<String, Boolean>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    suspend fun flushUserStates(session: EmbySession) {}
    suspend fun cachedLibraries(session: EmbySession): List<MediaLibrary>? = null
    suspend fun cachedHome(session: EmbySession, libraryIds: List<String>): List<MediaSection>? = null
    suspend fun cachedBrowse(session: EmbySession, request: BrowseRequest): MediaPage? = null
    suspend fun cachedChildren(session: EmbySession, parentId: String): List<MediaItem>? = null
    suspend fun cachedRecentVideos(session: EmbySession, libraryId: String): List<MediaItem>? = null
    suspend fun login(request: LoginRequest): EmbySession
    suspend fun libraries(session: EmbySession): List<MediaLibrary>
    suspend fun recentVideos(
        session: EmbySession,
        libraryId: String,
    ): List<MediaItem>

    suspend fun nextUp(session: EmbySession): List<MediaItem> = emptyList()
    suspend fun home(session: EmbySession, libraryIds: List<String>): List<MediaSection>
    suspend fun browse(session: EmbySession, request: BrowseRequest): MediaPage
    suspend fun children(session: EmbySession, parentId: String): List<MediaItem>
    suspend fun item(session: EmbySession, itemId: String): MediaItem = error("此来源不支持详情回源")
    suspend fun audioPlan(session: EmbySession, itemId: String): PlaybackPlan = error("此来源不支持音频")
    suspend fun updateAudioPosition(session: EmbySession, itemId: String, positionMs: Long, completed: Boolean) { error("此服务不支持离线进度") }
    suspend fun setFavorite(session: EmbySession, itemId: String, favorite: Boolean)

    suspend fun playbackPlan(session: EmbySession, itemId: String): PlaybackPlan
    suspend fun reportPlayback(session: EmbySession, report: PlaybackReport)
    suspend fun deleteItem(session: EmbySession, itemId: String)
    suspend fun logout(session: EmbySession)
}
