package com.shadow.media

import com.shadow.media.model.NetworkStorageConnection
import com.shadow.media.model.NetworkStorageHealth
import com.shadow.media.model.NetworkStorageKind
import com.shadow.media.model.NetworkStorageStatus
import com.shadow.media.network.NetworkStorageRepository
import com.shadow.media.network.NetworkStorageStore
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class NetworkStorageUiState(
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
)

internal class NetworkStorageController(
    private val scope: CoroutineScope,
    private val networkStorageRepository: NetworkStorageRepository,
    private val networkStorageStore: NetworkStorageStore,
    private val providersChanged: () -> Unit,
    private val reportError: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(NetworkStorageUiState())
    val state = mutableState.asStateFlow()
    private var storageProbeRequest: Job? = null
    private fun update(change: NetworkStorageUiState.() -> NetworkStorageUiState) = mutableState.update(change)

    fun setConnections(connections: List<NetworkStorageConnection>) = update { copy(networkStorages = connections) }

    fun open() {
        val connections = loadNetworkStorageConnections() ?: return
        update { copy(networkStorages = connections, networkStorageMessage = null) }
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
        scope.launch {
            runCatching {
                require(connection.name.isNotBlank()) { "请填写媒体库名称" }
                require(connection.address.isNotBlank()) { "请填写服务地址或 SMB 主机" }
                if (connection.kind == NetworkStorageKind.SMB) require(connection.share.isNotBlank()) { "请填写 SMB 共享名" }
                update { copy(isSavingNetworkStorage = true, networkStorageMessage = "正在验证连接") }
                val status = networkStorageRepository.probe(connection)
                if (status.health != NetworkStorageHealth.ONLINE) throw IllegalArgumentException(status.message)
                networkStorageStore.save(connection)
                providersChanged()
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
                if (error is CancellationException) throw error
                update { copy(isSavingNetworkStorage = false, networkStorageMessage = "连接失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun removeNetworkStorage(connectionId: String) {
        try { networkStorageStore.remove(connectionId) }
        catch (e: Exception) {
            val message = e.message ?: "无法移除网络媒体库"
            update { copy(networkStorageMessage = message) }
            reportError(message)
            return
        }
        networkStorageRepository.forget(connectionId)
        providersChanged()
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
        val connections = loadNetworkStorageConnections() ?: return
        storageProbeRequest = scope.launch {
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

    fun loadNetworkStorageConnections(): List<NetworkStorageConnection>? = try {
        networkStorageStore.loadAll()
    } catch (e: Exception) {
        val message = e.message ?: "网络存储凭据暂时无法读取，请解锁设备后重试"
        update { copy(networkStorageMessage = message) }
        reportError(message)
        null
    }
}
