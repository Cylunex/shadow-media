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
import kotlinx.coroutines.launch
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.MediaItem
import top.cylunex.shadowmedia.model.MediaLibrary
import top.cylunex.shadowmedia.model.PlaybackPlan
import top.cylunex.shadowmedia.model.embyTicksToMilliseconds
import top.cylunex.shadowmedia.network.EmbyRepository
import top.cylunex.shadowmedia.network.LoginRequest
import top.cylunex.shadowmedia.network.PlaybackOutbox
import top.cylunex.shadowmedia.network.SessionStore

enum class Screen { SERVERS, LOGIN, LIBRARIES, ITEMS, PLAYER }

data class MainUiState(
    val screen: Screen = Screen.LOGIN,
    val serverUrl: String = "",
    val userName: String = "",
    val password: String = "",
    val allowInsecureHttp: Boolean = false,
    val savedSessions: List<EmbySession> = emptyList(),
    val session: EmbySession? = null,
    val libraries: List<MediaLibrary> = emptyList(),
    val lastFeedLibraryId: String? = null,
    val selectedLibrary: MediaLibrary? = null,
    val items: List<MediaItem> = emptyList(),
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
) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    private var playbackRequest: Job? = null
    private var deleteRequest: Job? = null
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

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
                    screen = Screen.LIBRARIES,
                    isLoading = true,
                )
                viewModelScope.launch { playbackOutbox.flush(session) }
                loadLibraries(session)
            }.onFailure(::showError)
        }
    }

    fun selectLibrary(library: MediaLibrary) = loadLibrary(library, autoPlay = false)

    fun resumeLastFeed() {
        val library = state.value.libraries.firstOrNull { it.id == state.value.lastFeedLibraryId } ?: return
        loadLibrary(library, autoPlay = true)
    }

    private fun loadLibrary(library: MediaLibrary, autoPlay: Boolean) {
        val session = state.value.session ?: return
        viewModelScope.launch {
            update {
                copy(
                    selectedLibrary = library,
                    items = emptyList(),
                    screen = Screen.ITEMS,
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

    fun play(item: MediaItem) {
        val index = state.value.items.indexOfFirst { it.id == item.id }
        if (index >= 0) playAt(index)
    }

    fun playAt(index: Int) {
        val snapshot = state.value
        val session = snapshot.session ?: return
        val item = snapshot.items.getOrNull(index) ?: return
        if (
            snapshot.screen == Screen.PLAYER && snapshot.currentIndex == index &&
            (snapshot.playbackPlan?.itemId == item.id || snapshot.isLoading)
        ) return
        snapshot.selectedLibrary?.let { feedSessionStore.markCurrent(session, it.id, item.id, index) }
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
                val nextIndex = snapshot.currentIndex.coerceAtMost((remaining.size - 1).coerceAtLeast(0))
                snapshot.selectedLibrary?.let { feedSessionStore.removeItem(session, it.id, item.id) }
                if (removedActiveItem) playbackRequest?.cancel()
                update {
                    copy(
                        screen = if (removedActiveItem && remaining.isEmpty()) Screen.ITEMS else screen,
                        items = remaining,
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
                    screen = Screen.ITEMS,
                    playbackPlan = null,
                    selectedItem = null,
                    isLoading = false,
                    errorMessage = null,
                )
            }
            Screen.ITEMS -> update {
                copy(screen = Screen.LIBRARIES, selectedLibrary = null, items = emptyList())
            }
            Screen.LIBRARIES -> showServers()
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
            screen = Screen.LIBRARIES,
            serverUrl = session.serverUrl,
            userName = session.userName,
            allowInsecureHttp = session.allowInsecureHttp,
            savedSessions = saved,
            session = session,
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
            }
            .onFailure(::showError)
    }

    private fun showError(error: Throwable) {
        update { copy(isLoading = false, errorMessage = error.message ?: "发生未知错误") }
    }

    private fun update(transform: MainUiState.() -> MainUiState) = mutableState.update(transform)

    companion object {
        private const val MAX_PLAYBACK_REFRESHES = 1

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(
                        container.embyRepository,
                        container.sessionStore,
                        container.feedSessionStore,
                        container.playbackOutbox,
                    ) as T
            }
    }
}
