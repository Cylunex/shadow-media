package top.cylunex.shadowmedia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.MediaItem
import top.cylunex.shadowmedia.model.MediaLibrary

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    actionIsAdd: Boolean = false,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp)) {
        Text(
            "SHADOW / MEDIA",
            modifier = Modifier.align(Alignment.TopEnd),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(Modifier.weight(1f)) {
            Text(
                "PRIVATE CINEMA / CONNECTED",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(title.withoutEmoji(), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(7.dp))
            Text(
                subtitle.withoutEmoji(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(13.dp))
            Box(
                Modifier.fillMaxWidth(0.24f).height(2.dp).background(MaterialTheme.colorScheme.primary)
            )
            }
            FilledTonalIconButton(onClick = onAction, modifier = Modifier.size(54.dp)) {
                Icon(
                    if (actionIsAdd) Icons.Rounded.Add else Icons.Rounded.Storage,
                    contentDescription = actionLabel,
                )
            }
        }
    }
}

@Composable
fun ServerCard(
    session: EmbySession,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().shadowTvFocus(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(17.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        session.userName.withoutEmoji().firstOrNull()?.uppercase() ?: "S",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(session.userName.withoutEmoji(), style = MaterialTheme.typography.titleMedium)
                Text(
                    session.serverUrl,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                    Text(
                        "  登录已保存",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "移除服务器",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
fun ContinueFeedCard(currentIndex: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().shadowTvFocus(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.11f)) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(12.dp).size(24.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text("继续刷片", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
                Text(
                    "从第 ${currentIndex + 1} 条接着看",
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text("PLAY", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun LibraryCard(session: EmbySession?, library: MediaLibrary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().shadowTvFocus()) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.15f)) {
            EmbyArtwork(
                session = session,
                itemId = library.id,
                title = library.name,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f), Color.Black.copy(alpha = 0.88f))
                    )
                )
            )
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                Icon(Icons.Rounded.Dns, contentDescription = null, tint = Color.White.copy(alpha = 0.78f))
                Spacer(Modifier.height(6.dp))
                Text(
                    library.name.withoutEmoji(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    library.collectionType?.uppercase() ?: "媒体库",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
fun MediaPosterCard(
    session: EmbySession?,
    item: MediaItem,
    episodeLabel: String,
    progress: Float,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onFavorite: (() -> Unit)? = null,
) {
    Column {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().shadowTvFocus()) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                EmbyArtwork(
                    session,
                    item.id,
                    item.name,
                    Modifier.fillMaxSize(),
                    imageTag = item.imageTag,
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.75f))
                        )
                    )
                )
                AssistChip(
                    onClick = onClick,
                    label = { Text(if (item.played) "已看" else item.type) },
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Color.Black.copy(alpha = 0.58f),
                        labelColor = Color.White,
                    ),
                    border = null,
                )
                Column(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    onFavorite?.let { favoriteAction ->
                        Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.64f)) {
                            IconButton(onClick = favoriteAction, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    if (item.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    "收藏",
                                    tint = if (item.favorite) MaterialTheme.colorScheme.primary else Color.White,
                                )
                            }
                        }
                    }
                    onDelete?.let { deleteAction ->
                        Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.64f)) {
                            IconButton(onClick = deleteAction, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Rounded.DeleteOutline, "删除", tint = Color.White)
                            }
                        }
                    }
                }
                if (progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(item.name.withoutEmoji(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            episodeLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PlayerTopBar(
    page: Int,
    pageCount: Int,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.48f)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回列表", tint = Color.White)
            }
        }
        Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.48f)) {
            Text(
                "${page + 1}  /  $pageCount",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.48f)) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, "删除视频", tint = Color.White)
            }
        }
    }
}

@Composable
fun EmptyStatePanel(message: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Rounded.Storage,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp).size(28.dp),
                )
            }
            Text(message.withoutEmoji(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
