package com.shadow.media

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.shadow.media.model.MediaItem
import com.shadow.media.model.MediaSegment
import com.shadow.media.model.SegmentType
import com.shadow.media.model.PlaybackPlan
import com.shadow.media.playback.MpvIsoPlaybackRuntime
import com.shadow.media.playback.MpvIsoPlaybackState

@Composable
internal fun ExternalIsoPlayerScreen(
    entry: com.shadow.media.model.ExternalMediaEntry,
    viewModel: MainViewModel,
    container: AppContainer,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val runtime = remember(entry.id, entry.url, entry.startPositionMs, entry.playbackRevision) {
        MpvIsoPlaybackRuntime(context.applicationContext, entry, container.networkStorageRepository, onTerminalError = { position, _ -> viewModel.refreshExternalPlayback(position, true, automatic = true) })
    }
    val playbackState by runtime.state.collectAsStateWithLifecycle()
    val recovery by runtime.recovery.collectAsStateWithLifecycle()
    val diagnostics by runtime.diagnostics.collectAsStateWithLifecycle()
    val focusRequester = remember(runtime) { FocusRequester() }
    LaunchedEffect(runtime) { runCatching { focusRequester.requestFocus() } }

    DisposableEffect(runtime, lifecycleOwner) {
        var resumeAfterBackground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    runtime.setForeground(false)
                    resumeAfterBackground = runtime.state.value.isPlaying
                    runtime.pause()
                }
                Lifecycle.Event.ON_START -> {
                    runtime.setForeground(true)
                    if (resumeAfterBackground) {
                    runtime.play()
                    resumeAfterBackground = false
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val snapshot = runtime.state.value
            container.recordExternalHistory(entry, snapshot.positionMs, snapshot.durationMs.takeIf { it > 0 })
            runtime.close()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        runtime.seekTo((playbackState.positionMs - TV_SEEK_STEP_MS).coerceAtLeast(0))
                        true
                    }
                    Key.DirectionRight -> {
                        runtime.seekTo(playbackState.positionMs + TV_SEEK_STEP_MS)
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.MediaPlayPause -> {
                        if (playbackState.isPlaying) runtime.pause() else runtime.play()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { SurfaceView(it).also(runtime::attach) },
            update = runtime::attach,
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.58f)).statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::back) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(entry.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "网络 ISO · libmpv 光盘引擎",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        PlaybackRecoveryNotice(recovery, runtime::retry, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 76.dp, start = 16.dp, end = 16.dp))
        ExternalIsoControls(
            title = entry.title,
            runtime = runtime,
            state = playbackState,
            sourceStats = diagnostics.source,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        playbackState.error?.let { message ->
            Card(
                modifier = Modifier.align(Alignment.Center).padding(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xE81B1114), contentColor = Color.White),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ISO 播放失败", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    Text(message, color = Color.White.copy(alpha = 0.84f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ExternalIsoControls(
    title: String,
    runtime: MpvIsoPlaybackRuntime,
    state: MpvIsoPlaybackState,
    sourceStats: com.fongmi.android.tv.player.iso.IsoSourceStats,
    modifier: Modifier = Modifier,
) {
    var positionMs by remember(runtime) { mutableLongStateOf(state.positionMs) }
    var isScrubbing by remember(runtime) { mutableStateOf(false) }
    LaunchedEffect(state.positionMs, isScrubbing) {
        if (!isScrubbing) positionMs = state.positionMs
    }
    Column(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Color(0xE6111612)).navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text("DISC IMAGE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Slider(
            value = positionMs.coerceIn(0L, state.durationMs.coerceAtLeast(0L)).toFloat(),
            onValueChange = { isScrubbing = true; positionMs = it.toLong() },
            onValueChangeFinished = { runtime.seekTo(positionMs); isScrubbing = false },
            valueRange = 0f..state.durationMs.coerceAtLeast(1L).toFloat(),
            enabled = state.isSeekable && state.durationMs > 0,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("${formatDuration(positionMs)} / ${formatDuration(state.durationMs)}", color = Color.White)
                Text(
                    "随机读取 ${sourceStats.requestCount} 次 · ${sourceStats.upstreamHost ?: "网络存储"}",
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FilledTonalButton(onClick = { if (state.isPlaying) runtime.pause() else runtime.play() }) {
                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.size(6.dp))
                Text(if (state.isPlaying) "暂停" else "播放")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = runtime::previousChapter, enabled = state.chapterCount > 0) {
                Icon(Icons.Rounded.SkipPrevious, null)
                Text("上一章")
            }
            TextButton(onClick = runtime::nextChapter, enabled = state.chapterCount > 0) {
                Icon(Icons.Rounded.SkipNext, null)
                Text("下一章")
            }
            TextButton(onClick = runtime::nextAudioTrack, enabled = state.audioTracks.size > 1) {
                Icon(Icons.Rounded.Headphones, null)
                Text("音轨")
            }
            TextButton(onClick = runtime::nextSubtitleTrack, enabled = state.subtitleTracks.isNotEmpty()) {
                Icon(Icons.Rounded.Subtitles, null)
                Text("字幕")
            }
        }
    }
}

@Composable
internal fun IsoPlayer(
    state: MainUiState,
    item: MediaItem,
    plan: PlaybackPlan,
    container: AppContainer,
    onRetry: () -> Unit,
    onTerminalError: (Long, String) -> Unit,
    onStarted: (PlaybackPlan, Long) -> Unit,
    onStopped: (PlaybackPlan, Long) -> Unit,
    onSaveMoment: (MediaItem, Long) -> Unit,
    onSaveSegment: (MediaItem, SegmentType, Long, Long) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val session = requireNotNull(state.session)
    val runtime = remember(session, plan, state.playbackStartPositionMs) {
        MpvIsoPlaybackRuntime(
            context = context,
            session = session,
            plan = plan,
            playbackOutbox = container.playbackOutbox,
            clientIdentity = container.clientIdentity,
            startPositionMs = state.playbackStartPositionMs,
            onTerminalError = onTerminalError,
        )
    }
    val playbackState by runtime.state.collectAsStateWithLifecycle()
    val recovery by runtime.recovery.collectAsStateWithLifecycle()
    val diagnostics by runtime.diagnostics.collectAsStateWithLifecycle()
    val pendingReports by container.playbackOutbox.pendingCount.collectAsStateWithLifecycle()
    var launchError by remember(plan) { mutableStateOf<String?>(null) }
    var launchedAtPosition by remember(plan) { mutableLongStateOf(0L) }
    var showDiagnostics by remember(runtime) { mutableStateOf(false) }
    var externalPlaybackActive by remember(runtime) { mutableStateOf(false) }
    val focusRequester = remember(runtime) { FocusRequester() }
    LaunchedEffect(runtime) { runCatching { focusRequester.requestFocus() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        externalPlaybackActive = false
        val returnedPosition = result.data?.getLongExtra(VLC_RESULT_POSITION, -1L) ?: -1L
        if (returnedPosition >= 0) runtime.seekTo(returnedPosition)
        onStopped(plan, returnedPosition.takeIf { it >= 0 } ?: launchedAtPosition)
    }

    fun launchVlc() {
        val startPosition = playbackState.positionMs.coerceAtLeast(0L)
        val playbackUrl = plan.primary.url.toHttpUrl()
        val serverUrl = session.serverUrl.toHttpUrl()
        if (
            playbackUrl.scheme != serverUrl.scheme || playbackUrl.host != serverUrl.host ||
            playbackUrl.port != serverUrl.port
        ) {
            launchError = "ISO 播放地址不属于当前 Emby 服务器，已拒绝向外部播放器发送凭据"
            return
        }
        val authorizedUrl = playbackUrl.newBuilder()
            .setQueryParameter("api_key", session.accessToken)
            .build()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setPackage(VLC_PACKAGE)
            setDataAndType(Uri.parse(authorizedUrl.toString()), "video/*")
            putExtra("title", item.name)
            putExtra("from_start", startPosition == 0L)
            putExtra("position", startPosition)
        }
        val resumeNativeOnFailure = playbackState.isPlaying
        runtime.pause()
        try {
            launchedAtPosition = startPosition
            externalPlaybackActive = true
            launcher.launch(intent)
            onStarted(plan, startPosition)
            launchError = null
        } catch (_: ActivityNotFoundException) {
            externalPlaybackActive = false
            if (resumeNativeOnFailure) runtime.play()
            launchError = "没有找到 VLC for Android，请先安装后重试"
        }
    }

    DisposableEffect(runtime, lifecycleOwner) {
        var resumeAfterBackground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    runtime.setForeground(false)
                    resumeAfterBackground = !externalPlaybackActive && runtime.state.value.isPlaying
                    runtime.pause()
                }
                Lifecycle.Event.ON_START -> {
                    runtime.setForeground(true)
                    if (resumeAfterBackground) {
                    runtime.play()
                    resumeAfterBackground = false
                    }
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

    Box(
        Modifier.fillMaxSize()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        runtime.seekTo(playbackState.positionMs - TV_SEEK_STEP_MS)
                        true
                    }
                    Key.DirectionRight -> {
                        runtime.seekTo(playbackState.positionMs + TV_SEEK_STEP_MS)
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.MediaPlayPause -> {
                        if (playbackState.isPlaying) runtime.pause() else runtime.play()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        AndroidView(
            factory = { viewContext -> SurfaceView(viewContext).also(runtime::attach) },
            modifier = Modifier.fillMaxSize(),
        )

        if (playbackState.isBuffering) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("正在读取 ISO ${playbackState.bufferPercent}%", color = Color.White)
            }
        }

        FilledTonalButton(
            onClick = { showDiagnostics = !showDiagnostics },
            modifier = Modifier.statusBarsPadding().padding(top = 68.dp, start = 12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Rounded.BugReport, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.size(6.dp))
            Text(if (showDiagnostics) "收起诊断" else "ISO 诊断")
        }

        if (showDiagnostics || playbackState.error != null || launchError != null) {
            Card(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 124.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xEE111713),
                    contentColor = Color.White,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ISO 播放链路", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text("${diagnostics.engine} · ${diagnostics.nativeAbi ?: "正在加载"} · 应用内播放", color = Color.White)
                    Text(
                        "上游 ${diagnostics.source.upstreamHost ?: "等待连接"} · " +
                            "HTTP ${diagnostics.source.upstreamStatus ?: "-"} · " +
                            "Range ${if (diagnostics.source.upstreamStatus == 206) "是" else "待确认"}",
                        color = Color.White.copy(alpha = 0.74f),
                    )
                    Text(
                        "请求 ${diagnostics.source.requestCount} · 缓存 " +
                            "${diagnostics.source.cacheHits}/${diagnostics.source.cacheMisses} · " +
                            if (diagnostics.source.requestCount > 0) {
                                "最近 bytes=${diagnostics.source.lastOffset}-" +
                                    "${diagnostics.source.lastOffset + diagnostics.source.lastLength - 1} · "
                            } else {
                                "最近 Range 等待读取 · "
                            } +
                            diagnostics.source.totalBytes.takeIf { it > 0 }?.let(::formatBytes).orEmpty(),
                        color = Color.White.copy(alpha = 0.74f),
                    )
                    Text(
                        "章节 ${playbackState.chapterIndex + 1}/${playbackState.chapterCount} · " +
                            "${diagnostics.videoCodec ?: "?"}/${diagnostics.audioCodec ?: "?"} · " +
                            "${diagnostics.hardwareDecoder ?: "软件/待定"} · 同步队列 $pendingReports",
                        color = Color.White.copy(alpha = 0.74f),
                    )
                    (playbackState.error ?: launchError ?: diagnostics.lastError)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        Row {
                            OutlinedButton(onClick = onRetry) { Text("重新解析") }
                            Spacer(Modifier.size(8.dp))
                            OutlinedButton(onClick = ::launchVlc) { Text("外部 VLC 兜底") }
                        }
                    }
                    if (launchError?.contains("没有找到") == true) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VLC_DOWNLOAD_URL)))
                            },
                        ) { Text("打开 VLC 下载页") }
                    }
                }
            }
        }

        IsoPlaybackControls(
            item = item,
            runtime = runtime,
            state = playbackState,
            segments = state.mediaSegments,
            onSaveMoment = { onSaveMoment(item, it) },
            onSaveSegment = { type, startMs, endMs -> onSaveSegment(item, type, startMs, endMs) },
            onExternalFallback = ::launchVlc,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun IsoPlaybackControls(
    item: MediaItem,
    runtime: MpvIsoPlaybackRuntime,
    state: MpvIsoPlaybackState,
    segments: List<MediaSegment>,
    onSaveMoment: (Long) -> Unit,
    onSaveSegment: (SegmentType, Long, Long) -> Unit,
    onExternalFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var positionMs by remember(runtime) { mutableLongStateOf(0L) }
    var isScrubbing by remember(runtime) { mutableStateOf(false) }
    val activeSegment = segments.firstOrNull { positionMs in it.startMs until it.endMs }

    LaunchedEffect(state.positionMs, isScrubbing) {
        if (!isScrubbing) positionMs = state.positionMs
    }

    Column(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Color(0xE6111612))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text("DISC IMAGE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        Text(
            item.name,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = positionMs.coerceIn(0L, state.durationMs.coerceAtLeast(0L)).toFloat(),
            onValueChange = {
                isScrubbing = true
                positionMs = it.toLong()
            },
            onValueChangeFinished = {
                runtime.seekTo(positionMs)
                isScrubbing = false
            },
            valueRange = 0f..state.durationMs.coerceAtLeast(1L).toFloat(),
            enabled = state.isSeekable && state.durationMs > 0,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${formatDuration(positionMs)} / ${formatDuration(state.durationMs)}",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            FilledTonalButton(onClick = { if (state.isPlaying) runtime.pause() else runtime.play() }) {
                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.size(6.dp))
                Text(if (state.isPlaying) "暂停" else "播放")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = runtime::previousChapter, enabled = state.chapterCount > 0) {
                Icon(Icons.Rounded.SkipPrevious, null)
                Text("上一章")
            }
            TextButton(onClick = runtime::nextChapter, enabled = state.chapterCount > 0) {
                Icon(Icons.Rounded.SkipNext, null)
                Text("下一章")
            }
            TextButton(onClick = runtime::nextAudioTrack, enabled = state.audioTracks.size > 1) {
                Icon(Icons.Rounded.Headphones, null)
                Text("音轨")
            }
            TextButton(onClick = runtime::nextSubtitleTrack, enabled = state.subtitleTracks.isNotEmpty()) {
                Icon(Icons.Rounded.Subtitles, null)
                Text("字幕")
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { onSaveMoment(positionMs) }) {
                Icon(Icons.Rounded.BookmarkAdd, null)
                Spacer(Modifier.size(5.dp))
                Text("保存时刻")
            }
            if (activeSegment != null && state.isSeekable) {
                TextButton(onClick = { runtime.seekTo(activeSegment.endMs) }) {
                    Icon(Icons.Rounded.SkipNext, null)
                    Spacer(Modifier.size(5.dp))
                    Text(activeSegment.type.skipLabel())
                }
            } else {
                TextButton(
                    onClick = { onSaveSegment(SegmentType.INTRO, 0L, positionMs) },
                    enabled = positionMs >= 5_000,
                ) { Text("标记片头终点") }
                TextButton(
                    onClick = { onSaveSegment(SegmentType.CREDITS, positionMs, state.durationMs) },
                    enabled = state.durationMs > positionMs,
                ) { Text("标记片尾起点") }
            }
        }
        TextButton(onClick = onExternalFallback, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.size(5.dp))
            Text("外部 VLC 兜底", color = Color.White.copy(alpha = 0.7f))
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GiB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    else -> "$bytes B"
}

private const val VLC_PACKAGE = "org.videolan.vlc"
private const val VLC_RESULT_POSITION = "extra_position"
private const val VLC_DOWNLOAD_URL = "https://www.videolan.org/vlc/download-android.html"
