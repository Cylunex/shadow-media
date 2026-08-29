package top.cylunex.shadowmedia

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.cylunex.shadowmedia.database.MediaMomentEntity
import top.cylunex.shadowmedia.database.PlaybackMetricEntity
import top.cylunex.shadowmedia.database.SourceHealthEntity
import top.cylunex.shadowmedia.model.MediaSegment
import top.cylunex.shadowmedia.model.SegmentType
import top.cylunex.shadowmedia.ui.ScreenHeader
import top.cylunex.shadowmedia.ui.ShadowGlassPanel

@Composable
internal fun InsightsScreen(viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val moments by viewModel.moments.collectAsStateWithLifecycle()
    val metrics by viewModel.playbackMetrics.collectAsStateWithLifecycle()
    val health by viewModel.sourceHealth.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val report = metrics.toRedactedReport(health)

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "媒体记忆",
                subtitle = "从观看中的一个时间点，延伸到可复用的片段与可解释统计",
                actionLabel = "返回",
                onAction = viewModel::back,
            )
        }
        item {
            InsightHero(metrics = metrics, health = health)
        }
        item {
            FilledTonalButton(
                onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Shadow Media 脱敏播放诊断")
                                putExtra(Intent.EXTRA_TEXT, report)
                            },
                            "分享脱敏诊断",
                        )
                    )
                },
                enabled = metrics.isNotEmpty() || health.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null)
                Text("  分享脱敏诊断")
            }
        }
        state.insightMessage?.let { message ->
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(message, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item { InsightLabel("SAVED MOMENTS", "最近保存的媒体时刻") }
        if (moments.isEmpty()) {
            item { EmptyInsight("播放时使用“保存时刻”，这里会形成可继续观看的私人索引。") }
        } else {
            items(moments, key = MediaMomentEntity::id) { moment ->
                MomentCard(moment, { viewModel.playMoment(moment) }, { viewModel.removeMoment(moment.id) })
            }
        }
        if (state.mediaSegments.isNotEmpty()) {
            item { InsightLabel("SEGMENT MAP", "当前媒体的片段标记") }
            items(state.mediaSegments, key = MediaSegment::id) { segment ->
                SegmentCard(segment, onDelete = { viewModel.removeSegment(segment.id) })
            }
        }
        item { InsightLabel("SOURCE HEALTH", "近期开播质量") }
        if (health.isEmpty()) {
            item { EmptyInsight("完成几次播放后，这里会显示首帧速度、失败与线路稳定性。") }
        } else {
            items(health.take(12), key = SourceHealthEntity::sourceKey) { SourceHealthCard(it) }
        }
    }
}

@Composable
private fun InsightHero(metrics: List<PlaybackMetricEntity>, health: List<SourceHealthEntity>) {
    val firstFrames = metrics.mapNotNull(PlaybackMetricEntity::firstFrameMs)
    val averageFirstFrame = firstFrames.takeIf(List<Long>::isNotEmpty)?.average()?.toLong()
    val errors = metrics.count { it.errorCode != null }
    val buffering = metrics.sumOf(PlaybackMetricEntity::bufferingDurationMs)
    val healthy = health.count { it.consecutiveFailures == 0 }
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xCC163A20), Color(0xE6111D14), Color(0xDD203226)),
                ),
                RoundedCornerShape(30.dp),
            )
            .padding(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.12f)) {
                    Icon(
                        Icons.Rounded.Insights,
                        contentDescription = null,
                        modifier = Modifier.padding(11.dp).size(24.dp),
                        tint = Color.White,
                    )
                }
                Column {
                    Text("PLAYBACK FIELD NOTES", color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.labelSmall)
                    Text("你的观看系统正在学习", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InsightStat("播放", metrics.size.toString())
                InsightStat("平均首帧", averageFirstFrame?.let { "${it}ms" } ?: "—")
                InsightStat("缓冲", formatInsightDuration(buffering))
                InsightStat("健康线路", "$healthy/${health.size}")
                InsightStat("失败", errors.toString())
            }
        }
    }
}

@Composable
private fun InsightStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.58f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun InsightLabel(kicker: String, title: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 5.dp)) {
        Text(kicker, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MomentCard(moment: MediaMomentEntity, onPlay: () -> Unit, onDelete: () -> Unit) {
    ShadowGlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        cornerRadius = 22.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "从此时刻播放")
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(moment.mediaTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatInsightDuration(moment.positionMs)} · ${moment.note.ifBlank { "私人时间标记" }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除时刻")
            }
        }
    }
}

@Composable
private fun SegmentCard(segment: MediaSegment, onDelete: () -> Unit) {
    ShadowGlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        cornerRadius = 20.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.MovieFilter, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(segment.type.displayName(), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${formatInsightDuration(segment.startMs)} — ${formatInsightDuration(segment.endMs)} · 用户标记",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除片段")
            }
        }
    }
}

@Composable
private fun SourceHealthCard(health: SourceHealthEntity) {
    ShadowGlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        cornerRadius = 20.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "线路 ${health.sourceKey.takeLast(8)}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "成功 ${health.successes} · 失败 ${health.failures} · 连续失败 ${health.consecutiveFailures} · " +
                        "平均首帧 ${health.averageFirstFrameMs?.let { "${it}ms" } ?: "—"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun EmptyInsight(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(message, modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun SegmentType.displayName(): String = when (this) {
    SegmentType.INTRO -> "片头"
    SegmentType.RECAP -> "前情回顾"
    SegmentType.CREDITS -> "片尾"
    SegmentType.PREVIEW -> "预告"
    SegmentType.HIGHLIGHT -> "精彩片段"
    SegmentType.CHAPTER -> "章节"
}

private fun formatInsightDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

private fun List<PlaybackMetricEntity>.toRedactedReport(health: List<SourceHealthEntity>): String {
    val firstFrames = mapNotNull(PlaybackMetricEntity::firstFrameMs)
    val averageFirstFrame = firstFrames.takeIf(List<Long>::isNotEmpty)?.average()?.toLong()
    val methods = groupBy(PlaybackMetricEntity::method).mapValues { it.value.size }
    return buildString {
        appendLine("Shadow Media 脱敏播放诊断")
        appendLine("播放样本：$size")
        appendLine("平均首帧：${averageFirstFrame?.let { "${it}ms" } ?: "无数据"}")
        appendLine("缓冲次数：${sumOf(PlaybackMetricEntity::bufferingCount)}")
        appendLine("缓冲时长：${formatInsightDuration(sumOf(PlaybackMetricEntity::bufferingDurationMs))}")
        appendLine("播放失败：${this@toRedactedReport.count { it.errorCode != null }}")
        appendLine("播放方式：${methods.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "无数据" }}")
        appendLine("线路数量：${health.size}")
        appendLine("健康线路：${health.count { it.consecutiveFailures == 0 }}")
        append("报告不包含服务器地址、媒体标题、访问令牌或完整线路标识。")
    }
}
