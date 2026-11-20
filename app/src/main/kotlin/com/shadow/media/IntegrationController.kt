package com.shadow.media

import com.shadow.media.model.IntegrationConnection
import com.shadow.media.model.IntegrationKind
import com.shadow.media.model.IntegrationStatus
import com.shadow.media.model.UnifiedMediaItem
import com.shadow.media.network.ExternalSourceRepository
import com.shadow.media.network.ExternalSourceStore
import com.shadow.media.network.IntegrationRepository
import com.shadow.media.network.IntegrationStore
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class IntegrationUiState(
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
)

internal class IntegrationController(
    private val scope: CoroutineScope,
    private val integrationRepository: IntegrationRepository,
    private val integrationStore: IntegrationStore,
    private val externalSourceRepository: ExternalSourceRepository,
    private val externalSourceStore: ExternalSourceStore,
    private val providersChanged: () -> Unit,
) {
    private val mutableState = MutableStateFlow(IntegrationUiState())
    val state = mutableState.asStateFlow()
    private var integrationProbeRequest: Job? = null
    private fun update(change: IntegrationUiState.() -> IntegrationUiState) = mutableState.update(change)

    fun readConnections() = update { copy(integrations = integrationStore.loadAll()) }

    fun open() {
        readConnections()
        update { copy(integrationMessage = null) }
        refreshIntegrations()
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
        scope.launch {
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
        integrationProbeRequest = scope.launch {
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
        scope.launch {
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
            }.mapCatching { imported ->
                externalSourceStore.save(imported)
                providersChanged()
                update {
                    copy(
                        isSavingIntegration = false,
                        integrationMessage = "虚拟频道已加入直播中心",
                    )
                }
            }.onFailure {
                update { copy(isSavingIntegration = false, integrationMessage = it.message ?: "频道导入失败") }
            }
        }
    }

    fun requestMedia(item: UnifiedMediaItem) {
        val tmdbId = item.externalIds.entries.firstOrNull { it.key.equals("Tmdb", true) }
            ?.value?.toIntOrNull()
        val seerr = integrationStore.loadAll().firstOrNull { it.kind == IntegrationKind.SEERR }
        if (tmdbId == null || seerr == null) {
            update { copy(integrationMessage = "需要 TMDB ID 和已配置的 Seerr 连接") }
            return
        }
        scope.launch {
            val mediaType = if (item.type.equals("Movie", true)) "movie" else "tv"
            runCatching { integrationRepository.requestMedia(seerr, tmdbId, mediaType) }
                .onSuccess { message -> update { copy(integrationMessage = message) } }
                .onFailure { update { copy(integrationMessage = it.message ?: "提交想看失败") } }
        }
    }
}
