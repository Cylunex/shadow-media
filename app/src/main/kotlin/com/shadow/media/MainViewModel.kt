package com.shadow.media

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
import com.shadow.media.playback.*
import com.shadow.media.model.isMixedVideoLibrary
import com.shadow.media.model.EmbySession
import com.shadow.media.model.BrowseRequest
import com.shadow.media.model.ExternalSourceSummary
import com.shadow.media.model.ExternalMediaEntry
import com.shadow.media.model.LiveChannel
import com.shadow.media.model.LiveProgram
import com.shadow.media.model.MediaItem
import com.shadow.media.model.MediaFilter
import com.shadow.media.model.MediaLibrary
import com.shadow.media.model.MediaSection
import com.shadow.media.model.MediaSectionKind
import com.shadow.media.model.MediaSort
import com.shadow.media.model.PlaybackPlan
import com.shadow.media.model.PlaybackEvent
import com.shadow.media.model.embyTicksToMilliseconds
import com.shadow.media.model.millisecondsToEmbyTicks
import com.shadow.media.network.EmbyRepository
import com.shadow.media.network.withOptionalCatalogCache
import com.shadow.media.network.ExternalSourceRepository
import com.shadow.media.network.ExternalSourceStore
import com.shadow.media.network.LoginRequest
import com.shadow.media.network.PlaybackOutbox
import com.shadow.media.network.PlaybackReport
import com.shadow.media.network.SessionStore
import com.shadow.media.database.LocalMediaStateRepository
import com.shadow.media.model.FeatureId
import com.shadow.media.model.MediaDetail
import com.shadow.media.model.MediaKey
import com.shadow.media.model.NetworkStorageConnection
import com.shadow.media.model.ProviderDescriptor
import com.shadow.media.model.UnifiedMediaItem
import com.shadow.media.model.UnifiedPlaybackRequest
import com.shadow.media.model.toLiveChannels
import com.shadow.media.network.CatchupUrlResolver
import com.shadow.media.network.LiveGuideRepository
import com.shadow.media.network.TvBoxHttpMediaProvider
import com.shadow.media.database.MediaFavoriteEntity
import com.shadow.media.provider.AggregateSearchEngine
import com.shadow.media.provider.InMemoryProviderRegistry
import com.shadow.media.provider.ProviderSearchFailure
import com.shadow.media.provider.ProviderSearchRequest
import okhttp3.OkHttpClient
import java.util.UUID
import com.shadow.media.model.MediaSegment
import com.shadow.media.model.SegmentSource
import com.shadow.media.model.SegmentType
import com.shadow.media.network.IntegrationRepository
import com.shadow.media.network.IntegrationStore
import com.shadow.media.network.NetworkStorageRepository
import com.shadow.media.network.NetworkStorageStore
import com.shadow.media.database.MediaMomentEntity
import com.shadow.media.database.PlaybackMetricEntity
import com.shadow.media.database.SourceHealthEntity

enum class Screen {
    SERVERS, LOGIN, HOME, LIBRARIES, ITEMS, DETAIL, SOURCES, EXTERNAL_ITEMS, EXTERNAL_PLAYER,
    DISCOVER, PROVIDER_DETAIL, MDC, NETWORK_STORAGES, INTEGRATIONS, INSIGHTS, SETTINGS, PLAYER
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
    val feedFilter: FeedFilter = FeedFilter(),
    val externalSources: List<ExternalSourceSummary> = emptyList(),
    val externalSourceReadError: String? = null,
    val sourceUrl: String = "",
    val sourceAllowInsecureHttp: Boolean = false,
    val isInspectingSource: Boolean = false,
    val selectedExternalSource: ExternalSourceSummary? = null,
    val externalEntries: List<ExternalMediaEntry> = emptyList(),
    val liveChannels: List<LiveChannel> = emptyList(),
    val externalQuery: String = "",
    val externalGroup: String? = null,
    val selectedExternalEntry: ExternalMediaEntry? = null,
    val externalAttemptedLines: Set<String> = emptySet(),
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
    val networkStorageReturnScreen: Screen = Screen.HOME,
    val mediaSegments: List<MediaSegment> = emptyList(),
    val insightMessage: String? = null,
    val selectedItem: MediaItem? = null,
    val currentIndex: Int = 0,
    val wallFolders: List<MediaItem> = emptyList(),
    val playbackPlan: PlaybackPlan? = null,
    val playbackStartPositionMs: Long = 0,
    val playbackPlayWhenReady: Boolean = true,
    val playbackResolutionRecovery: PlaybackRecoveryState = PlaybackRecoveryState(),
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
    private val library: com.shadow.media.library.LibraryRepository? = null,
    private val offline: com.shadow.media.library.OfflineRepository? = null,
    private val nativeProviders: () -> List<com.shadow.media.provider.MediaProvider> = { emptyList() },
    private val prepareAccounts: suspend () -> Unit = {},
    private val lineHealth: com.shadow.media.library.LiveLineHealthStore? = null,
    private val autoSwitchLines: () -> Boolean = { true },
    private val playbackNetwork: StateFlow<Boolean> = MutableStateFlow(true),
    private val retryLimit: () -> Int = { 2 },
    private val mdcConnections: () -> List<com.shadow.media.library.CatalogConnection> = { emptyList() },
    private val sharedMdcConnections: () -> List<com.shadow.media.library.CatalogConnection> = { emptyList() },
    private val discoverSharedMdc: suspend (Boolean) -> List<String> = { emptyList() },
    private val sharedMdcToken: (suspend (com.shadow.media.library.CatalogConnection, String?) -> String)? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    internal val networkStorage = NetworkStorageController(viewModelScope, networkStorageRepository, networkStorageStore,
        providersChanged = { syncProviders() }, reportError = { message -> update { copy(errorMessage = message) } })
    internal val integrations = IntegrationController(viewModelScope, integrationRepository, integrationStore,
        externalSourceRepository, externalSourceStore, providersChanged = { syncProviders() })
    var feedPreloader: com.shadow.media.playback.FeedPreloadPool? = null
    private var resolutionRetry: (() -> Unit)? = null
    private val resolutionRecovery = PlaybackRecovery(viewModelScope, { !com.shadow.media.model.NetworkPolicy.offlineOnly }, retryLimit) { resolutionRetry?.invoke() }
    fun setResolutionForeground(active: Boolean) = resolutionRecovery.setForeground(active)
    fun toggleResolutionPause() {
        val playing = !state.value.playbackPlayWhenReady
        update { copy(playbackPlayWhenReady = playing) }
        resolutionRecovery.setPlayWhenReady(playing)
    }
    private var playbackRequest: Job? = null
    private var mdcPlayback: MdcPlaybackContext? = null
    private var contentRequest: Job? = null
    private var unifiedPageRequest = 0L
    private var favoriteObservation: Job? = null
    private val wallPagination = WallPagination()
    private val playbackOwnership = PlaybackOwnership()
    private var deleteRequest: Job? = null
    internal val liveGuide = LiveGuideController(viewModelScope, localMediaState, liveGuideRepository)
    private var providerSearchRequest: Job? = null
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
        viewModelScope.launch { playbackNetwork.collect(resolutionRecovery::setNetworkAvailable) }
        viewModelScope.launch { resolutionRecovery.state.collect { value -> update { copy(playbackResolutionRecovery = value) } } }
        viewModelScope.launch {
        prepareAccounts()
        val saved = sessionStore.loadAll()
        val active = sessionStore.load()
        if (active != null) restoreSession(active, saved) else {
            mutableState.value = MainUiState(
                screen = Screen.HOME,
                savedSessions = saved,
            )
        }
        syncProviders()
        }
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
        syncProviders()
    }

    fun requestRemoveServer(session: EmbySession) =
        update { copy(pendingRemoveSession = session, errorMessage = null) }

    fun cancelRemoveServer() = update { copy(pendingRemoveSession = null) }

    fun confirmRemoveServer() {
        val session = state.value.pendingRemoveSession ?: return
        cancelContentRequests()
        try { sessionStore.remove(session) }
        catch (e: Exception) { showError(e); return }
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
                    isLoading = true,
                )
                syncProviders()
                viewModelScope.launch {
                    try { playbackOutbox.flush(session) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (state.value.session == session) showError(e) }
                }
                favoriteObservation?.cancel()
        favoriteObservation = viewModelScope.launch {
            repository.favoriteStates(session).collect { favorites ->
                if (state.value.session != session) return@collect
                fun MediaItem.localFavorite() = favorites[id]?.let { copy(favorite = it) } ?: this
                update { copy(items = items.map { it.localFavorite() }, wallItems = wallItems.map { it.localFavorite() },
                    detailEpisodes = detailEpisodes.map { it.localFavorite() }, selectedSeries = selectedSeries?.localFavorite(),
                    homeSections = homeSections.map { section -> section.copy(items = section.items.map { it.localFavorite() }.let { values -> if (section.kind == MediaSectionKind.FAVORITES) values.filter { it.favorite } else values }) }) }
            }
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

    fun showMdc() = navigate { copy(screen = Screen.MDC, errorMessage = null) }

    internal fun mdcProvider(connection: com.shadow.media.library.CatalogConnection) =
        MdcMediaProvider(connection, externalClient, if (connection.isSharedMdc()) {
            { rejected -> checkNotNull(sharedMdcToken) { "统一服务连接不可用" }.invoke(connection, rejected) }
        } else null)

    internal fun artworkProvider(id: String): MdcMediaProvider? = when (val provider = providerRegistry.provider(id)) {
        is MdcMediaProvider -> provider
        is MdcTvBoxProvider -> provider.mdc
        else -> null
    }

    internal fun deviceHandoffFailed(error: Exception) = showError(error)

    internal suspend fun resumeDevicePlayback(key: MediaKey, position: Long) {
        syncProviders()
        val provider = requireNotNull(providerRegistry.provider(key.providerId)) { "请在此设备连接相同来源" }
        if (provider is MdcMediaProvider && key.itemId.contains(":version:")) {
            val work = key.copy(itemId = key.itemId.substringBefore(":version:"))
            val plan = provider.plan(work, online = true)
            val source = requireNotNull(selectMdcRecoverySource(plan, key.itemId.substringAfter(":version:"), "")) { "原版本已不可用" }
            playMdc(provider, work, plan, source, position)
        } else {
            val item = provider.detail(key).item.copy(progressMs = position)
            playUnifiedOnline(item, useSavedPosition = false)
        }
    }

    internal fun knownMdcConnections() = readMdcConnections(mdcConnections, sharedMdcConnections)

    internal suspend fun discoverMdcConnections(force: Boolean = false): MdcDiscovery {
        val failed = try { discoverSharedMdc(force) }
        catch (e: CancellationException) { throw e }
        catch (e: IllegalStateException) { listOf(e.message ?: "统一账号") }
        val known = knownMdcConnections()
        syncProviders(known)
        return known.copy(failedAccounts = (known.failedAccounts + failed).distinct())
    }

    internal suspend fun playMdc(
        provider: MdcMediaProvider, key: MediaKey, preparedPlan: org.json.JSONObject? = null,
        chosenSource: org.json.JSONObject? = null, resumePosition: Long? = null,
    ) {
        val plan = preparedPlan ?: provider.plan(key)
        val target = plan.optJSONObject("emby")
        if (target == null) {
            val sources = plan.optJSONArray("sources")
            val source = chosenSource ?: sources?.optJSONObject(0)
                ?: error("暂无已确认的播放线路，可在作品详情关联 Emby")
            val sourceId = source.optString("version_id").takeUnless { it.isBlank() || it == "null" }
                ?: error("播放线路缺少稳定版本身份")
            val item = provider.detail(key).item
            val subtitles = provider.workSubtitles(key, plan)
            val playbackId = key.itemId + ":version:" + sourceId
            val history = localMediaState.history(externalHistoryKey(key.providerId, playbackId).stableId)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            mdcPlayback = MdcPlaybackContext(provider, key, playbackId, sourceId,
                source.optString("source_id"))
            update { copy(
                screen = Screen.EXTERNAL_PLAYER,
                externalPlayerReturnScreen = Screen.MDC,
                selectedExternalEntry = ExternalMediaEntry(
                    id = playbackId,
                    startPositionMs = resumePosition ?: history?.takeUnless { it.completed }?.positionMs ?: 0,
                    sourceId = key.providerId, title = item.title, subtitles = subtitles,
                    url = source.getString("url"), group = provider.descriptor.name,
                    mimeType = source.optString("mime_type").takeUnless { it.isBlank() || it == "null" },
                ),
                isLoading = false,
            ) }
            return
        }
        val serverId = target.getString("server_id")
        val itemId = target.getString("item_id")
        val accounts = sessionStore.loadAll().filter { it.serverId == serverId }
        val session = accounts.singleOrNull() ?: accounts.singleOrNull { it == state.value.session }
            ?: error(if (accounts.isEmpty()) "请先在 Media 连接此 Emby 服务器" else "请先在 Emby 媒体服务中选择播放账号")
        val item = repository.item(session, itemId)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(sessionStore.select(session)) { "此 Emby 账号已移除，请重新连接" }
        playbackRequest?.cancel()
        deleteRequest?.cancel()
        update {
            copy(
                session = session,
                serverUrl = session.serverUrl,
                userName = session.userName,
                allowInsecureHttp = session.allowInsecureHttp,
                libraries = if (this.session == session) libraries else emptyList(),
                homeSections = if (this.session == session) homeSections else emptyList(),
                selectedLibrary = if (this.session == session) selectedLibrary else null,
                items = listOf(item),
                playerReturnScreen = Screen.MDC,
                feedMode = false,
            )
        }
        playAt(0)
    }

    fun showLibraries() = navigate { copy(screen = Screen.LIBRARIES, errorMessage = null) }

    fun refreshExternalSources() = syncProviders()

    fun showSources() {
        navigate { copy(screen = Screen.SOURCES, sourceUrl = "", errorMessage = null) }
        refreshExternalSources()
    }

    fun showSettings() = navigate { copy(screen = Screen.SETTINGS, errorMessage = null) }

    fun showNetworkStorages() {
        cancelContentRequests()
        update { copy(screen = Screen.NETWORK_STORAGES, networkStorageReturnScreen = screen, errorMessage = null) }
        networkStorage.open()
    }

    fun openNetworkStorage(connection: NetworkStorageConnection) {
        cancelContentRequests()
        syncProviders()
        val providerId = "storage:${connection.id}"
        val provider = providerRegistry.provider(providerId) ?: return
        contentRequest = viewModelScope.launch {
            update { copy(screen = Screen.PROVIDER_DETAIL, selectedUnifiedDetail = null, providerDetailBackStack = emptyList(), providerDetailReturnScreen = Screen.NETWORK_STORAGES, isLoading = true, errorMessage = null) }
            withOptionalCatalogCache { provider.cachedDetail(MediaKey(providerId, connection.rootPath.normalizedStoragePath())) }?.let { update { copy(selectedUnifiedDetail = it) } }
            runCatching { provider.detail(MediaKey(providerId, connection.rootPath.normalizedStoragePath())) }
                .onSuccess { detail -> ensureActive(); update { copy(selectedUnifiedDetail = detail, isLoading = false) } }
                .onFailure { ensureActive(); showError(it) }
        }
    }

    fun showIntegrations() {
        cancelContentRequests()
        update { copy(screen = Screen.INTEGRATIONS, errorMessage = null) }
        integrations.open()
    }

    fun requestSelectedMedia() {
        state.value.selectedUnifiedDetail?.item?.let(integrations::requestMedia)
    }

    fun showInsights() = navigate { copy(screen = Screen.INSIGHTS, insightMessage = null, errorMessage = null) }

    fun clearInsightMessage() = update { copy(insightMessage = null) }

    fun saveMoment(item: MediaItem, positionMs: Long) {
        val session = state.value.session ?: return
        val providerId = session.providerId
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
            providerId = session.providerId,
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
        val expectedProvider = session.providerId
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
                val health = sourceHealth.value.associateBy { it.sourceKey }
                aggregateSearchEngine.searchSnapshots(
                    providerRegistry.providers.first().filterNot {
                        (it is MdcMediaProvider && !it.connection.includeInSearch) ||
                            (it is MdcTvBoxProvider && !it.includeInSearch)
                    },
                    ProviderSearchRequest(query = query, pageSize = 60),
                    sourcePenalty = { item ->
                        health[item.key.providerId + ":site:" + item.key.itemId.substringBefore(':')]?.consecutiveFailures ?: 0
                    },
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
        val token = if (retry) state.value.providerSearchFailures.firstOrNull { it.providerId == providerId }?.pageToken
            else state.value.providerSearchNext[providerId] ?: return
        val generation = searchGeneration; val query = submittedQuery
        update { copy(loadingSearchPages = loadingSearchPages + providerId) }
        searchPageJobs[providerId] = viewModelScope.launch {
            try {
                val page = kotlinx.coroutines.withTimeout(12_000) { provider.search(ProviderSearchRequest(query, pageToken = token)) }
                if (generation == searchGeneration) update { copy(
                    discoverResults = (discoverResults + page.items).distinctBy { it.key },
                    providerSearchNext = (providerSearchNext - providerId) + (page.nextPageToken?.takeUnless { it == token }?.let { mapOf(providerId to it) } ?: emptyMap()),
                    providerSearchFailures = providerSearchFailures.filterNot { it.providerId == providerId } +
                        listOfNotNull(page.failedSources.takeIf { it.isNotEmpty() }?.let {
                            ProviderSearchFailure(providerId, provider.descriptor.name, "未响应站点：" + it.joinToString("、"), token)
                        })) }
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
            withOptionalCatalogCache { provider.cachedDetail(item.key) }?.let { update { copy(selectedUnifiedDetail = it) } }
            runCatching { provider.detail(item.key) }
                .onSuccess { detail -> ensureActive(); update { copy(selectedUnifiedDetail = detail, isLoading = false) } }
                .onFailure { ensureActive(); showError(it) }
        }
    }

    fun loadMoreUnifiedChildren() {
        if (state.value.screen != Screen.PROVIDER_DETAIL) return
        val detail = state.value.selectedUnifiedDetail ?: return
        val token = detail.childrenNextPageToken ?: return
        if (state.value.isLoading || state.value.isLoadingMore) return
        val provider = providerRegistry.provider(detail.item.key.providerId) ?: return
        val request = ++unifiedPageRequest
        contentRequest = viewModelScope.launch {
            update { copy(isLoadingMore = true, errorMessage = null) }
            try {
                val page = provider.browse(com.shadow.media.provider.ProviderBrowseRequest(parentKey = detail.item.key, pageToken = token))
                ensureActive()
                require(page.nextPageToken != token) { "目录分页未前进" }
                update {
                    val currentDetail = selectedUnifiedDetail
                    if (request != unifiedPageRequest || currentDetail?.item?.key != detail.item.key) this
                    else copy(selectedUnifiedDetail = currentDetail.copy(
                        children = (currentDetail.children + page.items).distinctBy { it.key },
                        childrenNextPageToken = page.nextPageToken, cached = currentDetail.cached || page.cached))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
            finally { if (request == unifiedPageRequest) update { copy(isLoadingMore = false) } }
        }
    }

    fun playUnifiedItem(item: UnifiedMediaItem, queueEntryId: String? = null, playWhenReady: Boolean = true) {
        if (com.shadow.media.model.contentKind(item.type) in setOf(com.shadow.media.model.ContentKind.FOLDER, com.shadow.media.model.ContentKind.SERIES)) {
            openUnifiedItem(item); return
        }
        cancelContentRequests()
        contentRequest = viewModelScope.launch {
            if (!tryOfflineVideo(item.key, item.progressMs)) playUnifiedOnline(item, queueEntryId, playWhenReady)
        }
    }
    private fun playUnifiedOnline(
        item: UnifiedMediaItem, queueEntryId: String? = null, playWhenReady: Boolean = true, useSavedPosition: Boolean = true,
    ) {
        cancelContentRequests()
        if (com.shadow.media.model.contentKind(item.type) in setOf(
                com.shadow.media.model.ContentKind.BOOK, com.shadow.media.model.ContentKind.COMIC, com.shadow.media.model.ContentKind.AUDIOBOOK, com.shadow.media.model.ContentKind.MUSIC, com.shadow.media.model.ContentKind.PODCAST)) {
            update { copy(pendingPublication = item) }
            return
        }
        val provider = providerRegistry.provider(item.key.providerId) ?: return
        if (provider is MdcMediaProvider) {
            contentRequest = viewModelScope.launch {
                update { copy(isLoading = true, errorMessage = null) }
                try {
                    val workId = item.key.itemId.substringBefore(":version:")
                    val versionId = item.key.itemId.substringAfter(":version:", "")
                    val key = MediaKey(provider.descriptor.id, workId)
                    val plan = provider.plan(key, online = versionId.isNotBlank())
                    val source = if (versionId.isBlank()) null else requireNotNull(
                        selectMdcRecoverySource(plan, versionId, ""),
                    ) { "该版本已不可用，可从作品详情选择其他版本" }
                    ensureActive()
                    playMdc(provider, key, plan, source)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { showError(e) }
                finally { update { copy(isLoading = false) } }
            }
            return
        }
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
            val embyItem = (provider.mediaItem(item.key) ?: item.toEmbyMediaItem()).copy(playbackPositionTicks = item.progressMs.coerceIn(0, Long.MAX_VALUE / 10000) * 10000)
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
                    playerReturnScreen = if (screen == Screen.PLAYER) playerReturnScreen else screen,
                    feedMode = false,
                )
            }
            if (useSavedPosition) playAt(0)
            else resolvePlayback(provider.session, embyItem, 0, item.progressMs, 0, playWhenReady)
            return
        }
        contentRequest = viewModelScope.launch {
            update { copy(isLoading = true, errorMessage = null) }
            val resolveStarted = android.os.SystemClock.elapsedRealtime()
            var episode: ResolvedEpisode? = null
            runCatching {
                val request = UnifiedPlaybackRequest(item.key, item.progressMs)
                if (provider is MdcTvBoxProvider) {
                    val line = provider.resolveEpisode(request, emptySet(), autoSwitchLines())
                    episode = line
                    listOf(line.candidate)
                } else provider.resolve(request)
            }
                .onSuccess { candidates ->
                    ensureActive()
                    val candidate = candidates.firstOrNull()
                    if (provider is MdcTvBoxProvider && candidate != null) lineHealth?.recordResolve(
                        item.key.providerId, item.key.itemId.substringBefore(':'), android.os.SystemClock.elapsedRealtime() - resolveStarted)
                    if (candidate == null) {
                        update { copy(isLoading = false, errorMessage = "这个 Provider 没有返回可播放线路") }
                    } else {
                        val position = if (useSavedPosition && (provider is MdcTvBoxProvider || item.key.providerId.startsWith("tvbox:")) && item.progressMs == 0L) {
                            localMediaState.history(externalHistoryKey(item.key.providerId, item.key.itemId).stableId)
                                ?.takeUnless { it.completed }?.positionMs ?: 0L
                        } else item.progressMs
                        ensureActive()
                        update {
                            copy(
                                screen = Screen.EXTERNAL_PLAYER,
                                externalPlayerReturnScreen = if (screen == Screen.EXTERNAL_PLAYER) externalPlayerReturnScreen else screen,
                                selectedExternalEntry = ExternalMediaEntry(
                                    queueEntryId = queueEntryId, playWhenReady = playWhenReady,
                                    id = item.key.itemId,
                                    playbackItemId = episode?.key?.itemId,
                                    failedPlaybackItems = episode?.failed.orEmpty(),
                                    sourceId = item.key.providerId,
                                    title = item.title,
                                    url = candidate.url,
                                    group = provider.descriptor.name,
                                    logoUrl = item.posterUrl,
                                    requestHeaders = candidate.requiredHeaders,
                                    subtitles = candidate.subtitles,
                                    credentialOrigin = candidate.credentialOrigin,
                                    startPositionMs = position,
                                    mimeType = candidate.mimeType,
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
                .onFailure {
                    ensureActive()
                    if (provider is MdcTvBoxProvider) localMediaState.recordSiteResult(item.key.providerId, item.key.itemId.substringBefore(':'), false, null)
                    showError(it)
                }
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
                wallFolders = emptyList(),
                feedFilter = state.value.session?.let { feedSessionStore.filter(it, library.id) } ?: FeedFilter(),
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

    fun setFeedFilter(filter: FeedFilter) {
        val snapshot = state.value
        val session = snapshot.session ?: return
        val library = snapshot.selectedLibrary ?: return
        feedSessionStore.setFilter(session, library.id, filter)
        update { copy(feedFilter = filter) }
    }
    fun startLibraryFeed() {
        val library = state.value.selectedLibrary ?: return
        loadFeed(library, autoPlay = true)
    }

    private fun loadFeed(library: MediaLibrary, autoPlay: Boolean) {
        val session = state.value.session ?: return
        cancelContentRequests()
        val filter = feedSessionStore.filter(session, library.id)
        contentRequest = viewModelScope.launch {
            update {
                copy(
                    feedFilter = filter,
                    selectedLibrary = library,
                    items = emptyList(),
                    screen = Screen.ITEMS,
                    feedMode = true,
                    isLoading = true,
                    errorMessage = null,
                )
            }
            runCatching { feedSessionStore.candidates(repository, session, library.id, filter) }
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
                            errorMessage = if (orderedItems.isEmpty()) "当前刷片条件下没有影片" else null,
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
            parentId = snapshot.wallFolders.lastOrNull()?.id ?: library.id, includeItemTypes = library.wallItemTypes(),
            recursive = !library.isMixedVideoLibrary || snapshot.wallSearch.isNotBlank(), searchTerm = snapshot.wallSearch,
            sort = snapshot.wallSort, descending = snapshot.wallSort != MediaSort.NAME,
            filter = snapshot.wallFilter, limit = WALL_PAGE_SIZE,
        ))
        val request = wallPagination.request() ?: return
        contentRequest = viewModelScope.launch {
            update {
                if (reset) copy(isLoading = true, isLoadingMore = false, wallHasMore = false, wallItems = emptyList(), errorMessage = null)
                else copy(isLoadingMore = true, errorMessage = null)
            }
            repository.cachedBrowse(session, request)?.let { page ->
                ensureActive()
                if (state.value.session == session) update { copy(wallItems = if (reset) page.items else (wallItems + page.items).distinctBy(MediaItem::id), wallTotalCount = page.totalRecordCount, isLoading = false, errorMessage = "正在显示本机目录，联网后可刷新") }
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
                        errorMessage = if (page.cached) "正在显示本机目录，联网后可刷新" else null,
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
        if (item.isFolder && !item.type.equals("Series", true) && !item.type.equals("BoxSet", true)) {
            update { copy(wallFolders = wallFolders + item, wallSearch = "", wallFilter = MediaFilter.ALL) }
            loadWall(reset = true)
            return
        }
        if (item.type.equals("Series", ignoreCase = true) || item.type.equals("BoxSet", ignoreCase = true)) {
            loadDetail(item)
        } else {
            val playable = state.value.wallItems.filterNot {
                it.isFolder || it.type.equals("Series", true) || it.type.equals("BoxSet", true)
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
            repository.cachedChildren(session, item.id)?.let { episodes ->
                if (state.value.session == session) update { copy(detailEpisodes = episodes, isLoading = false) }
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
        retryPlayback(state.value.playbackStartPositionMs, state.value.playbackPlayWhenReady)
    }

    fun retryPlayback(positionMs: Long, playWhenReady: Boolean) {
        val snapshot = state.value
        val session = snapshot.session ?: return
        val item = snapshot.items.getOrNull(snapshot.currentIndex) ?: return
        resolvePlayback(session, item, snapshot.currentIndex, positionMs.coerceAtLeast(0L), 0, playWhenReady)
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
        playWhenReady: Boolean = true,
        resolutionRetrying: Boolean = false,
    ) {
        if (!resolutionRetrying) {
            resolutionRecovery.manualRetry()
            resolutionRetry = {
                if (state.value.session == session && state.value.screen == Screen.PLAYER && state.value.selectedItem?.id == item.id)
                    resolvePlayback(session, item, index, startPositionMs, refreshAttempts, state.value.playbackPlayWhenReady, resolutionRetrying = true)
            }
        }
        resolutionRecovery.setPlayWhenReady(playWhenReady)
        contentRequest?.cancel()
        playbackRequest?.cancel()
        update {
            copy(
                screen = Screen.PLAYER,
                selectedItem = item,
                currentIndex = index,
                playbackPlan = null,
                playbackStartPositionMs = startPositionMs,
                playbackPlayWhenReady = playWhenReady,
                playbackRefreshAttempts = refreshAttempts,
                mediaSegments = emptyList(),
                isLoading = true,
                errorMessage = if (refreshAttempts > 0) "播放地址失效，正在重新解析…" else null,
            )
        }
        viewModelScope.launch {
            val providerId = session.providerId
            val segments = localMediaState.segments(providerId, item.id).first()
            if (state.value.session == session && state.value.screen == Screen.PLAYER && state.value.selectedItem?.id == item.id) update { copy(mediaSegments = segments) }
        }
        playbackRequest = viewModelScope.launch {
            try {
                if (tryOfflineVideo(MediaKey(session.providerId, item.id), startPositionMs)) return@launch
                val prepared = if (state.value.feedMode && refreshAttempts == 0) feedPreloader?.preparedPlan(session, item.id) else null
                val plan = prepared ?: repository.playbackPlan(session, item.id)
                ensureActive()
                if (state.value.session == session && state.value.screen == Screen.PLAYER && state.value.selectedItem?.id == item.id) {
                    resolutionRecovery.ready()
                    playbackOwnership.remember(plan, session)
                    update { copy(playbackPlan = plan, isLoading = false, errorMessage = null) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (state.value.session == session && state.value.screen == Screen.PLAYER && state.value.selectedItem?.id == item.id) {
                    val failure = classifyPlaybackResolutionFailure(error, com.shadow.media.model.NetworkPolicy.offlineOnly)
                    resolutionRecovery.recover(failure)
                    update { copy(isLoading = false, errorMessage = failure.message) }
                }
            }
        }
    }

    fun requestDelete(item: MediaItem) = update { copy(pendingDeleteItem = item, errorMessage = null) }

    fun toggleFavorite(item: MediaItem) {
        val session = state.value.session ?: return
        val target = !item.favorite
        viewModelScope.launch {
            runCatching {
                library?.addRemote(UnifiedMediaItem(MediaKey(session.providerId, item.id), item.name, item.type, favorite = target), "video", collected = false)
                repository.setFavorite(session, item.id, target)
            }
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
                            homeSections = (if (target && homeSections.none { it.kind == MediaSectionKind.FAVORITES }) homeSections + MediaSection("favorites", "我的收藏", MediaSectionKind.FAVORITES, emptyList()) else homeSections).map { section ->
                                section.copy(
                                    items = section.items.map {
                                        if (it.id == item.id) it.copy(favorite = target) else it
                                    }.let { updated ->
                                        if (section.kind == MediaSectionKind.FAVORITES && !target) {
                                            updated.filterNot { it.id == item.id }
                                        } else if (section.kind == MediaSectionKind.FAVORITES && target) (listOf(item.copy(favorite = true)) + updated).distinctBy { it.id }
                                        else updated
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
            }.mapCatching { imported ->
                externalSourceStore.save(imported)
                syncProviders()
                update {
                    copy(
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
            }.mapCatching { imported ->
                externalSourceStore.save(imported)
                syncProviders()
                update {
                    copy(
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
        val imported = try { externalSourceStore.load(source.id) }
        catch (e: Exception) { showError(e); return }
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
                externalQuery = "",
                externalGroup = null,
                selectedExternalEntry = null,
                errorMessage = null,
            )
        }
        liveGuide.load(source, entries)
    }

    internal suspend fun externalCatalogs(sourceId: String): List<com.shadow.media.provider.MediaProvider> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            externalSourceStore.load(sourceId)?.let(externalSourceRepository::catalogSites).orEmpty()
                .mapNotNull { site -> providerRegistry.provider("tvbox:${site.id}") }
        }

    fun updateExternalQuery(value: String) = update { copy(externalQuery = value) }

    fun setExternalGroup(value: String?) = update { copy(externalGroup = value) }

    fun toggleLiveFavorite(channel: LiveChannel) {
        state.value.selectedExternalSource?.let { liveGuide.toggleFavorite(it, channel) }
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
        downloadUnified(UnifiedMediaItem(MediaKey(session.providerId, item.id), item.name, item.type, subtitle = item.seriesName))
    }
    fun downloadUnified(item: UnifiedMediaItem) {
        viewModelScope.launch {
            try {
                val kind = com.shadow.media.model.contentKind(item.type)
                require(kind !in setOf(com.shadow.media.model.ContentKind.FOLDER, com.shadow.media.model.ContentKind.SERIES, com.shadow.media.model.ContentKind.LIVE_CHANNEL)) { "请选择具体文件" }
                val asset = requireNotNull(library).addRemote(item, if (kind in setOf(com.shadow.media.model.ContentKind.MUSIC, com.shadow.media.model.ContentKind.AUDIOBOOK)) "audio" else "mp4")
                requireNotNull(offline).enqueue(asset)
                update { copy(errorMessage = "已加入离线任务，可在来源页的离线管理查看") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
        }
    }

    private suspend fun tryOfflineVideo(key: MediaKey, positionMs: Long): Boolean {
        val asset = library?.dao?.assetForKey(key.providerId, key.itemId)?.takeIf { it.kind in setOf("MOVIE", "EPISODE") } ?: return false
        if (com.shadow.media.library.LibraryResources.hasOfflineMedia?.invoke(asset) != true) return false
        showOfflineVideo(asset, positionMs)
        return true
    }
    fun continueLegacy(row: com.shadow.media.database.ContinueRow) {
        cancelContentRequests()
        contentRequest = viewModelScope.launch {
            val key = MediaKey(row.providerId, row.itemId)
            try {
                if (tryOfflineVideo(key, row.positionMs ?: 0)) return@launch
                val provider = requireNotNull(providerRegistry.provider(key.providerId)) { "来源已断开，请重新连接" }
                val item = provider.detail(key).item
                ensureActive()
                playUnifiedOnline(item.copy(progressMs = row.positionMs ?: 0))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { showError(e) }
        }
    }
    fun continueLibraryVideo(asset: com.shadow.media.database.LibraryAssetEntity) {
        viewModelScope.launch {
            val history = localMediaState.history("${asset.providerId}:${asset.itemId}")
            continueLegacy(com.shadow.media.database.ContinueRow("", null, asset.providerId, asset.itemId, asset.title, asset.kind,
                history?.takeUnless { it.completed }?.positionMs ?: 0, history?.durationMs, null, 0))
        }
    }
    fun playOfflineAsset(asset: com.shadow.media.database.LibraryAssetEntity) {
        cancelContentRequests()
        contentRequest = viewModelScope.launch {
            val history = localMediaState.history("${asset.providerId}:${asset.itemId}")
            showOfflineVideo(asset, history?.takeUnless { it.completed }?.positionMs ?: 0)
        }
    }
    private fun showOfflineVideo(asset: com.shadow.media.database.LibraryAssetEntity, positionMs: Long) = update {
        copy(screen = Screen.EXTERNAL_PLAYER, externalPlayerReturnScreen = when (screen) { Screen.PLAYER -> playerReturnScreen; Screen.EXTERNAL_PLAYER -> externalPlayerReturnScreen; else -> screen },
            selectedExternalEntry = ExternalMediaEntry(id = asset.itemId, sourceId = asset.providerId, title = asset.title,
                startPositionMs = positionMs, url = "shadow-cached://${asset.id}/resource.${asset.format}"), isLoading = false, errorMessage = null)
    }

    fun externalPlaybackFailed(entryId: String, positionMs: Long, playing: Boolean) {
        val snapshot = state.value
        val entry = snapshot.selectedExternalEntry?.takeIf { it.id == entryId } ?: return
        lineHealth?.record(entry, false)
        recordSitePlayback(entry, false, null)
        val channel = snapshot.liveChannels.firstOrNull { channel -> channel.streams.any { it.id == entry.id && it.sourceId == entry.sourceId } }
        val attempted = snapshot.externalAttemptedLines + com.shadow.media.model.liveLineKey(entry)
        val next = if (autoSwitchLines() && channel != null) com.shadow.media.model.chooseLiveLine(channel.streams, attempted,
            { lineHealth?.health(it) ?: com.shadow.media.model.LiveLineHealth() }, System.currentTimeMillis()) else null
        if (next != null) {
            update { copy(selectedExternalEntry = next.copy(playWhenReady = playing, playbackRevision = entry.playbackRevision + 1),
                externalAttemptedLines = attempted, errorMessage = null) }
        } else refreshExternalPlayback(positionMs, playing, automatic = true)
    }
    fun externalPlaybackHealthy(entry: ExternalMediaEntry, elapsed: Long) {
        lineHealth?.record(entry, true)
        recordSitePlayback(entry, true, elapsed)
    }
    internal fun siteResolveMs(source: String, site: String) = lineHealth?.resolveMs(source, site)

    private fun recordSitePlayback(entry: ExternalMediaEntry, success: Boolean, elapsed: Long?) {
        if (!entry.sourceId.startsWith("mdc-tvbox:")) return
        val site = entry.id.substringBefore(':')
        viewModelScope.launch { localMediaState.recordSiteResult(entry.sourceId, site, success, elapsed) }
    }
    fun liveLineDescription(entry: ExternalMediaEntry): String {
        val value = lineHealth?.health(entry) ?: return ""
        return if (value.failures + value.successes == 0) "未测试" else "成功 ${value.successes} · 失败 ${value.failures}"
    }

    fun refreshExternalPlayback(positionMs: Long, playWhenReady: Boolean, automatic: Boolean = false) {
        val entry = state.value.selectedExternalEntry ?: return
        val provider = providerRegistry.provider(entry.sourceId) ?: providerRegistry.provider("external:" + entry.sourceId)
        if (automatic && entry.refreshAttempts >= 1 && (provider !is MdcTvBoxProvider || !autoSwitchLines())) return
        val mdc = mdcPlayback?.takeIf { it.entryId == entry.id && it.provider.descriptor.id == entry.sourceId }
        if (mdc != null) {
            playbackRequest?.cancel()
            playbackRequest = viewModelScope.launch {
                try {
                    val plan = mdc.provider.plan(mdc.key, online = true)
                    val source = requireNotNull(selectMdcRecoverySource(plan, mdc.versionId, mdc.sourceId)) {
                        "此版本已无可用线路"
                    }
                    ensureActive()
                    val subtitles = mdc.provider.workSubtitles(mdc.key, plan)
                    ensureActive()
                    if (state.value.screen == Screen.EXTERNAL_PLAYER && state.value.selectedExternalEntry == entry) {
                        update { copy(selectedExternalEntry = entry.copy(
                            url = source.getString("url"), startPositionMs = positionMs, subtitles = subtitles,
                            mimeType = source.optString("mime_type").takeUnless { it.isBlank() || it == "null" },
                            playWhenReady = playWhenReady, playbackRevision = entry.playbackRevision + 1,
                            refreshAttempts = if (automatic) entry.refreshAttempts + 1 else 0,
                        ), errorMessage = null) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (state.value.selectedExternalEntry == entry) update {
                        copy(errorMessage = "此版本线路恢复失败，可返回作品选择其他版本")
                    }
                }
            }
            return
        }
        if (provider == null) return
        playbackRequest?.cancel()
        playbackRequest = viewModelScope.launch {
            try {
                val current = MediaKey(provider.descriptor.id, entry.playbackItemId ?: entry.id)
                val request = UnifiedPlaybackRequest(current, positionMs, refresh = true)
                val episode = if (provider is MdcTvBoxProvider) {
                    val failed = if (automatic) entry.failedPlaybackItems + current.itemId else emptySet()
                    provider.resolveEpisode(request, failed, autoSwitchLines(),
                        retryCurrent = automatic && entry.refreshAttempts == 0)
                } else null
                val candidate = episode?.candidate ?: requireNotNull(provider.resolve(request).firstOrNull()) { "来源没有返回播放线路" }
                ensureActive()
                if (state.value.screen == Screen.EXTERNAL_PLAYER && state.value.selectedExternalEntry == entry) update {
                    copy(selectedExternalEntry = entry.copy(url = candidate.url, requestHeaders = candidate.requiredHeaders,
                        playbackItemId = episode?.key?.itemId ?: current.itemId, failedPlaybackItems = episode?.failed.orEmpty(),
                        credentialOrigin = candidate.credentialOrigin, subtitles = candidate.subtitles, startPositionMs = positionMs,
                        mimeType = candidate.mimeType ?: entry.mimeType,
                        playWhenReady = playWhenReady, playbackRevision = entry.playbackRevision + 1,
                        refreshAttempts = if (automatic) entry.refreshAttempts + 1 else 0), errorMessage = null)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (state.value.selectedExternalEntry == entry) update { copy(errorMessage = "重新解析来源失败，请检查连接或手动切换线路") } }
        }
    }

    fun playExternalEntry(entry: ExternalMediaEntry) = navigate {
        copy(
            screen = Screen.EXTERNAL_PLAYER,
            externalPlayerReturnScreen = Screen.EXTERNAL_ITEMS,
            selectedExternalEntry = entry,
            externalAttemptedLines = emptySet(),
            errorMessage = null,
        )
    }

    fun removeExternalSource(sourceId: String) {
        try { externalSourceStore.remove(sourceId) }
        catch (e: Exception) { showError(e); return }
        syncProviders()
    }

    fun refreshProviderConnections() = syncProviders()

    private fun syncProviders(mdc: MdcDiscovery = knownMdcConnections()) {
        val storedNetworkConnections = networkStorage.loadNetworkStorageConnections().orEmpty()
        val subscriptions = runCatching { externalSourceStore.loadImports() }
        val providers = buildList {
            addAll(nativeProviders())
            mdc.connections.forEach {
                val provider = mdcProvider(it)
                add(provider)
                add(MdcTvBoxProvider(provider))
            }
            library?.let { add(com.shadow.media.library.LibraryMediaProvider(it)) }
            sessionStore.loadAll().distinctBy { it.providerId }.forEach { add(EmbyMediaProvider(it, repository)) }
            storedNetworkConnections.forEach { connection ->
                add(networkStorageRepository.provider(connection))
            }
            var catalogCount = 0
            subscriptions.getOrNull().orEmpty().forEach { imported ->
                val summary = imported.summary
                val entries = runCatching { externalSourceRepository.entries(imported) }.getOrDefault(emptyList())
                if (entries.isNotEmpty()) add(LiveMediaProvider(summary, entries))
                externalSourceRepository.catalogSites(imported).forEach { site ->
                    if (catalogCount < MAX_HTTP_PROVIDERS) {
                        catalogCount++
                        TvBoxHttpMediaProvider(site, externalClient).let { add(library?.cacheProvider(it) ?: it) }
                    }
                }
            }
        }
        networkStorage.setConnections(storedNetworkConnections)
        integrations.readConnections()
        providerRegistry.replace(providers)
        update {
            copy(
                externalSources = subscriptions.getOrNull()?.map { it.summary } ?: externalSources,
                externalSourceReadError = subscriptions.exceptionOrNull()?.message,
                providerDescriptors = providers.map { it.descriptor },
            )
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
                liveGuide.clear()
                copy(
                    screen = Screen.SOURCES,
                    selectedExternalSource = null,
                    externalEntries = emptyList(),
                    liveChannels = emptyList(),
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
            Screen.ITEMS -> if (state.value.wallFolders.isNotEmpty()) {
                update { copy(wallFolders = wallFolders.dropLast(1), wallSearch = "") }
                loadWall(reset = true)
            } else update {
                copy(
                    screen = Screen.LIBRARIES,
                    selectedLibrary = null,
                    wallItems = emptyList(),
                    items = emptyList(),
                )
            }
            Screen.NETWORK_STORAGES -> update { copy(screen = networkStorageReturnScreen, errorMessage = null) }
            Screen.LIBRARIES, Screen.SOURCES, Screen.DISCOVER, Screen.SETTINGS, Screen.INTEGRATIONS,
            Screen.INSIGHTS, Screen.MDC -> showHome()
            Screen.HOME -> showServers()
            Screen.SERVERS -> Unit
        }
    }

    fun logout() {
        cancelContentRequests()
        playbackRequest?.cancel()
        deleteRequest?.cancel()
        val session = state.value.session ?: return
        try { sessionStore.remove(session) }
        catch (e: Exception) { showError(e); return }
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
            isLoading = true,
        )
        viewModelScope.launch {
            try { playbackOutbox.flush(session) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (state.value.session == session) showError(e) }
        }
        favoriteObservation?.cancel()
        favoriteObservation = viewModelScope.launch {
            repository.favoriteStates(session).collect { favorites ->
                if (state.value.session != session) return@collect
                fun MediaItem.localFavorite() = favorites[id]?.let { copy(favorite = it) } ?: this
                update { copy(items = items.map { it.localFavorite() }, wallItems = wallItems.map { it.localFavorite() },
                    detailEpisodes = detailEpisodes.map { it.localFavorite() }, selectedSeries = selectedSeries?.localFavorite(),
                    homeSections = homeSections.map { section -> section.copy(items = section.items.map { it.localFavorite() }.let { values -> if (section.kind == MediaSectionKind.FAVORITES) values.filter { it.favorite } else values }) }) }
            }
        }
        viewModelScope.launch { loadLibraries(session) }
    }

    private suspend fun loadLibraries(session: EmbySession) {
        repository.cachedLibraries(session)?.let { libraries ->
            val sections = repository.cachedHome(session, libraries.map(MediaLibrary::id)).orEmpty()
            if (state.value.session == session) update { copy(libraries = libraries, homeSections = sections.withRecommendations(), isLoading = false) }
        }
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
        resolutionRecovery.ready(); resolutionRetry = null
        unifiedPageRequest++
        contentRequest?.cancel()
        playbackRequest?.cancel()
        liveGuide.cancel()
        update { copy(isLoading = false, isLoadingMore = false) }
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
                        nativeProviders = { container.catalogs.musicProviders() + container.localVideos },
                        prepareAccounts = { container.accountScopesReady.await() },
                        lineHealth = container.lineHealth,
                        autoSwitchLines = { container.videoPreferences.settings.value.autoSwitchLines },
                        playbackNetwork = container.playbackNetwork,
                        retryLimit = { container.videoPreferences.settings.value.retries },
                        mdcConnections = container.mdcConnections::load,
                        sharedMdcConnections = container.sharedMdc::connections,
                        discoverSharedMdc = container.sharedMdc::discover,
                        sharedMdcToken = container.sharedMdc::token,
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
    else -> setOf("Folder", "CollectionFolder", "Movie", "Episode", "Video", "Series", "BoxSet")
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


private data class MdcPlaybackContext(
    val provider: MdcMediaProvider,
    val key: MediaKey,
    val entryId: String,
    val versionId: String,
    val sourceId: String,
)
