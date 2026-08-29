package top.cylunex.shadowmedia

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.cylunex.shadowmedia.model.IntegrationConnection
import top.cylunex.shadowmedia.model.IntegrationHealth
import top.cylunex.shadowmedia.model.IntegrationKind
import top.cylunex.shadowmedia.model.IntegrationStatus
import top.cylunex.shadowmedia.ui.ScreenHeader
import top.cylunex.shadowmedia.ui.shadowTvFocus
import top.cylunex.shadowmedia.ui.withoutEmoji

@Composable
internal fun IntegrationScreen(state: MainUiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                title = "服务连接",
                subtitle = "发现到入库、虚拟频道与直播治理的受控入口",
                actionLabel = "返回控制台",
                onAction = viewModel::back,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("新增连接", style = MaterialTheme.typography.titleLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(IntegrationKind.entries, key = IntegrationKind::name) { kind ->
                            FilterChip(
                                selected = state.integrationKind == kind,
                                onClick = { viewModel.updateIntegrationKind(kind) },
                                label = { Text(kind.label()) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = state.integrationName,
                        onValueChange = viewModel::updateIntegrationName,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("连接名称") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.integrationBaseUrl,
                        onValueChange = viewModel::updateIntegrationBaseUrl,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("服务地址") },
                        placeholder = { Text("https://service.example.com") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.integrationApiToken,
                        onValueChange = viewModel::updateIntegrationApiToken,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key / Token（可选）") },
                        leadingIcon = { Icon(Icons.Rounded.Key, null) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    if (state.integrationKind == IntegrationKind.DISPATCHARR || state.integrationKind == IntegrationKind.TUNARR) {
                        OutlinedTextField(
                            value = state.integrationPlaylistUrl,
                            onValueChange = viewModel::updateIntegrationPlaylistUrl,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("M3U 输出地址（Tunarr 可自动推导）") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = state.integrationEpgUrl,
                            onValueChange = viewModel::updateIntegrationEpgUrl,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("XMLTV 地址（可选）") },
                            singleLine = true,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.integrationAllowInsecureHttp,
                            onCheckedChange = viewModel::updateIntegrationAllowInsecure,
                        )
                        Text("允许受信任局域网 HTTP", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = viewModel::saveIntegration,
                        enabled = state.integrationName.isNotBlank() && state.integrationBaseUrl.isNotBlank() &&
                            !state.isSavingIntegration,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.AddLink, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.isSavingIntegration) "正在检查连接" else "检查并加密保存")
                    }
                    Text(
                        "Token 仅保存在 Android Keystore 加密数据中；诊断页不会显示或导出凭据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.integrations.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已连接服务", style = MaterialTheme.typography.titleLarge)
                    FilledTonalButton(onClick = viewModel::refreshIntegrations) {
                        Icon(Icons.Rounded.Refresh, null)
                        Text("  全部诊断")
                    }
                }
            }
        }
        items(state.integrations, key = IntegrationConnection::id) { connection ->
            IntegrationCard(
                connection = connection,
                status = state.integrationStatuses[connection.id],
                onImport = { viewModel.importVirtualChannels(connection) },
                onRemove = { viewModel.removeIntegration(connection.id) },
            )
        }
        state.integrationMessage?.let { message ->
            item {
                Text(
                    message.withoutEmoji(),
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun IntegrationCard(
    connection: IntegrationConnection,
    status: IntegrationStatus?,
    onImport: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).shadowTvFocus(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Rounded.MonitorHeart, null, Modifier.padding(11.dp).size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(connection.name.withoutEmoji(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${connection.kind.label()} · ${status?.summary() ?: "等待诊断"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (status?.health) {
                            IntegrationHealth.ONLINE -> MaterialTheme.colorScheme.primary
                            IntegrationHealth.AUTH_REQUIRED -> MaterialTheme.colorScheme.tertiary
                            IntegrationHealth.OFFLINE -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.DeleteOutline, "移除连接", tint = MaterialTheme.colorScheme.error)
                }
            }
            Text(connection.baseUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (connection.kind == IntegrationKind.TUNARR || connection.kind == IntegrationKind.DISPATCHARR) {
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.LiveTv, null)
                    Spacer(Modifier.width(8.dp))
                    Text("导入到直播中心")
                }
            }
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(connection.baseUrl))) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.AddLink, null)
                Spacer(Modifier.width(8.dp))
                Text("打开服务管理界面")
            }
        }
    }
}

private fun IntegrationKind.label(): String = when (this) {
    IntegrationKind.MOVIEPILOT -> "MoviePilot"
    IntegrationKind.SEERR -> "Seerr"
    IntegrationKind.TUNARR -> "Tunarr"
    IntegrationKind.DISPATCHARR -> "Dispatcharr"
}

private fun IntegrationStatus.summary(): String = buildString {
    append(message)
    latencyMs?.let { append(" · ${it}ms") }
    version?.let { append(" · $it") }
}
