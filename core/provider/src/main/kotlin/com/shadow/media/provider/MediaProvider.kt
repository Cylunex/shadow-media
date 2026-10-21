package com.shadow.media.provider

import kotlinx.coroutines.flow.Flow
import com.shadow.media.model.MediaDetail
import com.shadow.media.model.MediaKey
import com.shadow.media.model.ProviderDescriptor
import com.shadow.media.model.UnifiedMediaItem
import com.shadow.media.model.UnifiedMediaPage
import com.shadow.media.model.UnifiedPlaybackRequest
import com.shadow.media.model.PlaybackCandidate

interface MediaProvider {
    val descriptor: ProviderDescriptor

    suspend fun home(): List<ProviderSection>

    suspend fun cachedBrowse(request: ProviderBrowseRequest): UnifiedMediaPage? = null

    suspend fun cachedDetail(key: MediaKey): MediaDetail? = null

    suspend fun browse(request: ProviderBrowseRequest): UnifiedMediaPage

    suspend fun search(request: ProviderSearchRequest): UnifiedMediaPage

    suspend fun detail(key: MediaKey): MediaDetail

    suspend fun resolve(request: UnifiedPlaybackRequest): List<PlaybackCandidate>
}

interface ProviderRegistry {
    val providers: Flow<List<MediaProvider>>

    fun provider(id: String): MediaProvider?
}

data class ProviderSection(
    val id: String,
    val title: String,
    val items: List<UnifiedMediaItem>,
)

data class ProviderBrowseRequest(
    val parentKey: MediaKey? = null,
    val type: String? = null,
    val genre: String? = null,
    val sort: String? = null,
    val pageToken: String? = null,
    val pageSize: Int = 60,
)

data class ProviderSearchRequest(
    val query: String,
    val type: String? = null,
    val pageToken: String? = null,
    val pageSize: Int = 60,
)

class InMemoryProviderRegistry(initial: List<MediaProvider> = emptyList()) : ProviderRegistry {
    private val mutableProviders = kotlinx.coroutines.flow.MutableStateFlow(initial.distinctBy { it.descriptor.id })
    override val providers: Flow<List<MediaProvider>> = mutableProviders

    override fun provider(id: String): MediaProvider? = mutableProviders.value.firstOrNull {
        it.descriptor.id == id
    }

    fun replace(providers: List<MediaProvider>) {
        mutableProviders.value = providers.distinctBy { it.descriptor.id }
    }
}
