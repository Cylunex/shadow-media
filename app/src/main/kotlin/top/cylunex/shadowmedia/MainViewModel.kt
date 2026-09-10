package top.cylunex.shadowmedia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.BrowseRequest
import top.cylunex.shadowmedia.model.ExternalSourceSummary
import top.cylunex.shadowmedia.model.ExternalMediaEntry
import top.cylunex.shadowmedia.model.LiveChannel
import top.cylunex.shadowmedia.model.LiveProgram
import top.cylunex.shadowmedia.model.MediaItem
import top.cylunex.shadowmedia.model.MediaFilter
import top.cylunex.shadowmedia.model.MediaLibrary
import top.cylunex.shadowmedia.model.MediaSection
import top.cylunex.shadowmedia.model.MediaSectionKind
import top.cylunex.shadowmedia.model.MediaSort
import top.cylunex.shadowmedia.model.PlaybackPlan
import top.cylunex.shadowmedia.model.PlaybackEvent
import top.cylunex.shadowmedia.model.embyTicksToMilliseconds
import top.cylunex.shadowmedia.model.millisecondsToEmbyTicks
import top.cylunex.shadowmedia.network.EmbyRepository
import top.cylunex.shadowmedia.network.ExternalSourceRepository
import top.cylunex.shadowmedia.network.ExternalSourceStore
import top.cylunex.shadowmedia.network.LoginRequest
import top.cylunex.shadowmedia.network.PlaybackOutbox
import top.cylunex.shadowmedia.network.PlaybackReport
import top.cylunex.shadowmedia.network.SessionStore
import top.cylunex.shadowmedia.database.LocalMediaStateRepository
import top.cylunex.shadowmedia.model.FeatureId
import top.cylunex.shadowmedia.model.MediaDetail
import top.cylunex.shadowmedia.model.MediaKey
import top.cylunex.shadowmedia.model.NetworkStorageConnection
import top.cylunex.shadowmedia.model.NetworkStorageHealth
import top.cylunex.shadowmedia.model.NetworkStorageKind
import top.cylunex.shadowmedia.model.NetworkStorageStatus
import top.cylunex.shadowmedia.model.ProviderDescriptor
import top.cylunex.shadowmedia.model.UnifiedMediaItem
import top.cylunex.shadowmedia.model.UnifiedPlaybackRequest
import top.cylunex.shadowmedia.model.toLiveChannels
import top.cylunex.shadowmedia.network.CatchupUrlResolver
import top.cylunex.shadowmedia.network.LiveGuideRepository
import top.cylunex.shadowmedia.network.TvBoxHttpMediaProvider
import top.cylunex.shadowmedia.database.MediaFavoriteEntity
import top.cylunex.shadowmedia.provider.AggregateSearchEngine
import top.cylunex.shadowmedia.provider.InMemoryProviderRegistry
import top.cylunex.shadowmedia.provider.ProviderSearchFailure
import top.cylunex.shadowmedia.provider.ProviderSearchRequest
import okhttp3.OkHttpClient
import java.util.UUID
import top.cylunex.shadowmedia.model.IntegrationConnection
import top.cylunex.shadowmedia.model.IntegrationKind
import top.cylunex.shadowmedia.model.IntegrationStatus
import top.cylunex.shadowmedia.model.MediaSegment
import top.cylunex.shadowmedia.model.SegmentSource
import top.cylunex.shadowmedia.model.SegmentType
import top.cylunex.shadowmedia.network.IntegrationRepository
import top.cylunex.shadowmedia.network.IntegrationStore
import top.cylunex.shadowmedia.network.NetworkStorageRepository
import top.cylunex.shadowmedia.network.NetworkStorageStore
import top.cylunex.shadowmedia.database.MediaMomentEntity
import top.cylunex.shadowmedia.database.PlaybackMetricEntity
import top.cylunex.shadowmedia.database.SourceHealthEntity

enum class Screen {
    SERVERS, LOGIN, HOME, LIBRARIES, ITEMS, DETAIL, SOURCES, EXTERNAL_ITEMS, EXTERNAL_PLAYER,
    DISCOVER, PROVIDER_DETAIL, NETWORK_STORAGES, INTEGRATIONS, INSIGHTS, SETTINGS, PLAYER
}

data class MainUiState(
    val screen: Screen = Screen.LOGIN,
    val serverUrl: String = "",
    val userName: String = "",
    val password: String = "",
    val allowInsecureHttp: Boolean = false,
    val savedSessions: List<EmbySession> = emptyList(),
    val session: EmbySession? = null,
    val libraries: List<MediaLibrary> = emptyList(),
    val homeSections: List<MediaSection> = emptyList(),
    val lastFeedLibraryId: String? = null,
    val selectedLibrary: MediaLibrary? = null,
    val wallItems: List<MediaItem> = emptyList(),
    val items: List<MediaItem> = emptyList(),
    val wallSearch: String = "",
    val wallSort: MediaSort = MediaSort.DATE_ADDED,
    val wallFilter: MediaFilter = MediaFilter.ALL,
    val wallTotalCount: Int = 0,
    val wallHasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val selectedSeries: MediaItem? = null,
    val detailEpisodes: List<MediaItem> = emptyList(),
    val playerReturnScreen: Screen = Screen.ITEMS,
    val feedMode: Boolean = false,
    val externalSources: List<ExternalSourceSummary> = emptyList(),
    val sourceUrl: String = "",
    val sourceAllowInsecureHttp: Boolean = false,
    val isInspectingSource: Boolean = false,
    val selectedExternalSource: ExternalSourceSummary? = null,
    val externalEntries: List<ExternalMediaEntry> = emptyList(),
    val liveChannels: List<LiveChannel> = emptyList(),
    val livePrograms: Map<String, List<LiveProgram>> = emptyMap(),
    val liveFavoriteKeys: Set<String> = emptySet(),
    val externalQuery: String = "",
    val externalGroup: String? = null,
    val isLoadingGuide: Boolean = false,
    val liveGuideMessage: String? = null,
    val selectedExternalEntry: ExternalMediaEntry? = null,
    val externalPlayerReturnScreen: Screen = Screen.EXTERNAL_ITEMS,
    val providerDescriptors: List<ProviderDescriptor> = emptyList(),
    val discoverQuery: String = "",
    val discoverResults: List<UnifiedMediaItem> = emptyList(),
    val providerSearchFailures: List<ProviderSearchFailure> = emptyList(),
    val providerSearchNext: Map<String, String> = emptyMap(),
    val loadingSearchPages: Set<String> = emptySet(),
    val selectedUnifiedDetail: MediaDetail? = null,
    val pendingPublication: UnifiedMediaItem? = null,
    val providerDetailBackStack: List<MediaDetail> = emptyList(),
    val providerDetailReturnScreen: Screen = Screen.DISCOVER,
    val isSearchingProviders: Boolean = false,
    val integrations: List<IntegrationConnection> = emptyList(),
    val integrationStatuses: Map<String, IntegrationStatus> = emptyMap(),
    val integrationKind: IntegrationKind = IntegrationKind.TUNARR,
    val integrationName: String = "",
    val integrationBaseUrl: String = "",
    val integrationApiToken: String = "",
    val integrationAllowInsecureHttp: Boolean = false,
    val integrationPlaylistUrl: String = "",
    val integrationEpgUrl: String = "",
    val integrationMessage: String? = null,
    val isSavingIntegration: Boolean = false,
    val networkStorages: List<NetworkStorageConnection> = emptyList(),
    val networkStorageStatuses: Map<String, NetworkStorageStatus> = emptyMap(),
    val networkStorageKind: NetworkStorageKind = NetworkStorageKind.OPENLIST,
    val networkStorageName: String = "",
    val networkStorageAddress: String = "",
    val networkStorageUsername: String = "",
    val networkStoragePassword: String = "",
    val networkStorageDomain: String = "",
    val networkStorageShare: String = "",
    val networkStorageRootPath: String = "/",
    val networkStorageAllowInsecureHttp: Boolean = false,
    val networkStorageReadNfo: Boolean = true,
    val networkStorageResolveStrm: Boolean = true,
    val networkStorageMessage: String? = null,
    val isSavingNetworkStorage: Boolean = false,
    val networkStorageReturnScreen: Screen = Screen.HOME,
    val mediaSegments: List<MediaSegment> = emptyList(),
    val insightMessage: String? = null,
    val selectedItem: MediaItem? = null,
    val currentIndex: Int = 0,
    val playbackPlan: PlaybackPlan? = null,
    val playbackStartPositionMs: Long = 0,
    val playbackRefreshAttempts: Int = 0,
    val pendingDeleteItem: MediaItem? = null,
    val pendingRemoveSession: EmbySession? = null,
    val isDeleting: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class MainViewModel(
    private val repository: EmbyRepository,
    private val sessionStore: SessionStore,
    private val feedSessionStore: FeedSessionStore,
    private val playbackOutbox: PlaybackOutbox,
    private val externalSourceRepository: ExternalSourceRepository,
    private val externalSourceStore: ExternalSourceStore,
    private val localMediaState: LocalMediaStateRepository,
    private val liveGuideRepository: LiveGuideRepository,
    private val providerRegistry: InMemoryProviderRegistry,
    private val aggregateSearchEngine: AggregateSearchEngine,
    private val externalClient: OkHttpClient,
    private val handoffInbox: HandoffInbox,
    private val integrationRepository: IntegrationRepository,
    private val integrationStore: IntegrationStore,
    private val networkStorageRepository: NetworkStorageRepository,
    private val networkStorageStore: NetworkStorageStore,
    private val library: top.cylunex.shadowmedia.library.LibraryRepository? = null,
    private val offline: top.cylunex.shadowmedia.library.OfflineRepository? = null,
    private val nativeProviders: () -> List<top.cylunex.shadowmedia.provider.MediaProvider> = { emptyList() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    private var playbackRequest: Job? = null
    private var contentRequest: Job? = null
    private val wallPagination = WallPagination()
    private val playbackOwnership = PlaybackOwnership()
    private var deleteRequest: Job? = null
    private var liveGuideRequest: Job? = null
    private var providerSearchRequest: Job? = null
    private var storageProbeRequest: Job? = null
    private var integrationProbeRequest: Job? = null
    private val searchPageJobs = mutableMapOf<String, Job>()
    private var searchGeneration = 0L
    private var submittedQuery = ""
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()
    val featureFlags: StateFlow<Map<FeatureId, Boolean>> = localMediaState.featureFlags()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            FeatureId.entries.associateWith { it.defaultEnabled },
        )
    val moments: StateFlow<List<MediaMomentEntity>> = localMediaState.recentMoments()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playbackMetrics: StateFlow<List<PlaybackMetricEntity>> = localMediaState.playbackMetrics()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sourceHealth: StateFlow<List<SourceHealthEntity>> = localMediaState.sourceHealth()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        val saved = sessionStore.loadAll()
        val active = sessionStore.load()
        if (active != null) restoreSession(active, saved) else {
            mutableState.value = MainUiState(
                screen = Screen.HOME,
                savedSessions = saved,
            )
        }
        syncProviders()
        viewModelScope.launch {
            handoffInbox.intents.collect { uri ->
                val handoff = uri.toMediaHandoffOrNull() ?: return@collect
                syncProviders()
                val provider = providerRegistry.provider(handoff.key.providerId)
                if (provider == null) {
                    update { copy(errorMessage = "接力链接对应的内容服务在这台设备上不可用") }
                    return@collect
                }
                cancelContentRequests()
                contentRequest = viewModelScope.launch {
                update {
                    copy(
                        screen = Screen.PROVIDER_DETAIL,
                        selectedUnifiedDetail = null,
                        providerDetailBackStack = emptyList(),
                        providerDetailReturnScreen = Screen.HOME,
                        isLoading = true,
                        errorMessage = null,
                    )
                }
                runCatching { provider.detail(handoff.key) }
                    .onSuccess { detail ->
                        ensureActive()
                        update {
                            copy(
                                selectedUnifiedDetail = detail.copy(
                                    item = detail.item.copy(progressMs = handoff.positionMs)
                                ),
                                isLoading = false,
                            )
                        }
                    }
                    .onFailure { ensureActive(); showError(it) }
                }
            }
        }
    }

    fun updateServerUrl(value: String) = update { copy(serverUrl = value, errorMessage = null) }
    fun updateUserName(value: String) = update { copy(userName = value, errorMessage = null) }
    fun updatePassword(value: String) = update { copy(password = value, errorMessage = null) }
    fun updateAllowInsecure(value: Boolean) = update { copy(allowInsecureHttp = value, errorMessage = null) }

    fun showServers() {
        cancelContentRequests()
        playbackRequest?.cancel()
        deleteRequest?.cancel()
        val saved = sessionStore.loadAll()
        mutableState.value = MainUiState(
            screen = if (saved.isEmpty()) Screen.LOGIN else Screen.SERVERS,
            savedSessions = saved,
        )
    }

    fun addServer() {
        cancelContentRequests()
        mutableState.value = MainUiState(screen = Screen.LOGIN, savedSessions = sessionStore.loadAll())
    }

    fun selectServer(session: EmbySession) {
        if (!sessionStore.select(session)) {
            update { copy(errorMessage = "这个服务器登录已不存在，请重新添加") }
            return
        }
        restoreSession(session, sessionStore.loadAll())
    }

    fun requestRemoveServer(session: EmbySession) =
        update { copy(pendingRemoveSession = session, errorMessage = null) }

    fun cancelRemoveServer() = update { copy(pendingRemoveSession = null) }

    fun confirmRemoveServer() {
        val session = state.value.pendingRemoveSession ?: return
        cancelContentRequests()
        sessionStore.remove(session)
        val remaining = sessionStore.loadAll()
        mutableState.value = MainUiState(
            screen = if (remaining.isEmpty()) Screen.LOGIN else Screen.SERVERS,
            savedSessions = remaining,
        )
        syncProviders()
        viewModelScope.launch {
            playbackOutbox.discard(session)
            runCatching { repository.logout(session) }
        }
    }

    fun login() {
        val snapshot = state.value
        if (snapshot.isLoading) return
        contentRequest?.cancel()
        contentRequest = viewModelScope.launch {
            update { copy(isLoading = true, errorMessage = null) }
            runCatching {
                repository.login(
                    LoginRequest(
                        serverUrl = snapshot.serverUrl,
                        userName = snapshot.userName,
                        password = snapshot.password,
                        allowInsecureHttp = snapshot.allowInsecureHttp,
                    )
                )
            }.onSuccess { session ->
                ensureActive()
                sessionStore.save(session)
                mutableState.value = MainUiState(
                    session = session,
                    savedSessions = sessionStore.loadAll(),
                    serverUrl = session.serverUrl,
                    userName = session.userName,
                    screen = Screen.HOME,
                    externalSources = externalSourceStore.loadAll(),
                    isLoading = true,
                )
                syncProviders()
                viewModelScope.launch {
                    try { playbackOutbox.flush(session) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (state.value.session == session) showError(e) }
                }
                viewModelScope.launch { loadLibraries(session) }
            }.onFailure { ensureActive(); showError(it) }
        }
    }

    fun showHome() = navigate {
        copy(
            screen = Screen.HOME,
            selectedLibrary = null,
            selectedSeries = null,
            detailEpisodes = emptyList(),
            items = emptyList(),
            wallItems = emptyList(),
            feedMode = false,
            playbackPlan = null,
            selectedItem = null,
            pendingPublication = null,
            errorMessage = null,
        )
    }

    fun showLibraries() = navigate { copy(screen = Screen.LIBRARIES, errorMessage = null) }

    fun showSources() = navigate {
        copy(
            screen = Screen.SOURCES,
            externalSources = externalSourceStore.loadAll(),
            sourceUrl = "",
            errorMessage = null,
        )
    }

    fun showSettings() = navigate { copy(screen = Screen.SETTINGS, errorMessage = null) }

    fun showNetworkStorages() {
        cancelContentRequests()
        val returnScreen = state.value.screen
        update {
            copy(
                screen = Screen.NETWORK_STORAGES,
                networkStorages = networkStorageStore.loadAll(),
                networkStorageMessage = null,
                errorMessage = null,
                networkStorageReturnScreen = returnScreen,
            )
        }
        refreshNetworkStorages()
    }

    fun updateNetworkStorageKind(value: NetworkStorageKind) = update { copy(networkStorageKind = value) }
    fun updateNetworkStorageName(value: String) = update { copy(networkStorageName = value, networkStorageMessage = null) }
    fun updateNetworkStorageAddress(value: String) = update { copy(networkStorageAddress = value, networkStorageMessage = null) }
    fun updateNetworkStorageUsername(value: String) = update { copy(networkStorageUsername = value, networkStorageMessage = null) }
    fun updateNetworkStoragePassword(value: String) = update { copy(networkStoragePassword = value, networkStorageMessage = null) }
    fun updateNetworkStorageDomain(value: String) = update { copy(networkStorageDomain = value, networkStorageMessage = null) }
    fun updateNetworkStorageShare(value: String) = update { copy(networkStorageShare = value, networkStorageMessage = null) }
    fun updateNetworkStorageRootPath(value: String) = update { copy(networkStorageRootPath = value, networkStorageMessage = null) }
    fun updateNetworkStorageAllowInsecure(value: Boolean) = update { copy(networkStorageAllowInsecureHttp = value) }
    fun updateNetworkStorageReadNfo(value: Boolean) = update { copy(networkStorageReadNfo = value) }
    fun updateNetworkStorageResolveStrm(value: Boolean) = update { copy(networkStorageResolveStrm = value) }

    fun saveNetworkStorage() {
        val snapshot = state.value
        if (snapshot.isSavingNetworkStorage) return
        val connection = NetworkStorageConnection(
            id = UUID.randomUUID().toString(),
            name = snapshot.networkStorageName.trim(),
            kind = snapshot.networkStorageKind,
            address = snapshot.networkStorageAddress.trim(),
            username = snapshot.networkStorageUsername.trim(),
            password = snapshot.networkStoragePassword,
            domain = snapshot.networkStorageDomain.trim(),
            share = snapshot.networkStorageShare.trim(),
            rootPath = snapshot.networkStorageRootPath.trim().ifBlank { "/" },
            allowInsecureHttp = snapshot.networkStorageAllowInsecureHttp,
            readNfo = snapshot.networkStorageReadNfo,
            resolveStrm = snapshot.networkStorageResolveStrm,
        )
        viewModelScope.launch {
            runCatching {
                require(connection.name.isNotBlank()) { "请填写媒体库名称" }
                require(connection.address.isNotBlank()) { "请填写服务地址或 SMB 主机" }
                if (connection.kind == NetworkStorageKind.SMB) require(connection.share.isNotBlank()) { "请填写 SMB 共享名" }
                update { copy(isSavingNetworkStorage = true, networkStorageMessage = "正在验证连接") }
                val status = networkStorageRepository.probe(connection)
                if (status.health != NetworkStorageHealth.ONLINE) throw IllegalArgumentException(status.message)
                networkStorageStore.save(connection)
                syncProviders()
                status
            }.onSuccess { status ->
                update {
                    copy(
                        networkStorages = networkStorageStore.loadAll(),
                        networkStorageStatuses = networkStorageStatuses + (connection.id to status),
                        networkStorageName = "",
                        networkStorageAddress = "",
                        networkStorageUsername = "",
                        networkStoragePassword = "",
                        networkStorageDomain = "",
                        networkStorageShare = "",
                        networkStorageRootPath = "/",
                        isSavingNetworkStorage = false,
                        networkStorageMessage = "已添加 ${connection.name}",
                    )
                }
            }.onFailure { error ->
                update { copy(isSavingNetworkStorage = false, networkStorageMessage = "连接失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun removeNetworkStorage(connectionId: String) {
        networkStorageStore.remove(connectionId)
        networkStorageRepository.forget(connectionId)
        syncProviders()
        update {
            copy(
                networkStorages = networkStorageStore.loadAll(),
                networkStorageStatuses = networkStorageStatuses - connectionId,
                networkStorageMessage = "已移除网络媒体库",
            )
        }
    }

    fun refreshNetworkStorages() {
        storageProbeRequest?.cancel()
        val connections = networkStorageStore.loadAll()
        storageProbeRequest = viewModelScope.launch {
            supervisorScope {
                connections.forEach { connection -> launch {
                    try {
                        if (connection !in networkStorageStore.loadAll()) return@launch
                        val status = networkStorageRepository.probe(connection)
                        ensureActive()
                        if (connection in networkStorageStore.loadAll()) {
                            update { copy(networkStorageStatuses = networkStorageStatuses + (connection.id to status)) }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { update { copy(networkStorageMessage = e.message ?: "连接检查失败") } }
                } }
            }
        }
    }

    fun openNetworkStorage(connection: NetworkStorageConnection) {
        cancelContentRequests()
        syncProviders()
        val providerId = "storage:${connection.id}"
        val provider = providerRegistry.provider(providerId) ?: return
        contentRequest = viewModelScope.launch {
            update { copy(screen = Screen.PROVIDER_DETAIL, selectedUnifiedDetail = null, providerDetailBackStack = emptyList(), providerDetailReturnScreen = Screen.NETWORK_STORAGES, isLoading = true, errorMessage = null) }
            runCatching { provider.detail(MediaKey(providerId, connection.rootPath.normalizedStoragePath())) }
                .onSuccess { detail -> ensureActive(); update { copy(selectedUnifiedDetail = detail, isLoading = false) } }
                .onFailure { ensureActive(); showError(it) }
        }
    }

    fun showIntegrations() {
        cancelContentRequests()
        val connections = integrationStore.loadAll()
        update { copy(screen = Screen.INTEGRATIONS, integrations = connections, integrationMessage = null, errorMessage = null) }
        refreshIntegrations()
    }

    fun showInsights() = navigate { copy(screen = Screen.INSIGHTS, insightMessage = null, errorMessage = null) }

    fun clearInsightMessage() = update { copy(insightMessage = null) }

    fun saveMoment(item: MediaItem, positionMs: Long) {
        val session = state.value.session ?: return
        val providerId = "emby:${session.serverId}:${session.userId}"
        viewModelScope.launch {
            localMediaState.addMoment(
                MediaMomentEntity(
                    id = UUID.randomUUID().toString(),
                    providerId = providerId,
                    itemId = item.id,
                    mediaTitle = item.name,
                    positionMs = positionMs.coerceAtLeast(0),
                    note = item.seriesName.orEmpty(),
                    createdAtEpochMs = System.currentTimeMillis(),
                )
            )
            update { copy(insightMessage = "已保存 ${formatPosition(positionMs)} 的媒体时刻") }
        }
    }

    fun saveSegment(item: MediaItem, type: SegmentType, startMs: Long, endMs: Long) {
        val session = state.value.session ?: return
        if (endMs <= startMs) return
        val segment = MediaSegment(
            id = UUID.randomUUID().toString(),
            providerId = "emby:${session.serverId}:${session.userId}",
            itemId = item.id,
            type = type,
            startMs = startMs.coerceAtLeast(0),
            endMs = endMs.coerceAtLeast(0),
            confidence = 1f,
            source = SegmentSource.USER,
        )
        viewModelScope.launch {
            localMediaState.addSegment(segment)
            update {
                copy(
                    mediaSegments = (mediaSegments + segment).sortedBy(MediaSegment::startMs),
                    insightMessage = "已保存${type.displayName()} ${formatPosition(startMs)}—${formatPosition(endMs)}",
                )
            }
        }
    }

    fun removeMoment(id: String) {
        viewModelScope.launch { localMediaState.removeMoment(id) }
    }

    fun removeSegment(id: String) {
        viewModelScope.launch {
            localMediaState.removeSegment(id)
            update { copy(mediaSegments = mediaSegments.filterNot { it.id == id }) }
        }
    }

    fun playMoment(moment: MediaMomentEntity) {
        val session = state.value.session ?: return
        val expectedProvider = "emby:${session.serverId}:${session.userId}"
        if (moment.providerId != expectedProvider) {
            update { copy(insightMessage = "这个时刻属于另一台 Emby 服务器，请先切换登录") }
            return
        }
        val item = MediaItem(
            id = moment.itemId,
            name = moment.mediaTitle,
            type = "Video",
            seriesName = moment.note.takeIf(String::isNotBlank),
            seasonNumber = null,
            episodeNumber = null,
            runTimeTicks = null,
            playbackPositionTicks = moment.positionMs * 10_000,
            played = false,
            favorite = false,
        )
        update { copy(items = listOf(item), playerReturnScreen = Screen.INSIGHTS, feedMode = false) }
        resolvePlayback(session, item, 0, moment.positionMs, 0)
    }

    fun updateIntegrationKind(value: IntegrationKind) = update { copy(integrationKind = value) }
    fun updateIntegrationName(value: String) = update { copy(integrationName = value, integrationMessage = null) }
    fun updateIntegrationBaseUrl(value: String) = update { copy(integrationBaseUrl = value, integrationMessage = null) }
    fun updateIntegrationApiToken(value: String) = update { copy(integrationApiToken = value, integrationMessage = null) }
    fun updateIntegrationAllowInsecure(value: Boolean) = update { copy(integrationAllowInsecureHttp = value) }
    fun updateIntegrationPlaylistUrl(value: String) = update { copy(integrationPlaylistUrl = value) }
    fun updateIntegrationEpgUrl(value: String) = update { copy(integrationEpgUrl = value) }

    fun saveIntegration() {
        val snapshot = state.value
        if (snapshot.integrationName.isBlank() || snapshot.integrationBaseUrl.isBlank() || snapshot.isSavingIntegration) return
        val connection = IntegrationConnection(
            id = UUID.randomUUID().toString(),
            name = snapshot.integrationName.trim(),
            kind = snapshot.integrationKind,
            baseUrl = snapshot.integrationBaseUrl.trim(),
            apiToken = snapshot.integrationApiToken.trim(),
            allowInsecureHttp = snapshot.integrationAllowInsecureHttp,
            playlistUrl = snapshot.integrationPlaylistUrl.trim().takeIf(String::isNotEmpty),
            epgUrl = snapshot.integrationEpgUrl.trim().takeIf(String::isNotEmpty),
        )
        viewModelScope.launch {
            update { copy(isSavingIntegration = true, integrationMessage = null) }
            runCatching { integrationRepository.probe(connection) }
                .onSuccess { status ->
                    integrationStore.save(connection)
                    update {
                        copy(
                            integrations = integrationStore.loadAll(),
                            integrationStatuses = integrationStatuses + (connection.id to status),
                            integrationName = "",
                            integrationBaseUrl = "",
                            integrationApiToken = "",
                            integrationPlaylistUrl = "",
                            integrationEpgUrl = "",
                            isSavingIntegration = false,
                            integrationMessage = "连接已加密保存 · ${status.message}",
                        )
                    }
                }
                .onFailure {
                    update { copy(isSavingIntegration = false, integrationMessage = it.message ?: "连接配置无效") }
                }
        }
    }

    fun removeIntegration(connectionId: String) {
        integrationStore.remove(connectionId)
        update {
            copy(
                integrations = integrationStore.loadAll(),
                integrationStatuses = integrationStatuses - connectionId,
                integrationMessage = "连接已移除",
            )
        }
    }

    fun refreshIntegrations() {
        integrationProbeRequest?.cancel()
        val connections = integrationStore.loadAll()
        if (connections.isEmpty()) return
        integrationProbeRequest = viewModelScope.launch {
            try {
                val statuses = supervisorScope {
                    connections.map { connection -> async { connection.id to integrationRepository.probe(connection) } }.map { it.await() }.toMap()
                }
                ensureActive()
                val current = integrationStore.loadAll()
                val currentIds = current.map { it.id }.toSet()
                val validIds = current.filter { it in connections }.map { it.id }.toSet()
                update { copy(
                    integrations = current,
                    integrationStatuses = (integrationStatuses + statuses.filterKeys { it in validIds }).filterKeys { it in currentIds },
                ) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { copy(integrationMessage = e.message ?: "连接检查失败") } }
        }
    }

    fun importVirtualChannels(connection: IntegrationConnection) {
        val urls = integrationRepository.virtualChannelUrls(connection)
        if (urls == null) {
            update { copy(integrationMessage = "请先为这个服务填写 M3U 输出地址") }
            return
        }
        viewModelScope.launch {
            update { copy(isSavingIntegration = true, integrationMessage = "正在导入虚拟频道") }
            runCatching {
                val imported = externalSourceRepository.importFromUrl(urls.first, connection.allowInsecureHttp)
                val entries = externalSourceRepository.entries(imported).map { entry ->
                    if (entry.epgUrl == null) entry.copy(epgUrl = urls.second) else entry
                }
                imported.copy(
                    summary = imported.summary.copy(name = connection.name, allowInsecureHttp = connection.allowInsecureHttp),
                    resolvedEntries = entries,
                )
            }.onSuccess { imported ->
                externalSourceStore.save(imported)
                syncProviders()
                update {
                    copy(
                        externalSources = externalSourceStore.loadAll(),
                        isSavingIntegration = false,
                        integrationMessage = "虚拟频道已加入直播中心",
                    )
                }
            }.onFailure {
                update { copy(isSavingIntegration = false, integrationMessage = it.message ?: "频道导入失败") }
            }
        }
    }

    fun requestSelectedMedia() {
        val item = state.value.selectedUnifiedDetail?.item ?: return
        val tmdbId = item.externalIds.entries.firstOrNull { it.key.equals("Tmdb", true) }
            ?.value?.toIntOrNull()
        val seerr = integrationStore.loadAll().firstOrNull { it.kind == IntegrationKind.SEERR }
        if (tmdbId == null || seerr == null) {
            update { copy(integrationMessage = "需要 TMDB ID 和已配置的 Seerr 连接") }
            return
        }
        viewModelScope.launch {
            val mediaType = if (item.type.equals("Movie", true)) "movie" else "tv"
            runCatching { integrationRepository.requestMedia(seerr, tmdbId, mediaType) }
                .onSuccess { message -> update { copy(integrationMessage = message) } }
                .onFailure { update { copy(integrationMessage = it.message ?: "提交想看失败") } }
        }
    }

    fun showDiscover() {
        cancelContentRequests()
        syncProviders()
        update { copy(screen = Screen.DISCOVER, errorMessage = null) }
    }

    fun updateDiscoverQuery(value: String) {
        providerSearchRequest?.cancel(); searchPageJobs.values.forEach { it.cancel() }; searchPageJobs.clear(); searchGeneration++
        update { copy(discoverQuery = value, errorMessage = null, isSearchingProviders = false, providerSearchNext = emptyMap(), loadingSearchPages = emptySet(), providerSearchFailures = emptyList(), discoverResults = emptyList()) }
    }

    fun searchProviders() {
        val query = state.value.discoverQuery.trim()
        if (query.isEmpty()) return
        providerSearchRequest?.cancel()
        searchPageJobs.values.forEach { it.cancel() }; searchPageJobs.clear()
        val generation = ++searchGeneration
        submittedQuery = query
        providerSearchRequest = viewModelScope.launch {
            update {
                copy(
                    isSearchingProviders = true,
                    discoverResults = emptyList(),
                    providerSearchFailures = emptyList(),
                    providerSearchNext = emptyMap(), loadingSearchPages = emptySet(),
                    errorMessage = null,
                )
            }
            localMediaState.addSearch(query)
            try {
                aggregateSearchEngine.searchSnapshots(
                    providerRegistry.providers.first(),
                    ProviderSearchRequest(query = query, pageSize = 60),
                ).collect { result ->
                    if (generation == searchGeneration) update { copy(discoverResults = result.items, providerSearchFailures = result.failures, providerSearchNext = result.nextPageTokens, isSearchingProviders = result.pendingProviderIds.isNotEmpty()) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                update { copy(isSearchingProviders = false) }
                showError(e)
            }
        }
    }

    fun loadSearchPage(providerId: String, retry: Boolean = false) {
        if (state.value.isSearchingProviders || providerId in state.value.loadingSearchPages || submittedQuery.isBlank()) return
        val provider = providerRegistry.provider(providerId) ?: return
        val token = if (retry) null else state.value.providerSearchNext[providerId] ?: return
        val generation = searchGeneration; val query = submittedQuery
        update { copy(loadingSearchPages = loadingSearchPages + providerId) }
        searchPageJobs[providerId] = viewModelScope.launch {
            try {
                val page = kotlinx.coroutines.withTimeout(12_000) { provider.search(ProviderSearchRequest(query, pageToken = token)) }
                if (generation == searchGeneration) update { copy(
                    discoverResults = (discoverResults + page.items).distinctBy { it.key },
                    providerSearchNext = (providerSearchNext - providerId) + (page.nextPageToken?.takeUnless { it == token }?.let { mapOf(providerId to it) } ?: emptyMap()),
                    providerSearchFailures = providerSearchFailures.filterNot { it.providerId == providerId }) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                if (generation == searchGeneration) update { copy(errorMessage = "${provider.descriptor.name} 加载超时，可重试") }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { if (generation == searchGeneration) update { copy(errorMessage = "${provider.descriptor.name} 加载失败，可重试") } }
            finally { if (generation == searchGeneration) update { copy(loadingSearchPages = loadingSearchPages - providerId) } }
        }
    }

    fun openUnifiedItem(item: UnifiedMediaItem) {
        val provider = providerRegistry.provider(item.key.providerId) ?: return
        cancelContentRequests()
        val currentScreen = state.value.screen
        val currentDetail = state.value.selectedUnifiedDetail
        contentRequest = viewModelScope.launch {
            update {
                copy(
                    screen = Screen.PROVIDER_DETAIL,
                    selectedUnifiedDetail = null,
                    providerDetailBackStack = if (currentScreen == Screen.PROVIDER_DETAIL && currentDetail != null) {
                        providerDetailBackStack + currentDetail
                    } else emptyList(),
                    providerDetailReturnScreen = if (currentScreen == Screen.PROVIDER_DETAIL) providerDetailReturnScreen else currentScreen,
                    isLoading = true,
                    errorMessage = null,
                )
            }
            runCatching { provider.detail(item.key) }
                .onSuccess { detail -> ensureActive(); update { copy(selectedUnifiedDetail = detail, isLoading = false) } }
                .onFailure { ensureActive(); showError(it) }
        }
    }

    fun playUnifiedItem(item: UnifiedMediaItem) {
        if (top.cylunex.shadowmedia.model.contentKind(item.type) in setOf(top.cylunex.shadowmedia.model.ContentKind.FOLDER, top.cylunex.shadowmedia.model.ContentKind.SERIES)) {
            openUnifiedItem(item); return
        }
        cancelContentRequests()
        contentRequest = viewModelScope.launch {
            if (!tryOfflineVideo(item.key, item.progressMs)) playUnifiedOnline(item)
        }
    }
    private fun playUnifiedOnline(item: UnifiedMediaItem) {
        cancelContentRequests()
        if (top.cylunex.shadowmedia.model.contentKind(item.type) in setOf(
                top.cylunex.shadowmedia.model.ContentKind.BOOK, top.cylunex.shadowmedia.model.ContentKind.COMIC, top.cylunex.shadowmedia.model.ContentKind.AUDIOBOOK, top.cylunex.shadowmedia.model.ContentKind.MUSIC, top.cylunex.shadowmedia.model.ContentKind.PODCAST)) {
            update { copy(pendingPublication = item) }
            return
        }
        val provider = providerRegistry.provider(item.key.providerId) ?: return
        if (provider is EmbyMediaProvider) {
            if (item.type.equals("MusicAlbum", true) || item.type.equals("Series", true) || item.type.equals("BoxSet", true)) {
                openUnifiedItem(item)
                return
            }
            playbackRequest?.cancel()
            deleteRequest?.cancel()
            if (!sessionStore.select(provider.session)) {
                showError(IllegalStateException("此 Emby 账号已移除，请重新连接"))
                return
            }
            val embyItem = provider.mediaItem(item.key) ?: item.toEmbyMediaItem()
            update {
                copy(
                    session = provider.session,
                    serverUrl = provider.session.serverUrl,
                    userName = provider.session.userName,
                    allowInsecureHttp = provider.session.allowInsecureHttp,
                    libraries = if (session == provider.session) libraries else emptyList(),
                    homeSections = if (session == provider.session) homeSections else emptyList(),
                    selectedLibrary = if (session == provider.session) selectedLibrary else null,
                    items = listOf(embyItem),
                    playerReturnScreen = screen,
                    feedMode = false,
                )
            }
            playAt(0)
            return
        }
        contentRequest = viewModelScope.launch {
            update { copy(isLoading = true, errorMessage = null) }
            runCatching { provider.resolve(UnifiedPlaybackRequest(item.key, item.progressMs)) }
                .onSuccess { candidates ->
                    ensureActive()
                    val candidate = candidates.firstOrNull()
                    if (candidate == null) {
                        update { copy(isLoading = false, errorMessage = "这个 Provider 没有返回可播放线路") }
                    } else {
                        update {
                            copy(
                                screen = Screen.EXTERNAL_PLAYER,
                                externalPlayerReturnScreen = screen,
                                selectedExternalEntry = ExternalMediaEntry(
                                    id = item.key.itemId,
                                    sourceId = item.key.providerId,
                                    title = item.title,
                                    url = candidate.url,
                                    group = provider.descriptor.name,
                                    logoUrl = item.posterUrl,
                                    requestHeaders = candidate.requiredHeaders,
                                    credentialOrigin = candidate.credentialOrigin,
                                    startPositionMs = item.progressMs,
                                    isDiscImage = candidate.isDiscImage || item.key.itemId.substringBefore('|')
                                        .substringBefore('?').substringAfterLast('.', "")
                                        .equals("iso", ignoreCase = true) ||
                                        candidate.url.substringBefore('|').substringBefore('?')
                                            .substringAfterLast('.', "").equals("iso", ignoreCase = true),
                                ),
                                isLoading = false,
                            )
                        }
                    }
                }
                .onFailure { ensureActive(); showError(it) }
        }
    }

    fun setFeatureEnabled(feature: FeatureId, enabled: Boolean) {
        viewModelScope.launch { localMediaState.setFeatureEnabled(feature, enabled) }
    }

    fun clearPendingPublication() = update { copy(pendingPublication = null) }

    fun selectLibrary(library: MediaLibrary) {
        cancelContentRequests()
        update {
            copy(
                selectedLibrary = library,
                screen = Screen.ITEMS,
                wallItems = emptyList(),
                wallSearch = "",
                wallSort = MediaSort.DATE_ADDED,
                wallFilter = MediaFilter.ALL,
                wallTotalCount = 0,
                wallHasMore = false,
                feedMode = false,
                errorMessage = null,
            )
        }
        loadWall(reset = true)
    }

    fun resumeLastFeed() {
        val library = state.value.libraries.firstOrNull { it.id == state.value.lastFeedLibraryId } ?: return
        loadFeed(library, autoPlay = true)
    }

    fun startLibraryFeed() {
        val library = state.value.selectedLibrary ?: return
        loadFeed(library, autoPlay = true)
    }

    private fun loadFeed(library: MediaLibrary, autoPlay: Boolean) {
        val session = state.value.session ?: return
        cancelContentRequests()
        contentRequest = viewModelScope.launch {
            update {
                copy(
                    selectedLibrary = library,
                    items = emptyList(),
                    screen = Screen.ITEMS,
                    feedMode = true,
                    isLoading = true,
                    errorMessage = null,
                )
            }
            runCatching { repository.recentVideos(session, library.id) }
                .onSuccess { loadedItems ->
                    ensureActive()
                    if (state.value.session != session) return@onSuccess
                    val feed = feedSessionStore.reconcile(session, library.id, loadedItems)
                    val byId = loadedItems.associateBy(MediaItem::id)
                    val orderedItems = feed.orderedItemIds.mapNotNull(byId::get)
                    update {
                        copy(
                            items = orderedItems,
                            currentIndex = feed.currentIndex,
                            lastFeedLibraryId = library.id,
                            isLoading = false,
                        )
                    }
                    if (autoPlay && orderedItems.isNotEmpty()) playAt(feed.currentIndex)
                }
                .onFailure { ensureActive(); showError(it) }
        }
    }

    fun updateWallSearch(value: String) = update { copy(wallSearch = value, errorMessage = null) }

    fun submitWallSearch() = loadWall(reset = true)

    fun setWallSort(sort: MediaSort) {
        update { copy(wallSort = sort) }
        loadWall(reset = true)
    }

    fun setWallFilter(filter: MediaFilter) {
        update { copy(wallFilter = filter) }
        loadWall(reset = true)
    }

    fun loadMoreWall() {
        if (!state.value.wallHasMore || state.value.isLoading || state.value.isLoadingMore) return
        loadWall(reset = false)
    }

    private fun loadWall(reset: Boolean) {
        val snapshot = state.value
        val session = snapshot.session ?: return
        val library = snapshot.selectedLibrary ?: return
        if (snapshot.screen != Screen.ITEMS) return
        contentRequest?.cancel()
        if (reset) wallPagination.reset(BrowseRequest(
            parentId = library.id, includeItemTypes = library.wallItemTypes(), searchTerm = snapshot.wallSearch,
            sort = snapshot.wallSort, descending = snapshot.wallSort != MediaSort.NAME,
            filter = snapshot.wallFilter, limit = WALL_PAGE_SIZE,
        ))
        val request = wallPagination.request() ?: return
        contentRequest = viewModelScope.launch {
            update {
                if (reset) copy(isLoading = true, isLoadingMore = false, wallHasMore = false, wallItems = emptyList(), errorMessage = null)
                else copy(isLoadingMore = true, errorMessage = null)
            }
            runCatching {
                repository.browse(session, request)
            }.onSuccess { page ->
                ensureActive()
                if (state.value.session != session) return@onSuccess
                wallPagination.advance(page)
                update {
                    copy(
                        wallItems = if (reset) page.items else (wallItems + page.items).distinctBy(MediaItem::id),
                        wallTotalCount = page.totalRecordCount,
                        wallHasMore = page.items.isNotEmpty() && page.hasMore && request.sort != MediaSort.RANDOM,
                        isLoading = false,
                        isLoadingMore = false,
                    )
                }
            }.onFailure {
                ensureActive()
                update { copy(isLoading = false, isLoadingMore = false) }
                showError(it)
            }
        }
    }

    fun openCatalogItem(item: MediaItem) {
        if (item.type.equals("Series", ignoreCase = true) || item.type.equals("BoxSet", ignoreCase = true)) {
            loadDetail(item)
        } else {
            val playable = state.value.wallItems.filterNot {
                it.type.equals("Series", true) || it.type.equals("BoxSet", true)
            }
            val index = playable.indexOfFirst { it.id == item.id }
            if (index >= 0) {
                update { copy(items = playable, playerReturnScreen = Screen.ITEMS, feedMode = false) }
                playAt(index)
            }
        }
    }

    fun playHomeSection(section: MediaSection, item: MediaItem) {
        val playable = section.items.filterNot { it.type.equals("Series", true) }
        val index = playable.indexOfFirst { it.id == item.id }
        if (index < 0) {
            openCatalogItem(item)
            return
        }
        update { copy(items = playable, playerReturnScreen = Screen.HOME, feedMode = false) }
        playAt(index)
    }

    private fun loadDetail(item: MediaItem) {
        val session = state.value.session ?: return
        cancelContentRequests()
        contentRequest = viewModelScope.launch {
            update {
                copy(
                    screen = Screen.DETAIL,
                    selectedSeries = item,
                    detailEpisodes = emptyList(),
                    isLoading = true,
                    errorMessage = null,
                )
            }
            runCatching { repository.children(session, item.id) }
                .onSuccess { episodes -> ensureActive(); if (state.value.session == session) update { copy(detailEpisodes = episodes, isLoading = false) } }
                .onFailure { ensureActive(); showError(it) }
        }
    }

    fun playEpisode(item: MediaItem) {
        val episodes = state.value.detailEpisodes
        val index = episodes.indexOfFirst { it.id == item.id }
        if (index < 0) return
        update { copy(items = episodes, playerReturnScreen = Screen.DETAIL, feedMode = false) }
        playAt(index)
    }

    fun play(item: MediaItem, returnScreen: Screen = state.value.screen) {
        val index = state.value.items.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            update { copy(playerReturnScreen = returnScreen) }
            playAt(index)
        }
    }

    fun playAt(index: Int) {
        val snapshot = state.value
        val session = snapshot.session ?: return
        val item = snapshot.items.getOrNull(index) ?: return
        if (
            snapshot.screen == Screen.PLAYER && snapshot.currentIndex == index &&
            (snapshot.playbackPlan?.itemId == item.id || snapshot.isLoading)
        ) return
        if (snapshot.feedMode) {
            snapshot.selectedLibrary?.let { feedSessionStore.markCurrent(session, it.id, item.id, index) }
        }
        resolvePlayback(
            session,
            item,
            index,
            item.playbackPositionTicks.embyTicksToMilliseconds(),
            refreshAttempts = 0,
        )
    }

    fun retryPlayback() {
        val snapshot = state.value
        val session = snapshot.session ?: return
        val item = snapshot.items.getOrNull(snapshot.currentIndex) ?: return
        resolvePlayback(session, item, snapshot.currentIndex, snapshot.playbackStartPositionMs, 0)
    }

    fun recoverPlayback(positionMs: Long, message: String) {
        val snapshot = state.value
        if (snapshot.playbackRefreshAttempts >= MAX_PLAYBACK_REFRESHES) {
            update {
                copy(
                    playbackStartPositionMs = positionMs,
                    isLoading = false,
                    errorMessage = "刷新 PlaybackInfo 后仍无法播放：$message",
                )
            }
            return
        }
        val session = snapshot.session ?: return
        val item = snapshot.selectedItem ?: return
        resolvePlayback(
            session,
            item,
            snapshot.currentIndex,
            positionMs,
            snapshot.playbackRefreshAttempts + 1,
        )
    }

    fun externalPlaybackStarted(plan: PlaybackPlan, positionMs: Long) {
        reportExternalPlayback(plan, positionMs, PlaybackEvent.STARTED)
    }

    fun externalPlaybackStopped(plan: PlaybackPlan, positionMs: Long) {
        val positionTicks = positionMs.coerceAtLeast(0L).millisecondsToEmbyTicks()
        if (state.value.playbackPlan == plan) update {
            copy(
                playbackStartPositionMs = positionMs.coerceAtLeast(0L),
                items = items.map { item ->
                    if (item.id == plan.itemId) item.copy(playbackPositionTicks = positionTicks) else item
                },
            )
        }
        reportExternalPlayback(plan, positionMs, PlaybackEvent.STOPPED)
    }

    private fun reportExternalPlayback(plan: PlaybackPlan, positionMs: Long, event: PlaybackEvent) {
        val session = playbackOwnership.session(plan, event) ?: return
        if (sessionStore.loadAll().none { it == session }) return
        viewModelScope.launch {
          try {
            playbackOutbox.submit(
                session,
                PlaybackReport(
                    itemId = plan.itemId,
                    mediaSourceId = plan.primary.mediaSourceId ?: plan.mediaSourceId,
                    playSessionId = plan.playSessionId,
                    positionTicks = positionMs.coerceAtLeast(0L).millisecondsToEmbyTicks(),
                    isPaused = event == PlaybackEvent.STOPPED,
                    canSeek = true,
                    event = event,
                    playMethod = plan.primary.method,
                ),
            )
          } catch (e: CancellationException) { throw e }
          catch (e: Exception) { if (state.value.session == session) showError(e) }
        }
    }

    private fun resolvePlayback(
        session: EmbySession,
        item: MediaItem,
        index: Int,
        startPositionMs: Long,
        refreshAttempts: Int,
    ) {
        contentRequest?.cancel()
        playbackRequest?.cancel()
        update {
            copy(
                screen = Screen.PLAYER,
                selectedItem = item,
                currentIndex = index,
                playbackPlan = null,
                playbackStartPositionMs = startPositionMs,
                playbackRefreshAttempts = refreshAttempts,
                mediaSegments = emptyList(),
                isLoading = true,
                errorMessage = if (refreshAttempts > 0) "播放地址失效，正在重新解析…" else null,
            )
        }
        viewModelScope.launch {
            val providerId = "emby:${session.serverId}:${session.userId}"
            val segments = localMediaState.segments(providerId, item.id).first()
            if (state.value.session == session && state.value.screen == Screen.PLAYER && state.value.selectedItem?.id == item.id) update { copy(mediaSegments = segments) }
        }
        playbackRequest = viewModelScope.launch {
            try {
                if (tryOfflineVideo(MediaKey("emby:${session.serverId}:${session.userId}", item.id), startPositionMs)) return@launch
                val plan = repository.playbackPlan(session, item.id)
                ensureActive()
                if (state.value.session == session && state.value.screen == Screen.PLAYER && state.value.selectedItem?.id == item.id) {
                    playbackOwnership.remember(plan, session)
                    update { copy(playbackPlan = plan, isLoading = false, errorMessage = null) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (state.value.session == session && state.value.screen == Screen.PLAYER && state.value.selectedItem?.id == item.id) showError(error)
            }
        }
    }

    fun requestDelete(item: MediaItem) = update { copy(pendingDeleteItem = item, errorMessage = null) }

    fun toggleFavorite(item: MediaItem) {
        val session = state.value.session ?: return
        val target = !item.favorite
        viewModelScope.launch {
            runCatching { repository.setFavorite(session, item.id, target) }
                .onSuccess {
                    if (state.value.session != session) return@onSuccess
                    update {
                        copy(
                            items = items.map { if (it.id == item.id) it.copy(favorite = target) else it },
                            wallItems = wallItems.map {
                                if (it.id == item.id) it.copy(favorite = target) else it
                            },
                            detailEpisodes = detailEpisodes.map {
                                if (it.id == item.id) it.copy(favorite = target) else it
                            },
                            selectedSeries = selectedSeries?.let {
                                if (it.id == item.id) it.copy(favorite = target) else it
                            },
                            homeSections = homeSections.map { section ->
                                section.copy(
                                    items = section.items.map {
                                        if (it.id == item.id) it.copy(favorite = target) else it
                                    }.let { updated ->
                                        if (section.kind == MediaSectionKind.FAVORITES && !target) {
                                            updated.filterNot { it.id == item.id }
                                        } else updated
                                    }
                                )
                            }.filter { it.items.isNotEmpty() },
                        )
                    }
                }
                .onFailure { if (it is CancellationException) throw it; if (state.value.session == session) showError(it) }
        }
    }

    fun updateSourceUrl(value: String) = update { copy(sourceUrl = value, errorMessage = null) }

    fun updateSourceAllowInsecure(value: Boolean) =
        update { copy(sourceAllowInsecureHttp = value, errorMessage = null) }

    fun addExternalSource() {
        val snapshot = state.value
        if (snapshot.isInspectingSource || snapshot.sourceUrl.isBlank()) return
        viewModelScope.launch {
            update { copy(isInspectingSource = true, errorMessage = null) }
            runCatching {
                externalSourceRepository.importFromUrl(snapshot.sourceUrl, snapshot.sourceAllowInsecureHttp)
            }.onSuccess { imported ->
                externalSourceStore.save(imported)
                syncProviders()
                update {
                    copy(
                        externalSources = externalSourceStore.loadAll(),
                        sourceUrl = "",
                        isInspectingSource = false,
                        errorMessage = null,
                    )
                }
            }.onFailure {
                update { copy(isInspectingSource = false) }
                showError(it)
            }
        }
    }

    fun importLocalExternalSource(sourceUri: String, displayName: String, payload: String) {
        if (state.value.isInspectingSource) return
        viewModelScope.launch {
            update { copy(isInspectingSource = true, errorMessage = null) }
            runCatching {
                externalSourceRepository.importPayload(sourceUri, payload, displayName)
            }.onSuccess { imported ->
                externalSourceStore.save(imported)
                syncProviders()
                update {
                    copy(
                        externalSources = externalSourceStore.loadAll(),
                        isInspectingSource = false,
                        errorMessage = null,
                    )
                }
            }.onFailure {
                update { copy(isInspectingSource = false) }
                showError(it)
            }
        }
    }

    fun reportExternalSourceError(message: String) = update {
        copy(isInspectingSource = false, errorMessage = message)
    }

    fun openExternalSource(source: ExternalSourceSummary) {
        cancelContentRequests()
        val imported = externalSourceStore.load(source.id)
        if (imported == null || imported.payload.isBlank()) {
            update { copy(errorMessage = "这个订阅来自旧版本，请移除后重新导入") }
            return
        }
        val entries = runCatching { externalSourceRepository.entries(imported) }
            .getOrElse {
                showError(it)
                return
            }
        update {
            copy(
                screen = Screen.EXTERNAL_ITEMS,
                selectedExternalSource = source,
                externalEntries = entries,
                liveChannels = entries.toLiveChannels(),
                livePrograms = emptyMap(),
                liveFavoriteKeys = emptySet(),
                externalQuery = "",
                externalGroup = null,
                liveGuideMessage = null,
                selectedExternalEntry = null,
                errorMessage = null,
            )
        }
        loadLiveMetadata(source, entries)
    }

    fun updateExternalQuery(value: String) = update { copy(externalQuery = value) }

    fun setExternalGroup(value: String?) = update { copy(externalGroup = value) }

    fun toggleLiveFavorite(channel: LiveChannel) {
        val source = state.value.selectedExternalSource ?: return
        val providerId = "external:${source.id}"
        val stableKey = "$providerId:${channel.id}"
        viewModelScope.launch {
            if (stableKey in state.value.liveFavoriteKeys) {
                localMediaState.removeFavorite(stableKey)
                update { copy(liveFavoriteKeys = liveFavoriteKeys - stableKey) }
            } else {
                localMediaState.addFavorite(
                    MediaFavoriteEntity(
                        stableKey = stableKey,
                        providerId = providerId,
                        itemId = channel.id,
                        title = channel.title,
                        subtitle = channel.group,
                        posterUrl = channel.logoUrl,
                        addedAtEpochMs = System.currentTimeMillis(),
                    )
                )
                update { copy(liveFavoriteKeys = liveFavoriteKeys + stableKey) }
            }
        }
    }

    fun playCatchup(entry: ExternalMediaEntry, program: LiveProgram) {
        val resolved = CatchupUrlResolver.resolve(entry, program)
        if (resolved == null) {
            update { copy(errorMessage = "这个频道的回看模板无法解析") }
        } else {
            playExternalEntry(resolved)
        }
    }

    fun downloadEmby(item: MediaItem) {
        val session = state.value.session ?: return
        downloadUnified(UnifiedMediaItem(MediaKey("emby:${session.serverId}:${session.userId}", item.id), item.name, item.type, subtitle = item.seriesName))
    }
    fun downloadUnified(item: UnifiedMediaItem) {
        viewModelScope.launch {
            try {
                val kind = top.cylunex.shadowmedia.model.contentKind(item.type)
                require(kind !in setOf(top.cylunex.shadowmedia.model.ContentKind.FOLDER, top.cylunex.shadowmedia.model.ContentKind.SERIES, top.cylunex.shadowmedia.model.ContentKind.LIVE_CHANNEL)) { "请选择具体文件" }
                val asset = requireNotNull(library).addRemote(item, if (kind in setOf(top.cylunex.shadowmedia.model.ContentKind.MUSIC, top.cylunex.shadowmedia.model.ContentKind.AUDIOBOOK)) "audio" else "mp4")
                requireNotNull(offline).enqueue(asset)
                update { copy(errorMessage = "已加入离线任务，可在来源页的离线管理查看") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
        }
    }

    private suspend fun tryOfflineVideo(key: MediaKey, positionMs: Long): Boolean {
        val asset = library?.dao?.assetForKey(key.providerId, key.itemId)?.takeIf { it.kind in setOf("MOVIE", "EPISODE") } ?: return false
        if (top.cylunex.shadowmedia.library.LibraryResources.hasOfflineMedia?.invoke(asset) != true) return false
        showOfflineVideo(asset, positionMs)
        return true
    }
    fun playOfflineAsset(asset: top.cylunex.shadowmedia.database.LibraryAssetEntity) {
        cancelContentRequests()
        contentRequest = viewModelScope.launch {
            val history = localMediaState.history("${asset.providerId}:${asset.itemId}")
            showOfflineVideo(asset, history?.takeUnless { it.completed }?.positionMs ?: 0)
        }
    }
    private fun showOfflineVideo(asset: top.cylunex.shadowmedia.database.LibraryAssetEntity, positionMs: Long) = update {
        copy(screen = Screen.EXTERNAL_PLAYER, externalPlayerReturnScreen = if (screen == Screen.PLAYER) playerReturnScreen else screen,
            selectedExternalEntry = ExternalMediaEntry(id = asset.itemId, sourceId = asset.providerId, title = asset.title,
                startPositionMs = positionMs, url = "shadow-cached://${asset.id}/resource.${asset.format}"), isLoading = false, errorMessage = null)
    }

    fun playExternalEntry(entry: ExternalMediaEntry) = navigate {
        copy(
            screen = Screen.EXTERNAL_PLAYER,
            externalPlayerReturnScreen = Screen.EXTERNAL_ITEMS,
            selectedExternalEntry = entry,
            errorMessage = null,
        )
    }

    fun removeExternalSource(sourceId: String) {
        externalSourceStore.remove(sourceId)
        syncProviders()
        update { copy(externalSources = externalSourceStore.loadAll()) }
    }

    fun refreshProviderConnections() = syncProviders()

    private fun syncProviders() {
        val storedNetworkConnections = networkStorageStore.loadAll()
        val providers = buildList {
            addAll(nativeProviders())
            library?.let { add(top.cylunex.shadowmedia.library.LibraryMediaProvider(it)) }
            sessionStore.loadAll().distinctBy { it.serverId to it.userId }.forEach { add(EmbyMediaProvider(it, repository)) }
            storedNetworkConnections.forEach { connection ->
                add(networkStorageRepository.provider(connection))
            }
            externalSourceStore.loadAll().forEach { summary ->
                val imported = externalSourceStore.load(summary.id) ?: return@forEach
                val entries = runCatching { externalSourceRepository.entries(imported) }.getOrDefault(emptyList())
                if (entries.isNotEmpty()) add(LiveMediaProvider(summary, entries))
                externalSourceRepository.catalogSites(imported).forEach { site ->
                    if (count { it is TvBoxHttpMediaProvider } < MAX_HTTP_PROVIDERS) {
                        add(TvBoxHttpMediaProvider(site, externalClient))
                    }
                }
            }
        }
        providerRegistry.replace(providers)
        update {
            copy(
                providerDescriptors = providers.map { it.descriptor },
                networkStorages = storedNetworkConnections,
                integrations = integrationStore.loadAll(),
            )
        }
    }

    private fun loadLiveMetadata(source: ExternalSourceSummary, entries: List<ExternalMediaEntry>) {
        liveGuideRequest?.cancel()
        liveGuideRequest = viewModelScope.launch {
            val providerId = "external:${source.id}"
            val now = System.currentTimeMillis()
            val cached = localMediaState.epg(
                source.id,
                now - 6L * 60 * 60 * 1_000,
                now + 3L * 24 * 60 * 60 * 1_000,
            ).first()
            val favorites = localMediaState.favoriteKeys(providerId)
            ensureActive()
            update {
                copy(
                    livePrograms = cached.groupBy(LiveProgram::channelId),
                    liveFavoriteKeys = favorites,
                )
            }

            val guideUrls = entries.mapNotNull(ExternalMediaEntry::epgUrl)
                .map(String::trim)
                .filter { it.isNotEmpty() && '{' !in it }
                .distinct()
            if (guideUrls.isEmpty()) {
                update { copy(liveGuideMessage = "订阅未提供标准 XMLTV 节目单") }
                return@launch
            }
            update { copy(isLoadingGuide = true, liveGuideMessage = null) }
            val results = supervisorScope {
                guideUrls.map { url ->
                    async {
                        runCatching {
                            liveGuideRepository.load(source.id, url, source.allowInsecureHttp)
                        }
                    }
                }.map { it.await() }
            }
            ensureActive()
            val programs = results.flatMap { result -> result.getOrElse { emptyList() } }
                .distinctBy { "${it.channelId}:${it.startEpochMs}" }
                .map { it.copy(sourceId = source.id) }
            if (programs.isNotEmpty()) {
                localMediaState.replaceEpg(source.id, programs)
                ensureActive()
                update {
                    copy(
                        livePrograms = programs.groupBy(LiveProgram::channelId),
                        isLoadingGuide = false,
                        liveGuideMessage = "节目单已更新 · ${programs.size} 个节目",
                    )
                }
            } else {
                val firstError = results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
                update {
                    copy(
                        isLoadingGuide = false,
                        liveGuideMessage = firstError?.let { "节目单更新失败：$it" }
                            ?: "节目单没有匹配当前时间范围的节目",
                    )
                }
            }
        }
    }

    fun cancelDelete() {
        if (!state.value.isDeleting) update { copy(pendingDeleteItem = null) }
    }

    fun confirmDelete() {
        val session = state.value.session ?: return
        val item = state.value.pendingDeleteItem ?: return
        if (state.value.isDeleting) return
        deleteRequest = viewModelScope.launch {
            update { copy(isDeleting = true, errorMessage = null) }
            try {
                repository.deleteItem(session, item.id)
                ensureActive()
                if (state.value.session != session) return@launch
                val snapshot = state.value
                val removedActiveItem = snapshot.screen == Screen.PLAYER && snapshot.selectedItem?.id == item.id
                val remaining = snapshot.items.filterNot { it.id == item.id }
                val remainingWallItems = snapshot.wallItems.filterNot { it.id == item.id }
                val retainedIndex = remaining.indexOfFirst { it.id == snapshot.items.getOrNull(snapshot.currentIndex)?.id }
                val nextIndex = retainedIndex.takeIf { it >= 0 } ?: snapshot.currentIndex.coerceAtMost((remaining.size - 1).coerceAtLeast(0))
                snapshot.selectedLibrary?.let { feedSessionStore.removeItem(session, it.id, item.id) }
                if (removedActiveItem) playbackRequest?.cancel()
                update {
                    copy(
                        screen = if (removedActiveItem && remaining.isEmpty()) Screen.ITEMS else screen,
                        items = remaining,
                        wallItems = remainingWallItems,
                        detailEpisodes = detailEpisodes.filterNot { it.id == item.id },
                        homeSections = homeSections.map { it.copy(items = it.items.filterNot { entry -> entry.id == item.id }) }.filter { it.items.isNotEmpty() },
                        selectedItem = if (removedActiveItem) null else selectedItem,
                        playbackPlan = if (removedActiveItem) null else playbackPlan,
                        currentIndex = nextIndex,
                        pendingDeleteItem = null,
                        isDeleting = false,
                        isLoading = false,
                    )
                }
                if (removedActiveItem && remaining.isNotEmpty()) playAt(nextIndex)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (state.value.session != session) return@launch
                update {
                    copy(
                        pendingDeleteItem = null,
                        isDeleting = false,
                        errorMessage = "删除失败：${error.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun back() {
        cancelContentRequests()
        when (state.value.screen) {
            Screen.LOGIN -> if (state.value.savedSessions.isNotEmpty()) showServers()
            Screen.PLAYER -> update {
                copy(
                    screen = playerReturnScreen,
                    playbackPlan = null,
                    selectedItem = null,
                    isLoading = false,
                    errorMessage = null,
                )
            }
            Screen.EXTERNAL_PLAYER -> update {
                copy(screen = externalPlayerReturnScreen, selectedExternalEntry = null, errorMessage = null)
            }
            Screen.EXTERNAL_ITEMS -> update {
                liveGuideRequest?.cancel()
                copy(
                    screen = Screen.SOURCES,
                    selectedExternalSource = null,
                    externalEntries = emptyList(),
                    liveChannels = emptyList(),
                    livePrograms = emptyMap(),
                    selectedExternalEntry = null,
                    errorMessage = null,
                )
            }
            Screen.DETAIL -> update {
                copy(screen = Screen.ITEMS, selectedSeries = null, detailEpisodes = emptyList())
            }
            Screen.PROVIDER_DETAIL -> {
                val stack = state.value.providerDetailBackStack
                if (stack.isNotEmpty()) {
                    update {
                        copy(
                            selectedUnifiedDetail = stack.last(),
                            providerDetailBackStack = stack.dropLast(1),
                            errorMessage = null,
                        )
                    }
                } else {
                    update { copy(screen = providerDetailReturnScreen, selectedUnifiedDetail = null, errorMessage = null) }
                }
            }
            Screen.ITEMS -> update {
                copy(
                    screen = Screen.LIBRARIES,
                    selectedLibrary = null,
                    wallItems = emptyList(),
                    items = emptyList(),
                )
            }
            Screen.NETWORK_STORAGES -> update { copy(screen = networkStorageReturnScreen, errorMessage = null) }
            Screen.LIBRARIES, Screen.SOURCES, Screen.DISCOVER, Screen.SETTINGS, Screen.INTEGRATIONS,
            Screen.INSIGHTS -> showHome()
            Screen.HOME -> showServers()
            Screen.SERVERS -> Unit
        }
    }

    fun logout() {
        cancelContentRequests()
        playbackRequest?.cancel()
        deleteRequest?.cancel()
        val session = state.value.session ?: return
        sessionStore.remove(session)
        val remaining = sessionStore.loadAll()
        mutableState.value = MainUiState(
            screen = if (remaining.isEmpty()) Screen.LOGIN else Screen.SERVERS,
            savedSessions = remaining,
        )
        syncProviders()
        viewModelScope.launch {
            playbackOutbox.discard(session)
            runCatching { repository.logout(session) }
        }
    }

    private fun restoreSession(session: EmbySession, saved: List<EmbySession>) {
        cancelContentRequests()
        mutableState.value = MainUiState(
            screen = Screen.HOME,
            serverUrl = session.serverUrl,
            userName = session.userName,
            allowInsecureHttp = session.allowInsecureHttp,
            savedSessions = saved,
            session = session,
            externalSources = externalSourceStore.loadAll(),
            isLoading = true,
        )
        viewModelScope.launch {
            try { playbackOutbox.flush(session) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (state.value.session == session) showError(e) }
        }
        viewModelScope.launch { loadLibraries(session) }
    }

    private suspend fun loadLibraries(session: EmbySession) {
        runCatching { repository.libraries(session) }
            .onSuccess { libraries ->
                if (state.value.session != session) return@onSuccess
                val lastFeed = feedSessionStore.lastFor(session)
                update {
                    copy(
                        libraries = libraries,
                        lastFeedLibraryId = lastFeed?.libraryId?.takeIf { id -> libraries.any { it.id == id } },
                        isLoading = if (screen in setOf(Screen.HOME, Screen.LIBRARIES)) false else isLoading,
                    )
                }
                runCatching { repository.home(session, libraries.map(MediaLibrary::id)) }
                    .onSuccess { sections -> if (state.value.session == session) update { copy(homeSections = sections.withRecommendations()) } }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; if (state.value.session == session) update { copy(errorMessage = "首页加载不完整：${it.message ?: "未知错误"}") } }
            }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; if (state.value.session == session) showError(it) }
    }

    private fun showError(error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        update { copy(isLoading = false, errorMessage = error.message ?: "发生未知错误") }
    }

    private fun update(transform: MainUiState.() -> MainUiState) = mutableState.update(transform)

    private fun cancelContentRequests() {
        contentRequest?.cancel()
        playbackRequest?.cancel()
        liveGuideRequest?.cancel()
        update { copy(isLoading = false, isLoadingMore = false, isLoadingGuide = false) }
    }

    private fun navigate(transform: MainUiState.() -> MainUiState) {
        cancelContentRequests()
        update(transform)
    }

    companion object {
        private const val MAX_PLAYBACK_REFRESHES = 1
        private const val WALL_PAGE_SIZE = 60
        private const val MAX_HTTP_PROVIDERS = 16

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(
                        container.embyRepository,
                        container.sessionStore,
                        container.feedSessionStore,
                        container.playbackOutbox,
                        container.externalSourceRepository,
                        container.externalSourceStore,
                        container.localMediaState,
                        container.liveGuideRepository,
                        container.providerRegistry,
                        container.aggregateSearchEngine,
                        container.externalClient,
                        container.handoffInbox,
                        container.integrationRepository,
                        container.integrationStore,
                        container.networkStorageRepository,
                        container.networkStorageStore,
                        library = container.library,
                        offline = container.offline,
                        nativeProviders = container.catalogs::musicProviders,
                    ) as T
            }
    }
}

private fun SegmentType.displayName(): String = when (this) {
    SegmentType.INTRO -> "片头"
    SegmentType.RECAP -> "前情回顾"
    SegmentType.CREDITS -> "片尾"
    SegmentType.PREVIEW -> "预告"
    SegmentType.HIGHLIGHT -> "精彩片段"
    SegmentType.CHAPTER -> "章节"
}

private fun MediaLibrary.wallItemTypes(): Set<String> = when (collectionType?.lowercase()) {
    "tvshows" -> setOf("Series")
    "movies" -> setOf("Movie")
    "boxsets" -> setOf("BoxSet")
    "music" -> setOf("MusicAlbum", "MusicVideo")
    else -> setOf("Movie", "Episode", "Video", "Series")
}

private fun UnifiedMediaItem.toEmbyMediaItem() = MediaItem(
    id = key.itemId,
    name = title,
    type = type,
    seriesName = subtitle,
    seasonNumber = null,
    episodeNumber = null,
    runTimeTicks = durationMs?.times(10_000),
    playbackPositionTicks = progressMs * 10_000,
    played = played,
    favorite = favorite,
    overview = overview,
    productionYear = year,
    communityRating = rating,
    externalIds = externalIds,
)

private fun String.normalizedStoragePath(): String =
    "/" + replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() && it != "." }.fold(mutableListOf<String>()) { path, part ->
        if (part == "..") { if (path.isNotEmpty()) path.removeAt(path.lastIndex) } else path += part
        path
    }.joinToString("/")

private fun formatPosition(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

private fun List<MediaSection>.withRecommendations(): List<MediaSection> {
    val candidates = flatMap(MediaSection::items)
        .distinctBy(MediaItem::id)
        .filter { it.type !in setOf("Series", "BoxSet", "MusicAlbum") }
        .sortedByDescending { item ->
            var score = item.communityRating ?: 0.0
            if (item.playbackPositionTicks > 0 && !item.played) score += 60.0
            if (item.favorite) score += 35.0
            if (!item.played) score += 25.0 else score -= 20.0
            score
        }
        .take(16)
    if (candidates.isEmpty()) return this
    return listOf(
        MediaSection(
            id = "recommended-now",
            title = "现在就看 · 基于进度、收藏与评分",
            kind = MediaSectionKind.RECOMMENDED,
            items = candidates,
        )
    ) + this
}
