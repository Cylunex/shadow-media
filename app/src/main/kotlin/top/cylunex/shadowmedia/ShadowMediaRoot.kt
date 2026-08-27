package top.cylunex.shadowmedia

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.compose.material3.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import top.cylunex.shadowmedia.model.MediaItem
import top.cylunex.shadowmedia.model.MediaLibrary
import top.cylunex.shadowmedia.model.PlaybackPlan
import top.cylunex.shadowmedia.model.embyTicksToMilliseconds
import top.cylunex.shadowmedia.playback.PlaybackRuntime

@Composable
fun ShadowMediaRoot(viewModel: MainViewModel, container: AppContainer) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize()) {
        when (state.screen) {
            Screen.LOGIN -> LoginScreen(state, viewModel)
            Screen.LIBRARIES -> LibraryScreen(state, viewModel)
            Screen.ITEMS -> ItemScreen(state, viewModel)
            Screen.PLAYER -> FeedScreen(state, viewModel, container)
        }
        if (state.isLoading && state.screen != Screen.PLAYER) LoadingOverlay()
        state.pendingDeleteItem?.let { item ->
            DeleteConfirmationDialog(
                item = item,
                isDeleting = state.isDeleting,
                onConfirm = viewModel::confirmDelete,
                onDismiss = viewModel::cancelDelete,
            )
        }
    }
}

@Composable
private fun LoginScreen(state: MainUiState, viewModel: MainViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Shadow Media", style = MaterialTheme.typography.headlineLarge)
        Text("连接 Emby，播放自己的媒体库")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = viewModel::updateServerUrl,
            label = { Text("Emby 地址") },
            placeholder = { Text("https://media.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.userName,
            onValueChange = viewModel::updateUserName,
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::updatePassword,
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.allowInsecureHttp, onCheckedChange = viewModel::updateAllowInsecure)
            Text("允许受信任局域网使用未加密 HTTP")
        }
        ErrorText(state.errorMessage)
        Button(
            onClick = viewModel::login,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("登录") }
    }
}

@Composable
private fun LibraryScreen(state: MainUiState, viewModel: MainViewModel) {
    ContentListHeader(
        title = "选择媒体库",
        subtitle = "${state.session?.userName.orEmpty()} · ${state.session?.serverUrl.orEmpty()}",
        actionText = "退出登录",
        onAction = viewModel::logout,
    )
    LazyColumn(Modifier.fillMaxSize().padding(top = 88.dp)) {
        items(state.libraries, key = MediaLibrary::id) { library ->
            ListCard(
                title = library.name,
                subtitle = library.collectionType ?: "媒体库",
                onClick = { viewModel.selectLibrary(library) },
            )
        }
        if (!state.isLoading && state.libraries.isEmpty()) {
            item { EmptyState("此账号没有可用媒体库") }
        }
        item { ErrorText(state.errorMessage, Modifier.padding(16.dp)) }
    }
}

@Composable
private fun ItemScreen(state: MainUiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    ContentListHeader(
        title = state.selectedLibrary?.name ?: "视频",
        subtitle = "共 ${state.items.size} 条 · 选择一条开始刷片",
        actionText = "媒体库",
        onAction = viewModel::back,
    )
    LazyColumn(Modifier.fillMaxSize().padding(top = 88.dp)) {
        items(state.items, key = MediaItem::id) { item ->
            val progress = item.playbackPositionTicks.takeIf { it > 0 }?.let {
                " · 续播 ${formatDuration(it.embyTicksToMilliseconds())}"
            }.orEmpty()
            ListCard(
                title = item.name,
                subtitle = item.episodeLabel() + progress,
                onClick = { viewModel.play(item) },
                onDelete = { viewModel.requestDelete(item) },
            )
        }
        if (!state.isLoading && state.items.isEmpty()) item { EmptyState("这个媒体库没有找到视频") }
        item { ErrorText(state.errorMessage, Modifier.padding(16.dp)) }
    }
}

@Composable
private fun FeedScreen(state: MainUiState, viewModel: MainViewModel, container: AppContainer) {
    BackHandler(onBack = viewModel::back)
    val pagerState = rememberPagerState(
        initialPage = state.currentIndex.coerceIn(0, (state.items.size - 1).coerceAtLeast(0)),
        pageCount = { state.items.size },
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect(viewModel::playAt)
    }

    VerticalPager(
        state = pagerState,
        key = { state.items[it].id },
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxSize().background(Color.Black),
    ) { page ->
        val item = state.items[page]
        val plan = state.playbackPlan?.takeIf {
            page == state.currentIndex && it.itemId == item.id
        }
        FeedPage(
            item = item,
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
            onDelete = { viewModel.requestDelete(item) },
        )
    }
}

@Composable
private fun FeedPage(
    item: MediaItem,
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
    onDelete: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isActive && plan != null) {
            ActivePlayer(state, item, plan, container, onRetry)
        } else if (isActive) {
            FeedPlaceholder(isLoading, errorMessage, onRetry)
        }

        Row(
            modifier = Modifier.align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) { Text("‹ 列表", color = Color.White) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${page + 1} / $pageCount", color = Color.White)
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }

        if (errorMessage != null && plan != null) {
            Card(
                Modifier.align(Alignment.TopCenter).statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 64.dp)
            ) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
        }

        if (!isActive || plan == null) {
            Column(
                modifier = Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.52f))
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Text(item.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(item.episodeLabel(), color = Color.White.copy(alpha = 0.78f))
                Text(
                    "上下滑动切换视频",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ActivePlayer(
    state: MainUiState,
    item: MediaItem,
    plan: PlaybackPlan,
    container: AppContainer,
    onRetry: () -> Unit,
) {
    val session = requireNotNull(state.session)
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val runtime = remember(plan) {
        PlaybackRuntime(
            context = context,
            session = session,
            plan = plan,
            repository = container.embyRepository,
            clientIdentity = container.clientIdentity,
            startPositionMs = item.playbackPositionTicks.embyTicksToMilliseconds(),
        )
    }
    val diagnostics by runtime.diagnostics.collectAsStateWithLifecycle()
    var showDiagnostics by remember(runtime) { mutableStateOf(false) }

    DisposableEffect(runtime, lifecycleOwner) {
        var resumeAfterBackground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resumeAfterBackground = runtime.player.playWhenReady
                    runtime.player.pause()
                }
                Lifecycle.Event.ON_START -> if (resumeAfterBackground) {
                    runtime.player.play()
                    resumeAfterBackground = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runtime.close()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Player(player = runtime.player, modifier = Modifier.fillMaxSize())

        TextButton(
            onClick = { showDiagnostics = !showDiagnostics },
            modifier = Modifier.statusBarsPadding().padding(top = 54.dp, start = 8.dp),
        ) { Text(if (showDiagnostics) "收起诊断" else "播放诊断", color = Color.White) }

        if (showDiagnostics || diagnostics.lastError != null) {
            Card(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 104.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("${diagnostics.method} · ${diagnostics.requestHost}")
                    Text(
                        "${diagnostics.videoType ?: "Video"} / ${diagnostics.container ?: "?"} · " +
                            "${diagnostics.videoCodec ?: "?"} / ${diagnostics.audioCodec ?: "?"} · " +
                            "链路 ${diagnostics.candidateIndex + 1}/${diagnostics.candidateCount}"
                    )
                    Text(
                        "媒体源 ${diagnostics.sourceCount} · " +
                            "DP ${diagnostics.supportsDirectPlay.asFlag()} · " +
                            "DS ${diagnostics.supportsDirectStream.asFlag()} · " +
                            "TC ${diagnostics.supportsTranscoding.asFlag()}"
                    )
                    diagnostics.lastError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onRetry) { Text("重新解析播放地址") }
                    }
                }
            }
        }

        PlaybackControls(
            item = item,
            runtime = runtime,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun PlaybackControls(
    item: MediaItem,
    runtime: PlaybackRuntime,
    modifier: Modifier = Modifier,
) {
    val player = runtime.player
    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var isPlaying by remember(player) { mutableStateOf(false) }
    var canSeek by remember(player) { mutableStateOf(false) }
    var isScrubbing by remember(player) { mutableStateOf(false) }

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
            .background(Color.Black.copy(alpha = 0.7f))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(item.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(item.episodeLabel(), color = Color.White.copy(alpha = 0.75f))
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
            TextButton(onClick = { if (isPlaying) player.pause() else player.play() }) {
                Text(if (isPlaying) "暂停" else "播放", color = Color.White)
            }
        }
    }
}

@Composable
private fun FeedPlaceholder(isLoading: Boolean, errorMessage: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text("正在解析播放地址…", color = Color.White)
        } else {
            Text("PlaybackInfo 诊断", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(errorMessage ?: "暂时无法播放这个视频", color = Color.White)
            Text(
                "播放地址尚未解析成功，因此这里还没有播放器级诊断。",
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun ContentListHeader(
    title: String,
    subtitle: String,
    actionText: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(88.dp).statusBarsPadding().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        OutlinedButton(onClick = onAction) { Text(actionText) }
    }
}

@Composable
private fun ListCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onClick).padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
        onDelete?.let {
            TextButton(onClick = it, modifier = Modifier.padding(end = 8.dp)) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    HorizontalDivider()
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
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(message) }
}

@Composable
private fun ErrorText(message: String?, modifier: Modifier = Modifier) {
    message?.let { Text(it, modifier = modifier, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun LoadingOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card { CircularProgressIndicator(Modifier.padding(20.dp).size(40.dp)) }
    }
}

private fun MediaItem.episodeLabel(): String = if (seriesName != null) {
    "$seriesName · S${seasonNumber ?: 0}E${episodeNumber ?: 0}"
} else {
    type
}

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun Boolean.asFlag(): String = if (this) "是" else "否"
