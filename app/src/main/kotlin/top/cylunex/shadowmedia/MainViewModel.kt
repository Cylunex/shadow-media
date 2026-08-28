package top.cylunex.shadowmedia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.BrowseRequest
import top.cylunex.shadowmedia.model.ExternalSourceSummary
import top.cylunex.shadowmedia.model.ExternalMediaEntry
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

enum class Screen {
    SERVERS, LOGIN, HOME, LIBRARIES, ITEMS, DETAIL, SOURCES, EXTERNAL_ITEMS, EXTERNAL_PLAYER, SETTINGS, PLAYER
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
    val selectedExternalEntry: ExternalMediaEntry? = null,
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
) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    private var playbackRequest: Job? = null
    private var deleteRequest: Job? = null
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()
    val featureFlags: StateFlow<Map<FeatureId, Boolean>> = localMediaState.featureFlags()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            FeatureId.entries.associateWith { it.defaultEnabled },
        )

    init {
        val saved = sessionStore.loadAll()
        val active = sessionStore.load()
        if (active != null) restoreSession(active, saved) else {
            mutableState.value = MainUiState(
                screen = if (saved.isEmpty()) Screen.LOGIN else Screen.SERVERS,
                savedSessions = saved,
            )
        }
    }

    fun updateServerUrl(value: String) = update { copy(serverUrl = value, errorMessage = null) }
    fun updateUserName(value: String) = update { copy(userName = value, errorMessage = null) }
    fun updatePassword(value: String) = update { copy(password = value, errorMessage = null) }
    fun updateAllowInsecure(value: Boolean) = update { copy(allowInsecureHttp = value, errorMessage = null) }

    fun showServers() {
        playbackRequest?.cancel()
        deleteRequest?.cancel()
        val saved = sessionStore.loadAll()
        mutableState.value = MainUiState(
            screen = if (saved.isEmpty()) Screen.LOGIN else Screen.SERVERS,
            savedSessions = saved,
        )
    }

    fun addServer() {
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
        sessionStore.remove(session)
        val remaining = sessionStore.loadAll()
        mutableState.value = MainUiState(
            screen = if (remaining.isEmpty()) Screen.LOGIN else Screen.SERVERS,
            savedSessions = remaining,
        )
        viewModelScope.launch {
            playbackOutbox.discard(session)
            runCatching { repository.logout(session) }
        }
    }

    fun login() {
        val snapshot = state.value
        if (snapshot.isLoading) return
        viewModelScope.launch {
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
                viewModelScope.launch { playbackOutbox.flush(session) }
                loadLibraries(session)
            }.onFailure(::showError)
        }
    }

    fun showHome() = update {
        copy(
            screen = Screen.HOME,
            selectedLibrary = null,
            selectedSeries = null,
            detailEpisodes = emptyList(),
            items = emptyList(),
            wallItems = emptyList(),
            feedMode = false,
            errorMessage = null,
        )
    }

    fun showLibraries() = update { copy(screen = Screen.LIBRARIES, errorMessage = null) }

    fun showSources() = update {
        copy(
            screen = Screen.SOURCES,
            externalSources = externalSourceStore.loadAll(),
            sourceUrl = "",
            errorMessage = null,
        )
    }

    fun showSettings() = update { copy(screen = Screen.SETTINGS, errorMessage = null) }

    fun setFeatureEnabled(feature: FeatureId, enabled: Boolean) {
        viewModelScope.launch { localMediaState.setFeatureEnabled(feature, enabled) }
    }

    fun selectLibrary(library: MediaLibrary) {
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
        viewModelScope.launch {
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
                .onFailure(::showError)
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
        if (!state.value.wallHasMore || state.value.isLoadingMore) return
        loadWall(reset = false)
    }

    private fun loadWall(reset: Boolean) {
        val snapshot = state.value
        val session = snapshot.session ?: return
        val library = snapshot.selectedLibrary ?: return
        viewModelScope.launch {
            update {
                if (reset) copy(isLoading = true, wallItems = emptyList(), errorMessage = null)
                else copy(isLoadingMore = true, errorMessage = null)
            }
            val startIndex = if (reset) 0 else state.value.wallItems.size
            runCatching {
                repository.browse(
                    session,
                    BrowseRequest(
                        parentId = library.id,
                        includeItemTypes = library.wallItemTypes(),
                        searchTerm = state.value.wallSearch,
                        sort = state.value.wallSort,
                        descending = state.value.wallSort != MediaSort.NAME,
                        filter = state.value.wallFilter,
                        startIndex = startIndex,
                        limit = WALL_PAGE_SIZE,
                    )
                )
            }.onSuccess { page ->
                update {
                    copy(
                        wallItems = if (reset) page.items else (wallItems + page.items).distinctBy(MediaItem::id),
                        wallTotalCount = page.totalRecordCount,
                        wallHasMore = page.hasMore && wallSort != MediaSort.RANDOM,
                        isLoading = false,
                        isLoadingMore = false,
                    )
                }
            }.onFailure {
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
        viewModelScope.launch {
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
                .onSuccess { episodes -> update { copy(detailEpisodes = episodes, isLoading = false) } }
                .onFailure(::showError)
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
        update {
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
        val session = state.value.session ?: return
        viewModelScope.launch {
            playbackOutbox.submit(
                session,
                PlaybackReport(
                    itemId = plan.itemId,
                    mediaSourceId = plan.mediaSourceId,
                    playSessionId = plan.playSessionId,
                    positionTicks = positionMs.coerceAtLeast(0L).millisecondsToEmbyTicks(),
                    isPaused = event == PlaybackEvent.STOPPED,
                    canSeek = true,
                    event = event,
                    playMethod = plan.primary.method,
                ),
            )
        }
    }

    private fun resolvePlayback(
        session: EmbySession,
        item: MediaItem,
        index: Int,
        startPositionMs: Long,
        refreshAttempts: Int,
    ) {
        playbackRequest?.cancel()
        update {
            copy(
                screen = Screen.PLAYER,
                selectedItem = item,
                currentIndex = index,
                playbackPlan = null,
                playbackStartPositionMs = startPositionMs,
                playbackRefreshAttempts = refreshAttempts,
                isLoading = true,
                errorMessage = if (refreshAttempts > 0) "播放地址失效，正在重新解析…" else null,
            )
        }
        playbackRequest = viewModelScope.launch {
            try {
                val plan = repository.playbackPlan(session, item.id)
                if (state.value.selectedItem?.id == item.id) {
                    update { copy(playbackPlan = plan, isLoading = false, errorMessage = null) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (state.value.selectedItem?.id == item.id) showError(error)
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
                .onFailure(::showError)
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
                selectedExternalEntry = null,
                errorMessage = null,
            )
        }
    }

    fun playExternalEntry(entry: ExternalMediaEntry) = update {
        copy(screen = Screen.EXTERNAL_PLAYER, selectedExternalEntry = entry, errorMessage = null)
    }

    fun removeExternalSource(sourceId: String) {
        externalSourceStore.remove(sourceId)
        update { copy(externalSources = externalSourceStore.loadAll()) }
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
                val snapshot = state.value
                val removedActiveItem = snapshot.screen == Screen.PLAYER && snapshot.selectedItem?.id == item.id
                val remaining = snapshot.items.filterNot { it.id == item.id }
                val remainingWallItems = snapshot.wallItems.filterNot { it.id == item.id }
                val nextIndex = snapshot.currentIndex.coerceAtMost((remaining.size - 1).coerceAtLeast(0))
                snapshot.selectedLibrary?.let { feedSessionStore.removeItem(session, it.id, item.id) }
                if (removedActiveItem) playbackRequest?.cancel()
                update {
                    copy(
                        screen = if (removedActiveItem && remaining.isEmpty()) Screen.ITEMS else screen,
                        items = remaining,
                        wallItems = remainingWallItems,
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
        playbackRequest?.cancel()
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
                copy(screen = Screen.EXTERNAL_ITEMS, selectedExternalEntry = null, errorMessage = null)
            }
            Screen.EXTERNAL_ITEMS -> update {
                copy(
                    screen = Screen.SOURCES,
                    selectedExternalSource = null,
                    externalEntries = emptyList(),
                    selectedExternalEntry = null,
                    errorMessage = null,
                )
            }
            Screen.DETAIL -> update {
                copy(screen = Screen.ITEMS, selectedSeries = null, detailEpisodes = emptyList())
            }
            Screen.ITEMS -> update {
                copy(
                    screen = Screen.LIBRARIES,
                    selectedLibrary = null,
                    wallItems = emptyList(),
                    items = emptyList(),
                )
            }
            Screen.LIBRARIES, Screen.SOURCES, Screen.SETTINGS -> showHome()
            Screen.HOME -> showServers()
            Screen.SERVERS -> Unit
        }
    }

    fun logout() {
        playbackRequest?.cancel()
        deleteRequest?.cancel()
        val session = state.value.session ?: return
        sessionStore.remove(session)
        val remaining = sessionStore.loadAll()
        mutableState.value = MainUiState(
            screen = if (remaining.isEmpty()) Screen.LOGIN else Screen.SERVERS,
            savedSessions = remaining,
        )
        viewModelScope.launch {
            playbackOutbox.discard(session)
            runCatching { repository.logout(session) }
        }
    }

    private fun restoreSession(session: EmbySession, saved: List<EmbySession>) {
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
        viewModelScope.launch { playbackOutbox.flush(session) }
        viewModelScope.launch { loadLibraries(session) }
    }

    private suspend fun loadLibraries(session: EmbySession) {
        runCatching { repository.libraries(session) }
            .onSuccess { libraries ->
                val lastFeed = feedSessionStore.lastFor(session)
                update {
                    copy(
                        libraries = libraries,
                        lastFeedLibraryId = lastFeed?.libraryId?.takeIf { id -> libraries.any { it.id == id } },
                        isLoading = false,
                    )
                }
                runCatching { repository.home(session, libraries.map(MediaLibrary::id)) }
                    .onSuccess { sections -> update { copy(homeSections = sections) } }
                    .onFailure { update { copy(errorMessage = "首页加载不完整：${it.message ?: "未知错误"}") } }
            }
            .onFailure(::showError)
    }

    private fun showError(error: Throwable) {
        update { copy(isLoading = false, errorMessage = error.message ?: "发生未知错误") }
    }

    private fun update(transform: MainUiState.() -> MainUiState) = mutableState.update(transform)

    companion object {
        private const val MAX_PLAYBACK_REFRESHES = 1
        private const val WALL_PAGE_SIZE = 60

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
                    ) as T
            }
    }
}

private fun MediaLibrary.wallItemTypes(): Set<String> = when (collectionType?.lowercase()) {
    "tvshows" -> setOf("Series")
    "movies" -> setOf("Movie")
    "boxsets" -> setOf("BoxSet")
    "music" -> setOf("MusicAlbum", "MusicVideo")
    else -> setOf("Movie", "Episode", "Video", "Series")
}
