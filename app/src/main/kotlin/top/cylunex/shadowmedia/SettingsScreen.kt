package top.cylunex.shadowmedia

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.cylunex.shadowmedia.model.FeatureId
import top.cylunex.shadowmedia.ui.ScreenHeader
import top.cylunex.shadowmedia.ui.ShadowGlassPanel

@Composable
internal fun SettingsScreen(viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    val flags by viewModel.featureFlags.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "功能控制台",
                subtitle = "扩展能力保持解耦，可随时关闭或重新启用",
                actionLabel = "返回媒体中心",
                onAction = viewModel::back,
            )
        }
        item {
            FilledTonalButton(
                onClick = viewModel::showIntegrations,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Icon(Icons.Rounded.SettingsEthernet, null)
                Text("  管理 MoviePilot、Seerr 与频道服务")
            }
        }
        item {
            Text(
                "ACTIVE MODULES",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        items(FeatureId.entries, key = FeatureId::name) { feature ->
            FeatureToggle(
                feature = feature,
                enabled = flags[feature] ?: feature.defaultEnabled,
                onEnabledChange = { viewModel.setFeatureEnabled(feature, it) },
            )
        }
    }
}

@Composable
private fun FeatureToggle(
    feature: FeatureId,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    ShadowGlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        cornerRadius = 22.dp,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    feature.icon(),
                    contentDescription = null,
                    modifier = Modifier.padding(11.dp).size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(feature.title(), style = MaterialTheme.typography.titleMedium)
                Text(
                    feature.description(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

private fun FeatureId.title(): String = when (this) {
    FeatureId.LIVE_CENTER -> "直播中心"
    FeatureId.AGGREGATE_SEARCH -> "聚合搜索"
    FeatureId.EXTERNAL_PROVIDERS -> "外部 Provider"
    FeatureId.VIRTUAL_CHANNELS -> "虚拟频道"
    FeatureId.MEDIA_REQUESTS -> "想看与订阅"
    FeatureId.PLAYBACK_TELEMETRY -> "播放质量治理"
    FeatureId.MEDIA_MOMENTS -> "媒体时刻"
    FeatureId.SEMANTIC_SEARCH -> "语义搜索"
    FeatureId.WATCH_PARTY -> "一起看"
    FeatureId.LABS -> "实验室"
}

private fun FeatureId.description(): String = when (this) {
    FeatureId.LIVE_CENTER -> "频道、节目单、回看、提醒与多线路"
    FeatureId.AGGREGATE_SEARCH -> "跨服务器和外部内容统一检索"
    FeatureId.EXTERNAL_PROVIDERS -> "声明式接口、Stremio 与隔离运行时"
    FeatureId.VIRTUAL_CHANNELS -> "消费 Tunarr 或 ErsatzTV 线性频道"
    FeatureId.MEDIA_REQUESTS -> "连接 MoviePilot 或 Seerr 的入库流程"
    FeatureId.PLAYBACK_TELEMETRY -> "首帧、缓冲、失败和线路健康度"
    FeatureId.MEDIA_MOMENTS -> "保存时间点、备注和片段引用"
    FeatureId.SEMANTIC_SEARCH -> "通过字幕和索引定位具体场景"
    FeatureId.WATCH_PARTY -> "多人会话、同步控制和漂移修正"
    FeatureId.LABS -> "尚未稳定的前沿交互能力"
}

private fun FeatureId.icon(): ImageVector = when (this) {
    FeatureId.LIVE_CENTER -> Icons.Rounded.LiveTv
    FeatureId.AGGREGATE_SEARCH -> Icons.Rounded.Search
    FeatureId.EXTERNAL_PROVIDERS -> Icons.Rounded.Dns
    FeatureId.VIRTUAL_CHANNELS -> Icons.Rounded.AutoAwesomeMotion
    FeatureId.MEDIA_REQUESTS -> Icons.Rounded.Subscriptions
    FeatureId.PLAYBACK_TELEMETRY -> Icons.Rounded.MonitorHeart
    FeatureId.MEDIA_MOMENTS -> Icons.Rounded.MovieFilter
    FeatureId.SEMANTIC_SEARCH -> Icons.Rounded.Explore
    FeatureId.WATCH_PARTY -> Icons.Rounded.Forum
    FeatureId.LABS -> Icons.Rounded.Science
}
