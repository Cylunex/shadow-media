package top.cylunex.shadowmedia

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.cylunex.shadowmedia.model.ExternalMediaEntry
import top.cylunex.shadowmedia.model.ExternalSourceKind
import top.cylunex.shadowmedia.model.ExternalSourceSummary
import top.cylunex.shadowmedia.model.LiveChannel
import top.cylunex.shadowmedia.model.LiveProgram
import top.cylunex.shadowmedia.model.UnifiedMediaItem
import top.cylunex.shadowmedia.model.MediaItem
import top.cylunex.shadowmedia.model.MediaSection
import top.cylunex.shadowmedia.model.embyTicksToMilliseconds
import top.cylunex.shadowmedia.ui.EmbyArtwork
import top.cylunex.shadowmedia.ui.EmptyStatePanel
import top.cylunex.shadowmedia.ui.MediaPosterCard
import top.cylunex.shadowmedia.ui.withoutEmoji
import top.cylunex.shadowmedia.ui.ScreenHeader
import top.cylunex.shadowmedia.ui.shadowTvFocus
import coil3.compose.AsyncImage

@Composable
internal fun DiscoverScreen(state: MainUiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    val providerNames = remember(state.providerDescriptors) {
        state.providerDescriptors.associate { it.id to it.name }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                title = "发现",
                subtitle = "跨 ${state.providerDescriptors.size} 个内容服务统一检索",
                actionLabel = "返回首页",
                onAction = viewModel::back,
            )
        }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.discoverQuery,
                    onValueChange = viewModel::updateDiscoverQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索电影、剧集或频道") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.searchProviders() }),
                )
                Button(
                    onClick = viewModel::searchProviders,
                    enabled = state.discoverQuery.isNotBlank() && !state.isSearchingProviders,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Explore, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.isSearchingProviders) "正在聚合结果" else "跨源搜索")
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.providerDescriptors, key = { it.id }) { provider ->
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                        ) {
                            Text(
                                provider.name.withoutEmoji(),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
        if (state.providerSearchFailures.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)),
                ) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            "${state.providerSearchFailures.size} 个服务暂时不可用，其余结果不受影响",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
        if (state.discoverResults.isEmpty() && !state.isSearchingProviders) {
            item {
                EmptyStatePanel(
                    if (state.discoverQuery.isBlank()) "输入关键词，同时搜索 Emby、直播和已导入的安全 HTTP 站点"
                    else "没有找到匹配结果，可以换一个更短的关键词",
                    Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        items(state.discoverResults, key = { it.key.stableId }) { item ->
            UnifiedResultCard(
                item = item,
                providerName = providerNames[item.key.providerId].orEmpty(),
                onClick = { viewModel.openUnifiedItem(item) },
            )
        }
        state.errorMessage?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
    }
}

@Composable
private fun UnifiedResultCard(item: UnifiedMediaItem, providerName: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).shadowTvFocus(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            UnifiedPoster(item, Modifier.size(width = 78.dp, height = 108.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.title.withoutEmoji(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(item.year?.toString(), item.rating?.let { "%.1f".format(it) }, item.type)
                        .joinToString(" · "),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                item.subtitle?.let {
                    Text(it.withoutEmoji(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(providerName.withoutEmoji(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            Icon(Icons.Rounded.ChevronRight, "查看详情")
        }
    }
}

@Composable
internal fun UnifiedDetailScreen(state: MainUiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    val context = LocalContext.current
    val detail = state.selectedUnifiedDetail
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenHeader(
                title = detail?.item?.title?.withoutEmoji() ?: "内容详情",
                subtitle = detail?.item?.subtitle?.withoutEmoji() ?: "正在读取 Provider 元数据",
                actionLabel = "返回发现",
                onAction = viewModel::back,
            )
        }
        if (detail != null) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    UnifiedPoster(detail.item, Modifier.size(width = 116.dp, height = 164.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(detail.item.title.withoutEmoji(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            listOfNotNull(
                                detail.item.year?.toString(),
                                detail.item.rating?.let { "评分 %.1f".format(it) },
                                detail.item.type,
                            ).joinToString(" · "),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Button(
                            onClick = { viewModel.playUnifiedItem(detail.children.firstOrNull() ?: detail.item) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (detail.children.isEmpty()) "立即播放" else "播放第一集")
                        }
                        OutlinedButton(
                            onClick = {
                                val target = detail.children.firstOrNull() ?: detail.item
                                val link = target.key.toHandoffUri(target.progressMs).toString()
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, link)
                                            putExtra(Intent.EXTRA_TITLE, target.title.withoutEmoji())
                                        },
                                        "发送播放接力链接",
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.CastConnected, null)
                            Spacer(Modifier.width(8.dp))
                            Text("发送到其他设备")
                        }
                    }
                }
            }
            detail.item.overview?.takeIf(String::isNotBlank)?.let { overview ->
                item {
                    Text(
                        overview.withoutEmoji(),
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (detail.genres.isNotEmpty() || detail.people.isNotEmpty()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items((detail.genres + detail.people).distinct(), key = { it }) { label ->
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(label.withoutEmoji(), Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            if (detail.children.isNotEmpty()) {
                item {
                    Text(
                        "选集与线路",
                        modifier = Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(detail.children, key = { it.key.stableId }) { child ->
                    Card(
                        onClick = { viewModel.playUnifiedItem(child) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Movie, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(child.title.withoutEmoji(), style = MaterialTheme.typography.titleMedium)
                                child.subtitle?.let { Text(it.withoutEmoji(), style = MaterialTheme.typography.bodySmall) }
                            }
                            Icon(Icons.Rounded.PlayArrow, "播放")
                        }
                    }
                }
            }
        } else if (!state.isLoading) {
            item { EmptyStatePanel("Provider 没有返回可展示的详情", Modifier.padding(horizontal = 20.dp)) }
        }
        state.errorMessage?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
    }
}

@Composable
private fun UnifiedPoster(item: UnifiedMediaItem, modifier: Modifier) {
    Surface(modifier = modifier.clip(MaterialTheme.shapes.medium), color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Movie, null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
            if (item.posterUrl != null) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@Composable
internal fun MediaHomeScreen(state: MainUiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            ScreenHeader(
                title = "媒体中心",
                subtitle = "${state.session?.userName.orEmpty()} · Emby 与你的外部订阅",
                actionLabel = "服务器",
                onAction = viewModel::showServers,
            )
        }
        item {
            MediaHubActions(
                libraryCount = state.libraries.size,
                sourceCount = state.externalSources.size,
                providerCount = state.providerDescriptors.size,
                onLibraries = viewModel::showLibraries,
                onSources = viewModel::showSources,
                onDiscover = viewModel::showDiscover,
                onSettings = viewModel::showSettings,
            )
        }
        if (state.homeSections.isEmpty() && !state.isLoading) {
            item {
                EmptyStatePanel(
                    "媒体中心还没有可展示的内容",
                    Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        items(state.homeSections, key = MediaSection::id) { section ->
            MediaSectionRow(
                state = state,
                section = section,
                onPlay = { item -> viewModel.playHomeSection(section, item) },
                onFavorite = viewModel::toggleFavorite,
            )
        }
        state.errorMessage?.let { message ->
            item {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun MediaHubActions(
    libraryCount: Int,
    sourceCount: Int,
    providerCount: Int,
    onLibraries: () -> Unit,
    onSources: () -> Unit,
    onDiscover: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HubActionCard(
                title = "发现",
                subtitle = "$providerCount 个内容服务",
                icon = { Icon(Icons.Rounded.Explore, null) },
                onClick = onDiscover,
                modifier = Modifier.weight(1f),
            )
            HubActionCard(
                title = "媒体库",
                subtitle = "$libraryCount 个片库",
                icon = { Icon(Icons.Rounded.VideoLibrary, null) },
                onClick = onLibraries,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HubActionCard(
                title = "影视仓",
                subtitle = "$sourceCount 个订阅",
                icon = { Icon(Icons.Rounded.Dns, null) },
                onClick = onSources,
                modifier = Modifier.weight(1f),
            )
            HubActionCard(
                title = "控制台",
                subtitle = "功能与诊断",
                icon = { Icon(Icons.Rounded.Tune, null) },
                onClick = onSettings,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HubActionCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.shadowTvFocus(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) { icon() }
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MediaSectionRow(
    state: MainUiState,
    section: MediaSection,
    onPlay: (MediaItem) -> Unit,
    onFavorite: (MediaItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(section.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${section.items.size} 部内容",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(section.items, key = MediaItem::id) { item ->
                HomePosterCard(state, item, onPlay, onFavorite)
            }
        }
    }
}

@Composable
private fun HomePosterCard(
    state: MainUiState,
    item: MediaItem,
    onPlay: (MediaItem) -> Unit,
    onFavorite: (MediaItem) -> Unit,
) {
    Column(Modifier.width(142.dp)) {
        Card(onClick = { onPlay(item) }) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                EmbyArtwork(
                    state.session,
                    item.id,
                    item.name,
                    Modifier.fillMaxSize(),
                    imageTag = item.imageTag,
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.76f)))
                    )
                )
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.62f),
                ) {
                    IconButton(onClick = { onFavorite(item) }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Rounded.Favorite,
                            "收藏",
                            tint = if (item.favorite) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.72f),
                        )
                    }
                }
                if (item.playbackPositionTicks > 0) {
                    Text(
                        formatCompactDuration(item.playbackPositionTicks.embyTicksToMilliseconds()),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(item.productionYear?.toString(), item.seriesName).joinToString(" · ").ifBlank { item.type },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SeriesDetailScreen(state: MainUiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    val series = state.selectedSeries
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenHeader(
                title = series?.name ?: "详情",
                subtitle = listOfNotNull(series?.productionYear?.toString(), series?.communityRating?.let { "评分 %.1f".format(it) })
                    .joinToString(" · ").ifBlank { "剧集与播放进度" },
                actionLabel = "返回封面墙",
                onAction = viewModel::back,
            )
        }
        series?.overview?.takeIf(String::isNotBlank)?.let { overview ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(overview, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        FilledTonalButton(onClick = { viewModel.toggleFavorite(series) }) {
                            Icon(Icons.Rounded.Favorite, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (series.favorite) "取消收藏" else "收藏")
                        }
                    }
                }
            }
        }
        gridItems(state.detailEpisodes, key = MediaItem::id) { episode ->
            MediaPosterCard(
                session = state.session,
                item = episode,
                episodeLabel = episodeLabel(episode),
                progress = mediaProgress(episode),
                onClick = { viewModel.playEpisode(episode) },
                onFavorite = { viewModel.toggleFavorite(episode) },
            )
        }
        if (!state.isLoading && state.detailEpisodes.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyStatePanel("没有找到可播放的剧集") }
        }
    }
}

@Composable
internal fun ExternalSourcesScreen(state: MainUiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { readExternalSourceFile(context.contentResolver, uri) }
                    .onSuccess { (name, payload) ->
                        viewModel.importLocalExternalSource(uri.toString(), name, payload)
                    }
                    .onFailure { error ->
                        viewModel.reportExternalSourceError(error.message ?: "无法读取这个文件")
                    }
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "影视仓",
                subtitle = "URL / 本地文件 · 导入后直接浏览",
                actionLabel = "返回媒体中心",
                onAction = viewModel::back,
            )
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("安全子集", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "支持 JSONC、多仓目录、M3U/TXT，并自动展开 TVBox lives 直播列表；未知 JAR、QuickJS、Python 或 WebView 嗅探仍不会执行。",
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = state.sourceUrl,
                    onValueChange = viewModel::updateSourceUrl,
                    label = { Text("配置或直播订阅地址") },
                    placeholder = { Text("https://example.com/config.json") },
                    leadingIcon = { Icon(Icons.Rounded.AddLink, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.sourceAllowInsecureHttp,
                        onCheckedChange = viewModel::updateSourceAllowInsecure,
                    )
                    Text(
                        "允许 HTTP 主地址及其二级源（仅勾选你信任的配置）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = viewModel::addExternalSource,
                    enabled = state.sourceUrl.isNotBlank() && !state.isInspectingSource,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    Text(if (state.isInspectingSource) "正在导入并展开直播…" else "从 URL 导入")
                }
                OutlinedButton(
                    onClick = {
                        filePicker.launch(
                            arrayOf(
                                "application/json",
                                "application/x-mpegURL",
                                "audio/x-mpegurl",
                                "text/plain",
                                "*/*",
                            )
                        )
                    },
                    enabled = !state.isInspectingSource,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    Icon(Icons.Rounded.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("从本地文件导入")
                }
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        if (state.externalSources.isEmpty()) {
            item {
                EmptyStatePanel(
                    "还没有外部订阅；Shadow 不会预置或分发内容源",
                    Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        items(state.externalSources, key = ExternalSourceSummary::id) { source ->
            ExternalSourceCard(
                source = source,
                onOpen = { viewModel.openExternalSource(source) },
                onRemove = { viewModel.removeExternalSource(source.id) },
            )
        }
    }
}

@Composable
private fun ExternalSourceCard(
    source: ExternalSourceSummary,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).shadowTvFocus(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(
                    if (source.kind == ExternalSourceKind.LIVE_PLAYLIST) Icons.Rounded.LiveTv else Icons.Rounded.Storage,
                    null,
                    modifier = Modifier.padding(12.dp).size(24.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(source.name.withoutEmoji(), style = MaterialTheme.typography.titleMedium)
                Text(
                    source.url,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when (source.kind) {
                        ExternalSourceKind.LIVE_PLAYLIST -> "${source.liveCount} 个直播条目"
                        ExternalSourceKind.DECLARATIVE -> "${source.siteCount} 个仓库入口"
                        ExternalSourceKind.TVBOX_CONFIG -> "${source.siteCount} 个站点 · ${source.liveCount} 个直播配置"
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                if (source.runtimeRequiredCount > 0) {
                    Text(
                        "${source.runtimeRequiredCount} 个站点需要隔离运行时，当前不会执行",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.DeleteOutline, "移除订阅", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun ExternalItemsScreen(state: MainUiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    val source = state.selectedExternalSource
    val groups = remember(state.liveChannels) {
        state.liveChannels.mapNotNull(LiveChannel::group).distinct().sorted()
    }
    val favoritePrefix = "external:${source?.id}:"
    val filteredChannels = remember(
        state.liveChannels,
        state.externalQuery,
        state.externalGroup,
        state.liveFavoriteKeys,
    ) {
        state.liveChannels.filter { channel ->
            val matchesQuery = state.externalQuery.isBlank() ||
                channel.title.contains(state.externalQuery, true) ||
                channel.group.orEmpty().contains(state.externalQuery, true)
            val matchesGroup = when (state.externalGroup) {
                null -> true
                LIVE_FAVORITES_FILTER -> "$favoritePrefix${channel.id}" in state.liveFavoriteKeys
                else -> channel.group == state.externalGroup
            }
            matchesQuery && matchesGroup
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = source?.name ?: "直播中心",
                subtitle = if (state.liveChannels.isEmpty()) "已安全导入配置" else
                    "${state.liveChannels.size} 个频道 · ${state.externalEntries.size} 条线路",
                actionLabel = "返回影视仓",
                onAction = viewModel::back,
            )
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.externalQuery,
                    onValueChange = viewModel::updateExternalQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索频道") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = state.externalGroup == null,
                            onClick = { viewModel.setExternalGroup(null) },
                            label = { Text("全部") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.externalGroup == LIVE_FAVORITES_FILTER,
                            onClick = { viewModel.setExternalGroup(LIVE_FAVORITES_FILTER) },
                            label = { Text("收藏") },
                            leadingIcon = { Icon(Icons.Rounded.Star, null, Modifier.size(18.dp)) },
                        )
                    }
                    items(groups, key = { it }) { group ->
                        FilterChip(
                            selected = state.externalGroup == group,
                            onClick = { viewModel.setExternalGroup(group) },
                            label = { Text(group.withoutEmoji()) },
                        )
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (state.isLoadingGuide) Icons.Rounded.LiveTv else Icons.Rounded.Security,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        state.liveGuideMessage ?: if (state.isLoadingGuide) "正在同步 XMLTV 节目单"
                        else "独立网络沙箱播放，不携带 Emby 凭据",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (state.externalEntries.isEmpty()) {
            item {
                EmptyStatePanel(
                    when (source?.kind) {
                        ExternalSourceKind.TVBOX_CONFIG ->
                            "配置已导入，但没有成功展开可播放直播；需要 csp/JAR/JS 的影视站点不会在主应用中执行。"
                        ExternalSourceKind.DECLARATIVE ->
                            "多仓目录已导入，但仓库失效、超时或 HTTP 二级源未获授权，因此没有可播放直播。"
                        else -> "这个视频源没有找到有效的 HTTP(S) 播放地址"
                    },
                    Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        if (state.liveChannels.isNotEmpty() && filteredChannels.isEmpty()) {
            item { EmptyStatePanel("没有符合当前筛选条件的频道", Modifier.padding(horizontal = 20.dp)) }
        }
        items(filteredChannels, key = LiveChannel::id) { channel ->
            val programs = channel.epgId?.let(state.livePrograms::get).orEmpty()
            val stableKey = "$favoritePrefix${channel.id}"
            LiveChannelCard(
                channel = channel,
                programs = programs,
                favorite = stableKey in state.liveFavoriteKeys,
                onPlay = viewModel::playExternalEntry,
                onFavorite = { viewModel.toggleLiveFavorite(channel) },
                onCatchup = viewModel::playCatchup,
            )
        }
    }
}

@Composable
private fun LiveChannelCard(
    channel: LiveChannel,
    programs: List<LiveProgram>,
    favorite: Boolean,
    onPlay: (ExternalMediaEntry) -> Unit,
    onFavorite: () -> Unit,
    onCatchup: (ExternalMediaEntry, LiveProgram) -> Unit,
) {
    var selectedStream by remember(channel.id, channel.streams.size) { mutableIntStateOf(0) }
    val stream = channel.streams.getOrElse(selectedStream) { channel.streams.first() }
    val now = System.currentTimeMillis()
    val current = programs.firstOrNull { now in it.startEpochMs until it.endEpochMs }
    val next = programs.firstOrNull { it.startEpochMs >= (current?.endEpochMs ?: now) }
    val catchup = programs.filter { it.endEpochMs <= now }.takeLast(3)
    Card(
        onClick = { onPlay(stream) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).shadowTvFocus(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(58.dp).clip(MaterialTheme.shapes.medium),
                ) {
                    if (channel.logoUrl != null) {
                        AsyncImage(
                            model = channel.logoUrl,
                            contentDescription = channel.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.padding(6.dp),
                        )
                    } else {
                        Icon(Icons.Rounded.LiveTv, null, modifier = Modifier.padding(15.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(channel.title.withoutEmoji(), style = MaterialTheme.typography.titleMedium)
                    channel.group?.let {
                        Text(it.withoutEmoji(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        current?.let { "直播中 · ${it.title.withoutEmoji()}" } ?: "实时直播",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        if (favorite) "取消收藏" else "收藏频道",
                        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Rounded.PlayArrow, "播放")
            }
            current?.let { program ->
                val progress = ((now - program.startEpochMs).toFloat() /
                    (program.endEpochMs - program.startEpochMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatLiveTime(program.startEpochMs), style = MaterialTheme.typography.labelSmall)
                    Text(formatLiveTime(program.endEpochMs), style = MaterialTheme.typography.labelSmall)
                }
            }
            next?.let {
                Text(
                    "接下来 ${formatLiveTime(it.startEpochMs)} · ${it.title.withoutEmoji()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (channel.streams.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(channel.streams.indices.toList(), key = { channel.streams[it].id }) { index ->
                        FilterChip(
                            selected = selectedStream == index,
                            onClick = { selectedStream = index },
                            label = { Text("线路 ${index + 1}") },
                            leadingIcon = if (index == selectedStream) {
                                { Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp)) }
                            } else null,
                        )
                    }
                }
            }
            if (stream.catchupSource != null && catchup.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(catchup, key = { it.startEpochMs }) { program ->
                        FilledTonalButton(onClick = { onCatchup(stream, program) }) {
                            Icon(Icons.Rounded.History, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("${formatLiveTime(program.startEpochMs)} ${program.title.withoutEmoji()}", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun formatLiveTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

private const val LIVE_FAVORITES_FILTER = "__favorites__"

private suspend fun readExternalSourceFile(
    resolver: ContentResolver,
    uri: Uri,
): Pair<String, String> = withContext(Dispatchers.IO) {
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "本地视频源"
    val bytes = resolver.openInputStream(uri)?.use { it.readLimitedBytes(MAX_EXTERNAL_SOURCE_BYTES) }
        ?: throw IOException("无法打开这个文件")
    displayName to bytes.decodeToString()
}

private fun InputStream.readLimitedBytes(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, EXTERNAL_SOURCE_BUFFER_SIZE))
    val buffer = ByteArray(EXTERNAL_SOURCE_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw IOException("配置超过 2 MiB 安全上限")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private const val MAX_EXTERNAL_SOURCE_BYTES = 2 * 1024 * 1024
private const val EXTERNAL_SOURCE_BUFFER_SIZE = 8 * 1024

private fun episodeLabel(item: MediaItem): String = if (item.seriesName != null) {
    "${item.seriesName} · S${item.seasonNumber ?: 0}E${item.episodeNumber ?: 0}"
} else item.type

private fun mediaProgress(item: MediaItem): Float {
    val duration = item.runTimeTicks ?: return 0f
    if (duration <= 0) return 0f
    return (item.playbackPositionTicks.toDouble() / duration).toFloat().coerceIn(0f, 1f)
}

private fun formatCompactDuration(milliseconds: Long): String {
    val minutes = milliseconds / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
