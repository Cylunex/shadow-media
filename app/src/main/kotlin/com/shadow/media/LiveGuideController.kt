package com.shadow.media

import com.shadow.media.database.LocalMediaStateRepository
import com.shadow.media.database.MediaFavoriteEntity
import com.shadow.media.model.ExternalMediaEntry
import com.shadow.media.model.ExternalSourceSummary
import com.shadow.media.model.LiveChannel
import com.shadow.media.model.LiveProgram
import com.shadow.media.network.LiveGuideRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class LiveGuideUiState(
    val livePrograms: Map<String, List<LiveProgram>> = emptyMap(),
    val liveFavoriteKeys: Set<String> = emptySet(),
    val isLoadingGuide: Boolean = false,
    val liveGuideMessage: String? = null,
)

internal class LiveGuideController(
    private val scope: CoroutineScope,
    private val localMediaState: LocalMediaStateRepository,
    private val liveGuideRepository: LiveGuideRepository,
) {
    private val mutableState = MutableStateFlow(LiveGuideUiState())
    val state = mutableState.asStateFlow()
    private var liveGuideRequest: Job? = null
    private var sourceId: String? = null
    private fun update(change: LiveGuideUiState.() -> LiveGuideUiState) = mutableState.update(change)

    fun cancel() {
        liveGuideRequest?.cancel()
        update { copy(isLoadingGuide = false) }
    }

    fun clear() {
        liveGuideRequest?.cancel()
        sourceId = null
        mutableState.value = LiveGuideUiState()
    }

    fun toggleFavorite(source: ExternalSourceSummary, channel: LiveChannel) {
        val providerId = "external:${source.id}"
        val stableKey = "$providerId:${channel.id}"
        scope.launch {
            if (stableKey in state.value.liveFavoriteKeys) {
                localMediaState.removeFavorite(stableKey)
                if (sourceId == source.id) update { copy(liveFavoriteKeys = liveFavoriteKeys - stableKey) }
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
                if (sourceId == source.id) update { copy(liveFavoriteKeys = liveFavoriteKeys + stableKey) }
            }
        }
    }

    fun load(source: ExternalSourceSummary, entries: List<ExternalMediaEntry>) {
        clear()
        sourceId = source.id
        liveGuideRequest = scope.launch {
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
}
