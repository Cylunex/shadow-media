package top.cylunex.shadowmedia

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import top.cylunex.shadowmedia.database.*
import top.cylunex.shadowmedia.model.*
import top.cylunex.shadowmedia.provider.ProviderBrowseRequest
import java.util.UUID

data class MusicFilter(val query: String = "", val album: String = "", val artist: String = "", val folder: String = "",
    val favorite: Boolean = false, val recent: Boolean = false, val playlist: String = "")
data class MusicSource(val providerId: String, val parentId: String, val title: String)

@OptIn(ExperimentalCoroutinesApi::class)
class MusicViewModel(private val container: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(MusicFilter())
    val message = MutableStateFlow<String?>(null)
    val working = MutableStateFlow(false)
    val sources = MutableStateFlow<List<MusicSource>>(emptyList())
    private var job: Job? = null
    val snapshotState = container.music.snapshots.scopes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val dao = container.music.dao
    val tracks = filter.flatMapLatest { f -> Pager(PagingConfig(60, enablePlaceholders = false)) {
        dao.page("%" + f.query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", f.album, f.artist, f.folder, f.favorite, f.recent, f.playlist)
    }.flow }.cachedIn(viewModelScope)
    val albums = Pager(PagingConfig(40)) { dao.albums() }.flow.cachedIn(viewModelScope)
    val artists = Pager(PagingConfig(40)) { dao.artists() }.flow.cachedIn(viewModelScope)
    val folders = Pager(PagingConfig(40)) { dao.folders() }.flow.cachedIn(viewModelScope)
    val playlists = dao.playlists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun work(block: suspend () -> Unit) {
        if (working.value) return
        job = viewModelScope.launch {
            working.value = true
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message?.takeIf { !it.contains("http", true) } ?: "操作失败，请检查来源连接或文件授权" }
            finally { working.value = false }
        }
    }
    suspend fun queueIds(): List<String> {
        val f = filter.value
        if (f.playlist.isNotBlank()) return playlistItems(f.playlist).mapNotNull { it.second?.takeIf { a -> a.kind == "MUSIC" }?.id }.take(2000)
        val source = dao.page("%" + f.query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", f.album, f.artist, f.folder, f.favorite, f.recent, "")
        return try {
            val result = mutableListOf<String>()
            var offset: Int? = 0
            while (offset != null && result.size < 2000) {
                when (val page = source.load(PagingSource.LoadParams.Refresh(offset, minOf(200, 2000 - result.size), false))) {
                    is PagingSource.LoadResult.Page -> { result += page.data.map { it.asset.id }; offset = page.nextKey }
                    is PagingSource.LoadResult.Error -> throw page.throwable
                    else -> break
                }
            }
            result
        } finally { source.invalidate() }
    }
    fun cancel() { job?.cancel() }
    fun importFiles(uris: List<Uri>) = work {
        var imported = 0
        for (uri in uris) { container.music.importDocument(uri); imported++ }
        message.value = "已导入 $imported 首歌曲"
    }
    fun importFolder(uri: Uri) = work {
        val count = container.music.importDirectory(uri) { message.value = "已索引 $it 首歌曲" }
        message.value = "目录导入完成，共 $count 首歌曲"
    }
    fun loadSources() = work {
        val result = mutableListOf<MusicSource>()
        for (session in container.sessionStore.loadAll()) {
            for (library in container.embyRepository.libraries(session).filter { it.collectionType.equals("music", true) }) {
                result += MusicSource("emby:${session.serverId}:${session.userId}", library.id, "${session.userName} · ${library.name}")
            }
        }
        sources.value = result
        if (result.isEmpty()) message.value = "请先在来源中连接含音乐库的 Emby 账号"
    }
    fun refresh(source: MusicSource) = work {
        val provider = container.providerRegistry.provider(source.providerId) ?: error("来源已断开，请重新连接")
        val scopeId = scopedContentId(source.providerId, source.parentId)
        val generation = UUID.randomUUID().toString()
        val snapshots = container.music.snapshots
        snapshots.begin(scopeId, generation)
        try {
            top.cylunex.shadowmedia.library.ResourceScheduler.process.run(top.cylunex.shadowmedia.library.ResourcePriority.INDEX) {
                var token: String? = null
                val visited = mutableSetOf<String?>()
                val ids = mutableSetOf<String>()
                var expected: Int? = null
                do {
                    currentCoroutineContext().ensureActive()
                    check(container.backgroundResourcesAllowed()) { "后台索引已暂停，请检查网络和设备状态" }
                    check(visited.add(token)) { "来源重复返回相同分页，旧目录已保留" }
                    val page = provider.browse(ProviderBrowseRequest(MediaKey(source.providerId, source.parentId), type = "Audio", pageToken = token, pageSize = 200))
                    check(page.items.isNotEmpty()) { "来源返回空页，未删除旧目录" }
                    expected = page.totalCount ?: expected
                    val assets = page.items.map { container.music.importRemote(it).id }
                    snapshots.append(scopeId, generation, assets)
                    ids += assets
                    token = page.nextPageToken
                    message.value = "已索引 ${ids.size} 首歌曲"
                } while (token != null)
                check(expected == null || ids.size == expected) { "目录分页未完整返回，旧记录已保留" }
                snapshots.finish(scopeId, generation, System.currentTimeMillis(), true)
                sources.value = emptyList()
                message.value = "音乐库索引完成，共 ${ids.size} 首歌曲"
            }
        } catch (e: Exception) {
            withContext(NonCancellable) { snapshots.finish(scopeId, generation, System.currentTimeMillis(), false, "目录可能过期，已保留本机快照") }
            throw e
        }
    }
    fun favorite(row: MusicRow) { viewModelScope.launch { container.library.dao.favorite(row.asset.id, !row.asset.favorite) } }
    fun playlist(title: String) = work {
        require(title.isNotBlank())
        dao.putPlaylist(MusicPlaylistEntity(UUID.randomUUID().toString(), title.trim().take(120), System.currentTimeMillis()))
    }
    fun addToPlaylist(playlist: String, asset: String) = work { dao.append(playlist, listOf(asset)); message.value = "已加入歌单" }
    suspend fun playlistItems(id: String): List<Pair<MusicPlaylistEntryEntity, LibraryAssetEntity?>> {
        val entries = dao.entries(id)
        val assets = entries.map { it.assetId }.distinct().chunked(400).flatMap { container.library.dao.assetsByIds(it) }.associateBy { it.id }
        return entries.map { it to assets[it.assetId] }
    }
}
