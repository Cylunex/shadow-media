package top.cylunex.shadowmedia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.MediaItem
import top.cylunex.shadowmedia.model.MediaLibrary
import top.cylunex.shadowmedia.model.PlaybackPlan
import top.cylunex.shadowmedia.network.EmbyRepository
import top.cylunex.shadowmedia.network.LoginRequest
import top.cylunex.shadowmedia.network.SessionStore

enum class Screen { LOGIN, LIBRARIES, ITEMS, PLAYER }

data class MainUiState(
    val screen: Screen = Screen.LOGIN,
    val serverUrl: String = "",
    val userName: String = "",
    val password: String = "",
    val allowInsecureHttp: Boolean = false,
    val session: EmbySession? = null,
    val libraries: List<MediaLibrary> = emptyList(),
    val selectedLibrary: MediaLibrary? = null,
    val items: List<MediaItem> = emptyList(),
    val selectedItem: MediaItem? = null,
    val playbackPlan: PlaybackPlan? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class MainViewModel(
    private val repository: EmbyRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    init {
        sessionStore.load()?.let(::restoreSession)
    }

    fun updateServerUrl(value: String) = update { copy(serverUrl = value, errorMessage = null) }
    fun updateUserName(value: String) = update { copy(userName = value, errorMessage = null) }
    fun updatePassword(value: String) = update { copy(password = value, errorMessage = null) }
    fun updateAllowInsecure(value: Boolean) = update { copy(allowInsecureHttp = value, errorMessage = null) }

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
                mutableState.value = state.value.copy(
                    session = session,
                    password = "",
                    screen = Screen.LIBRARIES,
                    isLoading = true,
                )
                loadLibraries(session)
            }.onFailure(::showError)
        }
    }

    fun selectLibrary(library: MediaLibrary) {
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
                .onSuccess { items -> update { copy(items = items, isLoading = false) } }
                .onFailure(::showError)
        }
    }

    fun play(item: MediaItem) {
        val session = state.value.session ?: return
        viewModelScope.launch {
            update { copy(selectedItem = item, isLoading = true, errorMessage = null) }
            runCatching { repository.playbackPlan(session, item.id) }
                .onSuccess { plan ->
                    update { copy(playbackPlan = plan, screen = Screen.PLAYER, isLoading = false) }
                }
                .onFailure(::showError)
        }
    }

    fun back() {
        update {
            when (screen) {
                Screen.PLAYER -> copy(screen = Screen.ITEMS, playbackPlan = null, selectedItem = null)
                Screen.ITEMS -> copy(screen = Screen.LIBRARIES, selectedLibrary = null, items = emptyList())
                else -> this
            }
        }
    }

    fun logout() {
        val session = state.value.session
        sessionStore.clear()
        mutableState.value = MainUiState(serverUrl = session?.serverUrl.orEmpty())
        if (session != null) {
            viewModelScope.launch { runCatching { repository.logout(session) } }
        }
    }

    private fun restoreSession(session: EmbySession) {
        mutableState.value = MainUiState(
            screen = Screen.LIBRARIES,
            serverUrl = session.serverUrl,
            userName = session.userName,
            allowInsecureHttp = session.allowInsecureHttp,
            session = session,
            isLoading = true,
        )
        viewModelScope.launch { loadLibraries(session) }
    }

    private suspend fun loadLibraries(session: EmbySession) {
        runCatching { repository.libraries(session) }
            .onSuccess { libraries -> update { copy(libraries = libraries, isLoading = false) } }
            .onFailure(::showError)
    }

    private fun showError(error: Throwable) {
        update { copy(isLoading = false, errorMessage = error.message ?: "发生未知错误") }
    }

    private fun update(transform: MainUiState.() -> MainUiState) = mutableState.update(transform)

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(container.embyRepository, container.sessionStore) as T
            }
    }
}
