package top.cylunex.shadowmedia

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import top.cylunex.shadowmedia.model.NetworkStorageConnection
import top.cylunex.shadowmedia.model.NetworkStorageHealth
import top.cylunex.shadowmedia.model.NetworkStorageKind
import top.cylunex.shadowmedia.model.NetworkStorageStatus
import top.cylunex.shadowmedia.ui.ScreenHeader
import top.cylunex.shadowmedia.ui.ShadowGlassPanel
import top.cylunex.shadowmedia.ui.shadowTvFocus
import top.cylunex.shadowmedia.ui.withoutEmoji

@Composable
internal fun NetworkStorageScreen(state: MainUiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                title = "网络媒体库",
                subtitle = "OpenList · WebDAV · SMB · NFO · STRM",
                actionLabel = "返回",
                onAction = viewModel::back,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("独立于 Emby", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "读取同目录 NFO、海报和 STRM。凭据仅发送到配置的服务，OpenList 302 后不会带到网盘 CDN。",
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            ShadowGlassPanel(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                cornerRadius = 24.dp,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("添加媒体库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NetworkStorageKind.entries.forEach { kind ->
                            FilterChip(
                                selected = state.networkStorageKind == kind,
                                onClick = { viewModel.updateNetworkStorageKind(kind) },
                                label = { Text(kind.label()) },
                                leadingIcon = { Icon(kind.icon(), null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = state.networkStorageName,
                        onValueChange = viewModel::updateNetworkStorageName,
                        label = { Text("媒体库名称") },
                        placeholder = { Text("家庭影片") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.networkStorageAddress,
                        onValueChange = viewModel::updateNetworkStorageAddress,
                        label = { Text(if (state.networkStorageKind == NetworkStorageKind.SMB) "SMB 主机" else "服务地址") },
                        placeholder = {
                            Text(
                                when (state.networkStorageKind) {
                                    NetworkStorageKind.OPENLIST -> "https://openlist.example.com/"
                                    NetworkStorageKind.WEBDAV -> "https://dav.example.com/dav/"
                                    NetworkStorageKind.SMB -> "192.168.1.10 或 nas.local:445"
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.networkStorageKind == NetworkStorageKind.SMB) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = state.networkStorageShare,
                                onValueChange = viewModel::updateNetworkStorageShare,
                                label = { Text("共享名") },
                                placeholder = { Text("Media") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = state.networkStorageDomain,
                                onValueChange = viewModel::updateNetworkStorageDomain,
                                label = { Text("域，可空") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = state.networkStorageUsername,
                            onValueChange = viewModel::updateNetworkStorageUsername,
                            label = { Text("用户名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.networkStoragePassword,
                            onValueChange = viewModel::updateNetworkStoragePassword,
                            label = { Text("密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = state.networkStorageRootPath,
                        onValueChange = viewModel::updateNetworkStorageRootPath,
                        label = { Text("媒体根目录") },
                        placeholder = { Text("/Movies") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ToggleRow(
                        checked = state.networkStorageAllowInsecureHttp,
                        onCheckedChange = viewModel::updateNetworkStorageAllowInsecure,
                        title = if (state.networkStorageKind == NetworkStorageKind.SMB) {
                            "允许 STRM 使用受信任局域网 HTTP"
                        } else "允许受信任局域网 HTTP",
                    )
                    ToggleRow(
                        checked = state.networkStorageReadNfo,
                        onCheckedChange = viewModel::updateNetworkStorageReadNfo,
                        title = "读取 NFO 和同目录海报",
                    )
                    ToggleRow(
                        checked = state.networkStorageResolveStrm,
                        onCheckedChange = viewModel::updateNetworkStorageResolveStrm,
                        title = "解析 STRM 第一条有效播放地址",
                    )
                    state.networkStorageMessage?.let {
                        Text(
                            it.withoutEmoji(),
                            color = if (it.startsWith("连接失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = viewModel::saveNetworkStorage,
                        enabled = !state.isSavingNetworkStorage,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Storage, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.isSavingNetworkStorage) "正在验证" else "验证并添加")
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已连接", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = viewModel::refreshNetworkStorages) { Icon(Icons.Rounded.Refresh, "刷新连接") }
            }
        }
        items(state.networkStorages, key = NetworkStorageConnection::id) { connection ->
            NetworkStorageCard(
                connection = connection,
                status = state.networkStorageStatuses[connection.id],
                onOpen = { viewModel.openNetworkStorage(connection) },
                onRemove = { viewModel.removeNetworkStorage(connection.id) },
            )
        }
        if (state.networkStorages.isEmpty()) {
            item {
                Text(
                    "还没有网络媒体库。OpenList 推荐填写站点根地址，WebDAV 填写完整 DAV 地址。",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun NetworkStorageCard(
    connection: NetworkStorageConnection,
    status: NetworkStorageStatus?,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).shadowTvFocus(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(connection.kind.icon(), null, Modifier.padding(11.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(connection.name.withoutEmoji(), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${connection.kind.label()} · ${connection.rootPath}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    status?.summary() ?: "等待诊断",
                    color = status.healthColor(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Icon(Icons.Rounded.FolderOpen, "浏览", tint = MaterialTheme.colorScheme.primary)
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.DeleteOutline, "移除") }
        }
    }
}

@Composable
private fun ToggleRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun NetworkStorageKind.label(): String = when (this) {
    NetworkStorageKind.OPENLIST -> "OpenList"
    NetworkStorageKind.WEBDAV -> "WebDAV"
    NetworkStorageKind.SMB -> "SMB"
}

private fun NetworkStorageKind.icon() = when (this) {
    NetworkStorageKind.OPENLIST -> Icons.Rounded.CloudQueue
    NetworkStorageKind.WEBDAV -> Icons.Rounded.Storage
    NetworkStorageKind.SMB -> Icons.Rounded.Lan
}

private fun NetworkStorageStatus.summary(): String = buildString {
    append(message.withoutEmoji())
    latencyMs?.let { append(" · ${it}ms") }
}

@Composable
private fun NetworkStorageStatus?.healthColor(): Color = when (this?.health) {
    NetworkStorageHealth.ONLINE -> MaterialTheme.colorScheme.primary
    NetworkStorageHealth.AUTH_REQUIRED -> MaterialTheme.colorScheme.tertiary
    NetworkStorageHealth.OFFLINE -> MaterialTheme.colorScheme.error
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}
