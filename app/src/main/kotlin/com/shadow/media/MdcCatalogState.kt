package com.shadow.media

import androidx.compose.runtime.*
import com.shadow.media.model.UnifiedMediaItem
import kotlinx.coroutines.*

@Stable
internal class MdcCatalogState(val scope: CoroutineScope, initialError: String?) {
    var playlists by mutableStateOf(emptyList<MdcPlaylist>())
    var playlistWorks by mutableStateOf(emptyList<Pair<String, String>>())
    var people by mutableStateOf(emptyList<MdcPerson>())
    var actorProfile by mutableStateOf<org.json.JSONObject?>(null)
    var total by mutableStateOf(0)
    var facetData by mutableStateOf(org.json.JSONObject())
    var rows by mutableStateOf(emptyList<UnifiedMediaItem>())
    var detail by mutableStateOf<UnifiedMediaItem?>(null)
    var cursor by mutableStateOf<String?>(null)
    var error by mutableStateOf(initialError)
    var busy by mutableStateOf(false)
    var job: Job? = null
    var facetJob: Job? = null
    var actorJob: Job? = null
    var facetError by mutableStateOf<String?>(null)
    var actorError by mutableStateOf<String?>(null)
    var loadedFilters by mutableStateOf<org.json.JSONObject?>(null)
    var peopleCursor by mutableStateOf<String?>(null)
    var peopleError by mutableStateOf<String?>(null)
    var peopleBusy by mutableStateOf(false)
    var peopleGeneration by mutableIntStateOf(0)
    var peoplePageJob: Job? = null

    fun close() {
        job?.cancel()
        facetJob?.cancel()
        actorJob?.cancel()
        peoplePageJob?.cancel()
    }

    fun loadFacets(p: MdcMediaProvider, filters: org.json.JSONObject) {
        facetJob?.cancel()
        facetError = null
        facetJob = scope.launch {
            try {
                val result = p.facets(filters)
                ensureActive()
                facetData = result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { facetError = e.message ?: "筛选统计暂不可用" }
        }
    }

    fun loadActor(p: MdcMediaProvider, id: String) {
        actorJob?.cancel()
        actorError = null
        actorProfile = null
        if (id.isBlank()) return
        actorJob = scope.launch {
            try {
                val result = p.person(id)
                ensureActive()
                actorProfile = result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { actorError = e.message ?: "演员资料暂不可用" }
        }
    }

    suspend fun loadPeoplePage(p: MdcMediaProvider, term: String, cursor: String?, generation: Int) {
        peopleBusy = true
        peopleError = null
        try {
            val page = p.peoplePage(term, cursor)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (generation != peopleGeneration) return
            people = if (cursor == null) page.items else (people + page.items).distinctBy { it.id }
            peopleCursor = page.next
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (generation == peopleGeneration) peopleError = e.message ?: "演员目录读取失败" }
        finally { if (generation == peopleGeneration) peopleBusy = false }
    }
}
