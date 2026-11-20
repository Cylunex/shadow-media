package com.shadow.media

import androidx.lifecycle.repeatOnLifecycle
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.compose.material3.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.shadow.media.model.MediaItem
import com.shadow.media.model.MediaFilter
import com.shadow.media.model.MediaLibrary
import com.shadow.media.model.MediaSegment
import com.shadow.media.model.MediaSort
import com.shadow.media.model.SegmentType
import com.shadow.media.model.EmbySession
import com.shadow.media.model.PlaybackPlan
import com.shadow.media.playback.PlaybackRuntime
import com.shadow.media.playback.ExternalPlaybackRuntime
import com.shadow.media.ui.ContinueFeedCard
import com.shadow.media.ui.EmbyArtwork
import com.shadow.media.ui.EmptyStatePanel
import com.shadow.media.ui.LibraryCard
import com.shadow.media.ui.LocalEmbyImageLoader
import com.shadow.media.ui.MediaPosterCard
import com.shadow.media.ui.PlayerTopBar
import com.shadow.media.ui.ScreenHeader
import com.shadow.media.ui.ShadowBackdrop
import com.shadow.media.ui.ServerCard
import com.shadow.media.ui.rememberEmbyImageLoader

@Composable
fun LegacyMediaRoot(
    viewModel: MainViewModel,
    container: AppContainer,
    rootScreen: Screen? = null,
    videoWarehouse: Boolean = true,
    onWarehouseBack: () -> Unit = viewModel::back,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rootContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.screen) {
        (rootContext.findActivity() as? MainActivity)?.setPlaybackActive(
            state.screen == Screen.PLAYER || state.screen == Screen.EXTERNAL_PLAYER
        )
    }
    val mdcPageState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val imageLoader = rememberEmbyImageLoader(state.session, container.clientIdentity)
    val videoControls = remember { VideoControlState() }
    DisposableEffect(Unit) { onDispose { (rootContext.findActivity() as? MainActivity)?.setPlaybackActive(false) } }
    CompositionLocalProvider(LocalEmbyImageLoader provides imageLoader, LocalVideoControlState provides videoControls) {
        ShadowBackdrop(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = if (state.screen == Screen.HOME) rootScreen ?: Screen.HOME else state.screen,
                transitionSpec = { fadeIn(tween(360)) togetherWith fadeOut(tween(180)) },
                label = "shadow-screen",
            ) { screen ->
            when (screen) {
                Screen.SERVERS -> ServerScreen(state, viewModel)
                Screen.LOGIN -> LoginScreen(state, viewModel)
                Screen.HOME -> MediaHomeScreen(state, viewModel)
                Screen.LIBRARIES -> LibraryScreen(state, viewModel)
                Screen.ITEMS -> ItemScreen(state, viewModel)
                Screen.DETAIL -> SeriesDetailScreen(state, viewModel)
                Screen.SOURCES -> mdcPageState.SaveableStateProvider("warehouse") {
                    if (videoWarehouse) VideoWarehouseScreen(state, viewModel, onWarehouseBack)
                    else ExternalSourcesScreen(state, viewModel)
                }
                Screen.EXTERNAL_ITEMS -> ExternalItemsScreen(state, viewModel)
                Screen.EXTERNAL_PLAYER -> ExternalPlayerScreen(state, viewModel, container)
                Screen.DISCOVER -> DiscoverScreen(state, viewModel)
                Screen.PROVIDER_DETAIL -> UnifiedDetailScreen(state, viewModel)
                Screen.MDC -> mdcPageState.SaveableStateProvider("mdc") { MdcScreen(viewModel) }
                Screen.NETWORK_STORAGES -> NetworkStorageScreen(viewModel)
                Screen.INTEGRATIONS -> IntegrationScreen(viewModel)
                Screen.INSIGHTS -> InsightsScreen(viewModel)
                Screen.SETTINGS -> SettingsScreen(viewModel)
                Screen.PLAYER -> FeedScreen(state, viewModel, container)
            }}
            if (state.isLoading && state.screen != Screen.PLAYER && state.screen != Screen.EXTERNAL_PLAYER) {
                LoadingOverlay()
            }
            state.pendingDeleteItem?.let { item ->
                DeleteConfirmationDialog(
                    item = item,
                    isDeleting = state.isDeleting,
                    onConfirm = viewModel::confirmDelete,
                    onDismiss = viewModel::cancelDelete,
                )
            }
            state.pendingRemoveSession?.let { session ->
                RemoveServerConfirmationDialog(
                    session = session,
                    onConfirm = viewModel::confirmRemoveServer,
                    onDismiss = viewModel::cancelRemoveServer,
                )
            }
        }
    }
}

@Composable
private fun ExternalPlayerScreen(state: MainUiState, viewModel: MainViewModel, container: AppContainer) {
    BackHandler(onBack = viewModel::back)
    val entry = state.selectedExternalEntry
    if (entry == null) {
        EmptyStatePanel("播放条目已不存在")
        return
    }
    if (entry.isDiscImage) {
        ExternalIsoPlayerScreen(entry, viewModel, container)
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { com.shadow.media.playback.VideoPreferenceStore.get(context) }
    val videoSettings by preferences.settings.collectAsStateWithLifecycle()
    var decoderResume by remember(entry.id, entry.url, entry.startPositionMs, entry.queueEntryId, entry.playbackRevision) { mutableStateOf<VideoRestartSnapshot?>(null) }
    val runtime = remember(entry.id, entry.url, entry.startPositionMs, entry.queueEntryId, entry.playbackRevision, videoSettings.decoder) {
        ExternalPlaybackRuntime(context.applicationContext,
            entry.copy(startPositionMs = decoderResume?.positionMs ?: entry.startPositionMs), container.networkStorageRepository,
            startPlayWhenReady = (decoderResume?.playing ?: entry.playWhenReady) && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            onTerminalError = { position, playing -> viewModel.externalPlaybackFailed(entry.id, position, playing) })
            .also { it.player.setPlaybackSpeed(decoderResume?.speed ?: 1f) }
    }
    val videoControls = LocalVideoControlState.current
    var showHandoff by remember { mutableStateOf(false) }
    var showCast by remember { mutableStateOf(false) }
    val inPip = (context.findActivity() as? MainActivity)?.inPictureInPicture == true
    val playbackError by runtime.error.collectAsStateWithLifecycle()
    val recovery by runtime.recovery.collectAsStateWithLifecycle()
    LaunchedEffect(runtime) {
        while (true) {
            kotlinx.coroutines.delay(5000)
            entry.queueEntryId?.let { container.videoQueue.position(it, container.castPlayback.returnedPosition(entry.sourceId, entry.id) ?: runtime.player.currentPosition) }
            container.recordExternalHistory(entry, runtime.player.currentPosition.coerceAtLeast(0L), runtime.player.duration.takeIf { it > 0 })
        }
    }
    DisposableEffect(runtime) {
        val listener = object : androidx.media3.common.Player.Listener {
            private val started = android.os.SystemClock.elapsedRealtime()
            private var healthy = false
            override fun onRenderedFirstFrame() {
                if (!healthy) { healthy = true; viewModel.externalPlaybackHealthy(entry, android.os.SystemClock.elapsedRealtime() - started) }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) container.castPlayback.takeReturnPosition(entry.sourceId, entry.id)?.let { if (runtime.player.isCurrentMediaItemSeekable) runtime.player.seekTo(it) }
            }
        }
        runtime.player.addListener(listener)
        onDispose { runtime.player.removeListener(listener) }
    }
    val focusRequester = remember(runtime) { FocusRequester() }
    LaunchedEffect(runtime) { runCatching { focusRequester.requestFocus() } }
    DisposableEffect(runtime, lifecycleOwner) {
        var resumeAfterBackground = false
        var backgroundAt = 0L
        runtime.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    backgroundAt = android.os.SystemClock.elapsedRealtime()
                    runtime.setForeground(false)
                    resumeAfterBackground = runtime.player.playWhenReady
                    runtime.player.pause()
                    container.recordExternalHistory(entry, runtime.player.currentPosition.coerceAtLeast(0L), runtime.player.duration.takeIf { it > 0 })
                }
                Lifecycle.Event.ON_START -> {
                    runtime.setForeground(true)
                    if (resumeAfterBackground && !videoControls.sleepExpired) {
                        if (backgroundAt > 0 && android.os.SystemClock.elapsedRealtime() - backgroundAt >= 60_000L && entry.sourceId != "local-video")
                            viewModel.refreshExternalPlayback(runtime.player.currentPosition, true)
                        runtime.player.play()
                    }
                    resumeAfterBackground = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            container.recordExternalHistory(
                entry,
                runtime.player.currentPosition.coerceAtLeast(0L),
                runtime.player.duration.takeIf { it > 0 },
            )
            runtime.close()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (videoControls.locked) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (runtime.player.isCurrentMediaItemSeekable) {
                            runtime.player.seekTo((runtime.player.currentPosition - TV_SEEK_STEP_MS).coerceAtLeast(0))
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        if (runtime.player.isCurrentMediaItemSeekable) {
                            runtime.player.seekTo(runtime.player.currentPosition + TV_SEEK_STEP_MS)
                        }
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.MediaPlayPause -> {
                        if (runtime.player.isPlaying) runtime.player.pause() else runtime.player.play()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        Player(player = runtime.player, modifier = Modifier.fillMaxSize(), showControls = !videoControls.locked && !inPip)
        VideoSubtitles(runtime.player)
        if (!inPip && !videoControls.locked) Row(
            modifier = Modifier.align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.58f))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::back) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(entry.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "外部源 · 无 Emby 凭据",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (!inPip && !videoControls.locked) PlaybackRecoveryNotice(
            state = recovery, error = playbackError ?: state.errorMessage, onRetry = runtime::retry,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 76.dp, start = 16.dp, end = 16.dp),
        )
        if (!inPip && !videoControls.locked && (playbackError != null || recovery.phase == com.shadow.media.playback.PlaybackRecoveryPhase.FAILED)) TextButton(
            onClick = { viewModel.refreshExternalPlayback(runtime.player.currentPosition, runtime.player.playWhenReady) },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 170.dp),
        ) { Text("重新解析来源") }
        if (entry.sourceId.startsWith("mdc:") && !inPip && !videoControls.locked &&
            (playbackError != null || recovery.phase == com.shadow.media.playback.PlaybackRecoveryPhase.FAILED)) {
            var matching by remember(entry.id) { mutableStateOf(false) }
            TextButton(onClick = { matching = true }, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 220.dp)) {
                Text("在影视仓找其他来源")
            }
            if (matching) MdcCrossSiteSearch(com.shadow.media.model.UnifiedMediaItem(
                com.shadow.media.model.MediaKey(entry.sourceId, entry.id.substringBefore(":version:")), entry.title, "Movie"
            ), viewModel) { matching = false }
        }
        if (!inPip && !videoControls.locked) LiveLinePicker(state, viewModel, entry, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 76.dp))
        VideoQueueControls(container, viewModel, entry, runtime.player,
            Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(bottom = 72.dp))
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { CastPlaybackBar(container) }
        if (showCast) CastPlaybackPanel(container, entry, runtime.player, onDismiss = { showCast = false }, prepare = {
            val provider = container.providerRegistry.provider(entry.sourceId)
            val candidate = provider?.resolve(com.shadow.media.model.UnifiedPlaybackRequest(com.shadow.media.model.MediaKey(provider.descriptor.id, entry.id), runtime.player.currentPosition))?.firstOrNull()
            val resolved = if (candidate == null) entry else entry.copy(url = candidate.url, requestHeaders = candidate.requiredHeaders, credentialOrigin = candidate.credentialOrigin)
            PreparedCastMedia(resolved, runtime.player.currentPosition, com.shadow.media.playback.castMimeType(resolved.url))
        })
        if (showHandoff) DeviceHandoffPanel(container.deviceHandoff, viewModel,
            { container.deviceHandoff.playback(entry.sourceId, entry.id, entry.title, runtime.player.currentPosition) }, { showHandoff = false })
        VideoPlayerFeatures(runtime.player, matchedSubtitleCount = entry.subtitles.size, onHandoff = { showHandoff = true }, onSubtitle = runtime::addSubtitle, onCast = { showCast = true }, preferenceKey = "${entry.sourceId}:${entry.id}",
            onDecoderChange = { mode ->
                decoderResume = VideoRestartSnapshot(runtime.player.currentPosition, runtime.player.playWhenReady, runtime.player.playbackParameters.speed)
                preferences.update { it.copy(decoder = mode) }
            })
    }
}

@Composable
private fun ServerScreen(state: MainUiState, viewModel: MainViewModel) {
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "你的服务器",
                subtitle = "${state.savedSessions.size} 个已保存登录 · 凭据相互隔离",
                actionLabel = "添加服务器",
                onAction = viewModel::addServer,
                actionIsAdd = true,
            )
        }
        items(state.savedSessions, key = { "${it.serverUrl}:${it.serverId}:${it.userId}" }) { session ->
            ServerCard(
                session = session,
                onClick = { viewModel.selectServer(session) },
                onRemove = { viewModel.requestRemoveServer(session) },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        if (state.savedSessions.isEmpty()) {
            item { EmptyStatePanel("还没有服务器，先添加一个 Emby 登录", Modifier.padding(horizontal = 20.dp)) }
        }
        item {
            OutlinedButton(
                onClick = viewModel::showNetworkStorages,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Icon(Icons.Rounded.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("打开网络媒体库")
            }
        }
        item { ErrorText(state.errorMessage, Modifier.padding(horizontal = 20.dp)) }
    }
}

@Composable
private fun LoginScreen(state: MainUiState, viewModel: MainViewModel) {
    if (state.savedSessions.isNotEmpty()) BackHandler(onBack = viewModel::back)
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                colors = listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.54f), Color.Transparent),
                radius = 920f,
            )
        )
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            item {
                Text("SHADOW", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("连接你的片库", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "登录 Emby，开始专注、连续的私人刷片体验。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.serverUrl,
                            onValueChange = viewModel::updateServerUrl,
                            label = { Text("Emby 地址") },
                            placeholder = { Text("https://media.example.com") },
                            leadingIcon = { Icon(Icons.Rounded.Movie, null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.userName,
                            onValueChange = viewModel::updateUserName,
                            label = { Text("用户名") },
                            leadingIcon = { Icon(Icons.Rounded.Person, null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = viewModel::updatePassword,
                            label = { Text("密码") },
                            leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = state.allowInsecureHttp, onCheckedChange = viewModel::updateAllowInsecure)
                            Text(
                                "允许受信任局域网使用 HTTP",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        ErrorText(state.errorMessage)
                        Button(
                            onClick = viewModel::login,
                            enabled = !state.isLoading,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("连接服务器") }
                    }
                }
                if (state.savedSessions.isNotEmpty()) {
                    TextButton(onClick = viewModel::showServers, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("返回服务器列表")
                    }
                }
                TextButton(onClick = viewModel::showNetworkStorages, modifier = Modifier.fillMaxWidth()) {
                    Text("不使用 Emby，打开 OpenList / WebDAV / SMB")
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(state: MainUiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenHeader(
                title = "选择片库",
                subtitle = "${state.session?.userName.orEmpty()} · ${state.session?.serverUrl.orEmpty()}",
                actionLabel = "媒体中心",
                onAction = viewModel::showHome,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedButton(onClick = viewModel::showMdc, modifier = Modifier.fillMaxWidth()) { Text("Shadow MDC 片库") }
        }
        if (state.lastFeedLibraryId != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ContinueFeedCard(state.currentIndex, viewModel::resumeLastFeed)
            }
        }
        gridItems(state.libraries, key = MediaLibrary::id) { library ->
            LibraryCard(state.session, library) { viewModel.selectLibrary(library) }
        }
        if (!state.isLoading && state.libraries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyStatePanel("此账号没有可用媒体库") }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { ErrorText(state.errorMessage) }
    }
}

@Composable
private fun ItemScreen(state: MainUiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenHeader(
                title = (listOfNotNull(state.selectedLibrary?.name) + state.wallFolders.map { it.name }).joinToString(" / ").ifBlank { "视频" },
                subtitle = "${state.wallTotalCount} 部内容 · 已加载 ${state.wallItems.size} 部",
                actionLabel = "媒体库",
                onAction = viewModel::back,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.wallSearch,
                    onValueChange = viewModel::updateWallSearch,
                    label = { Text("搜索当前媒体库") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = viewModel::submitWallSearch) {
                            Icon(Icons.Rounded.Search, "搜索")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MediaFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.wallFilter == filter,
                            onClick = { viewModel.setWallFilter(filter) },
                            label = { Text(filter.label()) },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MediaSort.entries.forEach { sort ->
                        FilterChip(
                            selected = state.wallSort == sort,
                            onClick = { viewModel.setWallSort(sort) },
                            label = { Text(sort.label()) },
                        )
                    }
                }
            }
        }
        if (state.wallItems.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        FeedOrder.entries.forEach { order ->
                            FilterChip(selected = state.feedFilter.order == order, onClick = {
                                viewModel.setFeedFilter(state.feedFilter.copy(order = order))
                            }, label = { Text(order.label) })
                        }
                    }
                    FilterChip(selected = state.feedFilter.hideSeen, onClick = {
                        viewModel.setFeedFilter(state.feedFilter.copy(hideSeen = !state.feedFilter.hideSeen))
                    }, label = { Text("跳过刷过的影片") })
                    ContinueFeedCard(state.currentIndex, viewModel::startLibraryFeed)
                }
            }
        }
        gridItems(state.wallItems, key = MediaItem::id) { item ->
            val durationTicks = item.runTimeTicks ?: 0L
            val progress = if (durationTicks > 0) {
                (item.playbackPositionTicks.toDouble() / durationTicks).toFloat().coerceIn(0f, 1f)
            } else 0f
            MediaPosterCard(
                session = state.session,
                item = item,
                episodeLabel = item.episodeLabel(),
                progress = progress,
                onClick = { viewModel.openCatalogItem(item) },
                onDelete = if (item.type.equals("Series", true) || item.type.equals("BoxSet", true)) {
                    null
                } else {
                    { viewModel.requestDelete(item) }
                },
                onFavorite = { viewModel.toggleFavorite(item) },
                onOffline = if (item.type in setOf("Movie", "Video", "Episode")) ({ viewModel.downloadEmby(item) }) else null,
            )
        }
        if (state.wallHasMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FilledTonalButton(
                    onClick = viewModel::loadMoreWall,
                    enabled = !state.isLoadingMore,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.isLoadingMore) "正在加载…" else "加载更多") }
            }
        }
        if (!state.isLoading && state.wallItems.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyStatePanel("这个媒体库没有找到视频") }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { ErrorText(state.errorMessage) }
    }
}

@Composable
private fun FeedScreen(state: MainUiState, viewModel: MainViewModel, container: AppContainer) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        viewModel.setResolutionForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> viewModel.setResolutionForeground(true)
            Lifecycle.Event.ON_STOP -> viewModel.setResolutionForeground(false)
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); viewModel.setResolutionForeground(false) }
    }
    BackHandler(onBack = viewModel::back)
    LaunchedEffect(state.insightMessage) {
        if (state.insightMessage != null) {
            delay(2_500)
            viewModel.clearInsightMessage()
        }
    }
    val pagerState = rememberPagerState(
        initialPage = state.currentIndex.coerceIn(0, (state.items.size - 1).coerceAtLeast(0)),
        pageCount = { state.items.size },
    )
    val scope = rememberCoroutineScope()
    val feedPreload = rememberFeedPreloading(state, viewModel, container, pagerState.isScrollInProgress)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect(viewModel::playAt)
    }

    val videoControls = LocalVideoControlState.current
    VerticalPager(
        state = pagerState,
        userScrollEnabled = !videoControls.locked,
        key = { state.items[it].id },
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (videoControls.locked) return@onPreviewKeyEvent false
            val target = when (event.key) {
                Key.DirectionDown, Key.PageDown -> (pagerState.currentPage + 1).coerceAtMost(state.items.lastIndex)
                Key.DirectionUp, Key.PageUp -> (pagerState.currentPage - 1).coerceAtLeast(0)
                else -> return@onPreviewKeyEvent false
            }
            if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target) }
            true
        },
    ) { page ->
        val item = state.items[page]
        val plan = state.playbackPlan?.takeIf {
            page == state.currentIndex && it.itemId == item.id
        }
        FeedPage(
            item = item,
            feedPreload = feedPreload,
            page = page,
            pageCount = state.items.size,
            plan = plan,
            isActive = page == state.currentIndex,
            isLoading = state.isLoading,
            errorMessage = state.errorMessage,
            state = state,
            container = container,
            onBack = viewModel::back,
            onRetry = viewModel::retryPlayback,
            onRetryCurrent = viewModel::retryPlayback,
            onResolutionPause = viewModel::toggleResolutionPause,
            onTerminalError = viewModel::recoverPlayback,
            onExternalPlaybackStarted = viewModel::externalPlaybackStarted,
            onExternalPlaybackStopped = viewModel::externalPlaybackStopped,
            onSaveMoment = viewModel::saveMoment,
            onSaveSegment = viewModel::saveSegment,
            onDelete = { viewModel.requestDelete(item) },
        )
    }
}

@Composable
private fun FeedPage(
    item: MediaItem,
    feedPreload: com.shadow.media.playback.FeedPreloadPool?,
    page: Int,
    pageCount: Int,
    plan: PlaybackPlan?,
    isActive: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    state: MainUiState,
    container: AppContainer,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRetryCurrent: (Long, Boolean) -> Unit,
    onResolutionPause: () -> Unit,
    onTerminalError: (Long, String) -> Unit,
    onExternalPlaybackStarted: (PlaybackPlan, Long) -> Unit,
    onExternalPlaybackStopped: (PlaybackPlan, Long) -> Unit,
    onSaveMoment: (MediaItem, Long) -> Unit,
    onSaveSegment: (MediaItem, SegmentType, Long, Long) -> Unit,
    onDelete: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        EmbyArtwork(
            session = state.session,
            itemId = item.id,
            title = item.name,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(alpha = 0.28f),
                        0.2f to Color.Transparent,
                        0.56f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.92f),
                    )
                )
            )
        )
        if (isActive && plan != null) {
            if (plan.isDiscImage()) {
                IsoPlayer(
                    state = state,
                    item = item,
                    plan = plan,
                    container = container,
                    onRetry = onRetry,
                    onTerminalError = onTerminalError,
                    onStarted = onExternalPlaybackStarted,
                    onStopped = onExternalPlaybackStopped,
                    onSaveMoment = onSaveMoment,
                    onSaveSegment = onSaveSegment,
                )
            } else {
                ActivePlayer(
                    feedPreload = feedPreload,
                    state = state,
                    item = item,
                    plan = plan,
                    container = container,
                    onRetryCurrent = onRetryCurrent,
                    onTerminalError = onTerminalError,
                    onSaveMoment = onSaveMoment,
                    onSaveSegment = onSaveSegment,
                )
            }
        } else if (isActive) {
            FeedPlaceholder(isLoading, errorMessage, onRetry)
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp)) {
                PlaybackRecoveryNotice(state.playbackResolutionRecovery, onRetry)
                TextButton(onClick = onResolutionPause) { Text(if (state.playbackPlayWhenReady) "暂停自动恢复" else "继续自动恢复") }
            }
        }

        if (!LocalVideoControlState.current.locked && (androidx.compose.ui.platform.LocalContext.current.findActivity() as? MainActivity)?.inPictureInPicture != true) PlayerTopBar(
            page = page,
            pageCount = pageCount,
            onBack = onBack,
            onDelete = onDelete,
            modifier = Modifier.align(Alignment.TopCenter)
                .statusBarsPadding(),
        )

        if (errorMessage != null && plan != null) {
            Card(
                Modifier.align(Alignment.TopCenter).statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 76.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xE81B1114),
                    contentColor = Color.White,
                ),
            ) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
        }

        state.insightMessage?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 28.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xEB19371D),
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        if (!isActive || plan == null) {
            Column(
                modifier = Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 88.dp, top = 72.dp, bottom = 22.dp),
            ) {
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.14f)) {
                    Text(
                        item.type.uppercase(),
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                Spacer(Modifier.height(9.dp))
                Text(item.name, color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(
                    item.episodeLabel(),
                    color = Color.White.copy(alpha = 0.74f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "上滑继续 · 长按画面可暂停",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ActivePlayer(
    feedPreload: com.shadow.media.playback.FeedPreloadPool?,
    state: MainUiState,
    item: MediaItem,
    plan: PlaybackPlan,
    container: AppContainer,
    onRetryCurrent: (Long, Boolean) -> Unit,
    onTerminalError: (Long, String) -> Unit,
    onSaveMoment: (MediaItem, Long) -> Unit,
    onSaveSegment: (MediaItem, SegmentType, Long, Long) -> Unit,
) {
    val session = requireNotNull(state.session)
    val context = androidx.compose.ui.platform.LocalContext.current
    val videoControls = LocalVideoControlState.current
    val inPip = (context.findActivity() as? MainActivity)?.inPictureInPicture == true
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { com.shadow.media.playback.VideoPreferenceStore.get(context) }
    val videoSettings by preferences.settings.collectAsStateWithLifecycle()
    var decoderResume by remember(session, plan, state.playbackStartPositionMs) { mutableStateOf<VideoRestartSnapshot?>(null) }
    val runtime = remember(session, plan, state.playbackStartPositionMs, feedPreload, videoSettings.decoder) {
        PlaybackRuntime(
            context = context,
            feedPreload = feedPreload,
            session = session,
            plan = plan,
            playbackOutbox = container.playbackOutbox,
            clientIdentity = container.clientIdentity,
            startPositionMs = decoderResume?.positionMs ?: state.playbackStartPositionMs,
            startPlayWhenReady = (decoderResume?.playing ?: state.playbackPlayWhenReady) && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            mediaTitle = item.name,
            telemetrySink = container.playbackTelemetry,
            onTerminalError = onTerminalError,
        ).also { it.player.setPlaybackSpeed(decoderResume?.speed ?: 1f) }
    }
    val syncOwner = container.deviceHandoff.accountId
    LaunchedEffect(runtime, syncOwner) {
        if (syncOwner != null) lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                container.deviceHandoff.observe(session.providerId, item.id, item.name, runtime.currentPositionMs)
                delay(3000)
            }
        }
    }
    val diagnostics by runtime.diagnostics.collectAsStateWithLifecycle()
    val recovery by runtime.recovery.collectAsStateWithLifecycle()
    val pendingReports by container.playbackOutbox.pendingCount.collectAsStateWithLifecycle()
    var showHandoff by remember { mutableStateOf(false) }
    var showCast by remember { mutableStateOf(false) }
    var showDiagnostics by remember(runtime) { mutableStateOf(false) }
    DisposableEffect(runtime) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) container.castPlayback.takeReturnPosition(session.providerId, item.id)?.let(runtime::seekTo)
            }
        }
        runtime.player.addListener(listener)
        onDispose { runtime.player.removeListener(listener) }
    }
    val focusRequester = remember(runtime) { FocusRequester() }

    LaunchedEffect(runtime) { runCatching { focusRequester.requestFocus() } }

    DisposableEffect(runtime, lifecycleOwner) {
        var resumeAfterBackground = false
        var backgroundAt = 0L
        runtime.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    backgroundAt = android.os.SystemClock.elapsedRealtime()
                    runtime.setForeground(false)
                    resumeAfterBackground = runtime.player.playWhenReady
                    runtime.player.pause()
                }
                Lifecycle.Event.ON_START -> {
                    runtime.setForeground(true)
                    if (resumeAfterBackground && !videoControls.sleepExpired) {
                        if (backgroundAt > 0 && android.os.SystemClock.elapsedRealtime() - backgroundAt >= 60_000L)
                            onRetryCurrent(runtime.currentPositionMs, true)
                        else runtime.player.play()
                    }
                    resumeAfterBackground = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            container.recordEmbyHistory(session, item, runtime.currentPositionMs, runtime.durationMs)
            runtime.close()
        }
    }

    Box(
        Modifier.fillMaxSize()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (videoControls.locked) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        runtime.seekTo(runtime.currentPositionMs - TV_SEEK_STEP_MS)
                        true
                    }
                    Key.DirectionRight -> {
                        runtime.seekTo(runtime.currentPositionMs + TV_SEEK_STEP_MS)
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.MediaPlayPause -> {
                        if (runtime.player.isPlaying) runtime.player.pause() else runtime.player.play()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        Player(player = runtime.player, modifier = Modifier.fillMaxSize())
        VideoSubtitles(runtime.player)

        if (!inPip && !videoControls.locked) FilledTonalButton(
            onClick = { showDiagnostics = !showDiagnostics },
            modifier = Modifier.statusBarsPadding().padding(top = 68.dp, start = 12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Rounded.BugReport, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.size(6.dp))
            Text(if (showDiagnostics) "收起诊断" else "播放诊断")
        }

        if (!inPip && !videoControls.locked && !showDiagnostics) PlaybackRecoveryNotice(
            state = recovery, onRetry = runtime::retry,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 124.dp, start = 16.dp, end = 16.dp),
        )
        if (!inPip && !videoControls.locked && (showDiagnostics || (diagnostics.lastError != null && recovery.message == null))) {
            Card(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 124.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xEE111713),
                    contentColor = Color.White,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("播放链路", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text(
                        buildString {
                            append(diagnostics.method)
                            append(" · ")
                            append(diagnostics.requestHost)
                            diagnostics.resolvedHost?.takeIf { it != diagnostics.requestHost }?.let {
                                append(" → ")
                                append(it)
                            }
                        },
                        color = Color.White,
                    )
                    Text(
                        "HTTP ${diagnostics.httpStatus ?: "等待"} · " +
                            "跳转 ${diagnostics.redirectCount} · " +
                            "尝试 ${diagnostics.networkAttempt}/3 · " +
                            "响应头 ${diagnostics.responseHeadersMs?.let { "${it}ms" } ?: "等待"}",
                        color = Color.White.copy(alpha = 0.74f),
                    )
                    Text(
                        "${diagnostics.videoType ?: "Video"} / ${diagnostics.container ?: "?"} · " +
                            "${diagnostics.videoCodec ?: "?"} / ${diagnostics.audioCodec ?: "?"} · " +
                            "链路 ${diagnostics.candidateIndex + 1}/${diagnostics.candidateCount}",
                        color = Color.White.copy(alpha = 0.74f),
                    )
                    Text("进度同步队列：$pendingReports", color = Color.White.copy(alpha = 0.74f))
                    Text(
                        "媒体源 ${diagnostics.sourceCount} · " +
                            "DP ${diagnostics.supportsDirectPlay.asFlag()} · " +
                            "DS ${diagnostics.supportsDirectStream.asFlag()} · " +
                            "TC ${diagnostics.supportsTranscoding.asFlag()}",
                        color = Color.White.copy(alpha = 0.74f),
                    )
                    recovery.message?.let {
                        Text(it)
                        TextButton(onClick = runtime::retry) { Text("从当前位置重试") }
                    }
                    diagnostics.lastError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { onRetryCurrent(runtime.currentPositionMs, runtime.player.playWhenReady) }) { Text("重新解析播放地址") }
                    }
                }
            }
        }

        if (!inPip && !videoControls.locked) PlaybackControls(
            item = item,
            runtime = runtime,
            segments = state.mediaSegments,
            onSaveMoment = { onSaveMoment(item, it) },
            onSaveSegment = { type, startMs, endMs -> onSaveSegment(item, type, startMs, endMs) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { CastPlaybackBar(container) }
        if (showCast) CastPlaybackPanel(container,
            com.shadow.media.model.ExternalMediaEntry(item.id, session.providerId, item.name, plan.primary.url), runtime.player,
            position = { runtime.currentPositionMs }, onDismiss = { showCast = false }, onTransfer = runtime::transferReportingToRemote,
            prepare = {
                val position = runtime.currentPositionMs
                val remotePlan = container.embyRepository.playbackPlan(session, item.id)
                require(container.sessionStore.loadAll().any { it == session })
                val candidate = remotePlan.primary
                val offset = if (candidate.method == com.shadow.media.model.PlayMethod.TRANSCODE) position else 0L
                val url = if (offset > 0) candidate.url.toHttpUrl().newBuilder().setQueryParameter("StartTimeTicks", (offset * 10000).toString()).build().toString() else candidate.url
                PreparedCastMedia(com.shadow.media.model.ExternalMediaEntry(item.id, session.providerId, item.name, url,
                    requestHeaders = candidate.requiredHeaders, credentialOrigin = candidate.credentialOrigin ?: session.serverUrl), position,
                    if (candidate.method == com.shadow.media.model.PlayMethod.TRANSCODE) "application/vnd.apple.mpegurl" else com.shadow.media.playback.castMimeType("file.${remotePlan.container ?: "mp4"}"), offset,
                    onProgress = { event, at, paused ->
                        if (container.sessionStore.loadAll().any { it == session }) container.playbackOutbox.submit(session,
                            com.shadow.media.network.PlaybackReport(item.id, candidate.mediaSourceId ?: remotePlan.mediaSourceId, remotePlan.playSessionId,
                                at.coerceIn(0, Long.MAX_VALUE / 10000) * 10000, paused, true, event, candidate.method))
                    })
            })
        if (showHandoff) DeviceHandoffPanel(container.deviceHandoff, null,
            { container.deviceHandoff.playback(session.providerId, item.id, item.name, runtime.currentPositionMs) }, { showHandoff = false })
        VideoPlayerFeatures(runtime.player, onHandoff = { showHandoff = true }, onCast = { showCast = true }, position = { runtime.currentPositionMs },
            duration = { runtime.durationMs }, canSeek = { runtime.isSeekSupported }, seek = runtime::seekTo,
            preferenceKey = "${session.providerId}:${plan.itemId}", onDecoderChange = { mode ->
                decoderResume = VideoRestartSnapshot(runtime.currentPositionMs, runtime.player.playWhenReady, runtime.player.playbackParameters.speed)
                preferences.update { it.copy(decoder = mode) }
            })
    }
}

@Composable
private fun PlaybackControls(
    item: MediaItem,
    runtime: PlaybackRuntime,
    segments: List<MediaSegment>,
    onSaveMoment: (Long) -> Unit,
    onSaveSegment: (SegmentType, Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = runtime.player
    val context = androidx.compose.ui.platform.LocalContext.current
    val tracks by runtime.tracks.collectAsStateWithLifecycle()
    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var isPlaying by remember(player) { mutableStateOf(false) }
    var canSeek by remember(player) { mutableStateOf(false) }
    var isScrubbing by remember(player) { mutableStateOf(false) }
    val activeSegment = segments.firstOrNull { positionMs in it.startMs until it.endMs }

    LaunchedEffect(player) {
        while (isActive) {
            val duration = runtime.durationMs
            durationMs = duration
            if (!isScrubbing) positionMs = runtime.currentPositionMs
            isPlaying = player.isPlaying
            canSeek = runtime.isSeekSupported && duration > 0
            delay(250L)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Color(0xE6111612))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(item.type.uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        Text(
            item.name,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(item.episodeLabel(), color = Color.White.copy(alpha = 0.68f), maxLines = 1)
        Slider(
            value = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)).toFloat(),
            onValueChange = {
                isScrubbing = true
                positionMs = it.toLong()
            },
            onValueChangeFinished = {
                if (canSeek) runtime.seekTo(positionMs)
                isScrubbing = false
            },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            enabled = canSeek,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!canSeek) {
                Text("当前链路不可拖动", color = Color.White.copy(alpha = 0.65f))
            }
            FilledTonalButton(onClick = { if (isPlaying) player.pause() else player.play() }) {
                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.size(6.dp))
                Text(if (isPlaying) "暂停" else "播放")
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tracks.audioTracks.size > 1) {
                TextButton(onClick = runtime::nextAudioTrack) {
                    Icon(Icons.Rounded.Headphones, null)
                    Spacer(Modifier.size(6.dp))
                    Text(tracks.audioTracks.getOrNull(tracks.selectedAudioIndex) ?: "音轨")
                }
            }
            if (tracks.subtitleTracks.isNotEmpty()) {
                TextButton(onClick = runtime::nextSubtitleTrack) {
                    Icon(Icons.Rounded.Subtitles, null)
                    Spacer(Modifier.size(6.dp))
                    Text(tracks.subtitleTracks.getOrNull(tracks.selectedSubtitleIndex) ?: "字幕关闭")
                }
            }
            TextButton(onClick = { onSaveMoment(positionMs) }) {
                Icon(Icons.Rounded.BookmarkAdd, null)
                Spacer(Modifier.size(6.dp))
                Text("保存时刻")
            }
            if (activeSegment != null && canSeek) {
                TextButton(onClick = { runtime.seekTo(activeSegment.endMs) }) {
                    Icon(Icons.Rounded.SkipNext, null)
                    Spacer(Modifier.size(6.dp))
                    Text(activeSegment.type.skipLabel())
                }
            } else {
                TextButton(
                    onClick = { onSaveSegment(SegmentType.INTRO, 0L, positionMs) },
                    enabled = positionMs >= 5_000,
                ) { Text("标记片头终点") }
                TextButton(
                    onClick = { onSaveSegment(SegmentType.CREDITS, positionMs, durationMs) },
                    enabled = durationMs > positionMs,
                ) { Text("标记片尾起点") }
            }
            TextButton(onClick = { context.enterShadowPictureInPicture() }) {
                Icon(Icons.Rounded.PictureInPictureAlt, null)
                Spacer(Modifier.size(6.dp))
                Text("画中画")
            }
        }
    }
}

internal fun SegmentType.skipLabel(): String = when (this) {
    SegmentType.INTRO -> "跳过片头"
    SegmentType.RECAP -> "跳过回顾"
    SegmentType.CREDITS -> "跳过片尾"
    SegmentType.PREVIEW -> "跳过预告"
    SegmentType.HIGHLIGHT -> "跳过片段"
    SegmentType.CHAPTER -> "下一章节"
}

@Composable
private fun FeedPlaceholder(isLoading: Boolean, errorMessage: String?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.padding(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xE6111612),
                contentColor = Color.White,
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text("正在解析播放地址…", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("正在检查 Direct Play、转封装与转码链路", color = Color.White.copy(alpha = 0.6f))
                } else {
                    Icon(Icons.Rounded.BugReport, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(30.dp))
                    Text("PlaybackInfo 诊断", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        errorMessage ?: "暂时无法播放这个视频",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "播放地址尚未解析成功，因此这里还没有播放器级诊断。",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, null)
                        Spacer(Modifier.size(6.dp))
                        Text("重新解析")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    item: MediaItem,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("永久删除？") },
        text = {
            Text(
                "将从 Emby 媒体库和服务器文件系统删除“${item.name}”。" +
                    "此操作无法在 Shadow Media 中撤销。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isDeleting) {
                Text(if (isDeleting) "正在删除…" else "确认删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) { Text("取消") }
        },
    )
}

@Composable
private fun RemoveServerConfirmationDialog(
    session: EmbySession,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移除服务器登录？") },
        text = {
            Text("将移除 ${session.userName} 在 ${session.serverUrl} 的本地登录信息，不会删除媒体文件。")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认移除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ErrorText(message: String?, modifier: Modifier = Modifier) {
    message?.let { Text(it, modifier = modifier, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun LoadingOverlay() {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(
                Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Text("正在同步媒体库…", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

private fun MediaItem.episodeLabel(): String = if (seriesName != null) {
    "$seriesName · S${seasonNumber ?: 0}E${episodeNumber ?: 0}"
} else {
    type
}

internal fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun Boolean.asFlag(): String = if (this) "是" else "否"

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.enterShadowPictureInPicture() {
    val activity = findActivity() ?: return
    activity.enterPictureInPictureMode(
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
    )
}

private fun MediaFilter.label(): String = when (this) {
    MediaFilter.ALL -> "全部"
    MediaFilter.UNPLAYED -> "未看"
    MediaFilter.RESUMABLE -> "续播"
    MediaFilter.FAVORITES -> "收藏"
    MediaFilter.PLAYED -> "已看"
}

private fun MediaSort.label(): String = when (this) {
    MediaSort.DATE_ADDED -> "最近加入"
    MediaSort.NAME -> "名称"
    MediaSort.PREMIERE_DATE -> "首映日期"
    MediaSort.RATING -> "评分"
    MediaSort.RANDOM -> "随机换一批"
}

private fun PlaybackPlan.isDiscImage(): Boolean =
    videoType.equals("Iso", ignoreCase = true) ||
        container?.split(',')?.any { it.equals("iso", ignoreCase = true) } == true

internal const val TV_SEEK_STEP_MS = 10_000L

internal data class VideoRestartSnapshot(val positionMs: Long, val playing: Boolean, val speed: Float)
