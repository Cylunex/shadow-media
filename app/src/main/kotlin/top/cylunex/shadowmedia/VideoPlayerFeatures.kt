@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package top.cylunex.shadowmedia

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import kotlinx.coroutines.delay
import top.cylunex.shadowmedia.model.ExternalSubtitle
import top.cylunex.shadowmedia.model.gestureSeekPosition
import top.cylunex.shadowmedia.model.subtitleMimeType
import kotlin.math.abs
import kotlin.math.roundToInt

internal class VideoControlState {
    var locked by mutableStateOf(false)
    var sleepExpired = false
}
internal val LocalVideoControlState = compositionLocalOf { VideoControlState() }
private fun Context.videoActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.videoActivity(); else -> null }

/** The middle of the frame owns gestures, leaving native controls and top navigation accessible. */
@Composable
internal fun VideoPlayerFeatures(
    player: Player,
    position: () -> Long = { player.currentPosition },
    duration: () -> Long = { player.duration },
    canSeek: () -> Boolean = { player.isCurrentMediaItemSeekable },
    seek: (Long) -> Unit = player::seekTo,
    onSubtitle: ((ExternalSubtitle) -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = context.videoActivity()
    val state = LocalVideoControlState.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var hint by remember(player) { mutableStateOf<String?>(null) }
    var panel by remember(player) { mutableStateOf<String?>(null) }
    var deadline by remember(player) { mutableLongStateOf(0L) }
    var remaining by remember(player) { mutableLongStateOf(0L) }
    var trackRevision by remember(player) { mutableIntStateOf(0) }
    var speeding by remember(player) { mutableStateOf(false) }
    val latestPosition by rememberUpdatedState(position)
    val latestDuration by rememberUpdatedState(duration)
    val latestSeek by rememberUpdatedState(seek)
    val latestCanSeek by rememberUpdatedState(canSeek)
    BackHandler(enabled = state.locked) { state.locked = false }
    DisposableEffect(player) {
        val window = activity?.window
        val brightness = window?.attributes?.screenBrightness
        val hadKeepAwake = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val listener = object : Player.Listener { override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { trackRevision++ } }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            state.locked = false
            state.sleepExpired = false
            if (!hadKeepAwake) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (brightness != null) window.attributes = window.attributes.apply { screenBrightness = brightness }
        }
    }
    LaunchedEffect(deadline, player) {
        if (deadline == 0L) { remaining = 0; return@LaunchedEffect }
        while (true) {
            remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (remaining == 0L) { player.pause(); state.sleepExpired = true; deadline = 0; hint = "睡眠定时已暂停播放"; break }
            delay(minOf(remaining, 1000))
        }
    }
    LaunchedEffect(hint, speeding) { if (!speeding && hint != null) { delay(1500); hint = null } }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }.orEmpty()
                val mime = subtitleMimeType(name)
                if (mime == null) hint = "请选择 SRT / ASS / VTT 字幕" else onSubtitle?.invoke(ExternalSubtitle(uri.toString(), mime, name))
            } catch (_: Exception) { hint = "无法读取字幕" }
        }
    }
    if ((activity as? MainActivity)?.inPictureInPicture == true) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val height = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val detector = remember(player, width, height) {
            object {
                var startPosition = 0L
                var target = 0L
                var startBrightness = 0.5f
                var startVolume = 0
                var axis = 0
                var oldSpeed = 1f
                var wasLongPress = false
                val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean {
                        axis = 0; wasLongPress = false; startPosition = latestPosition(); target = startPosition
                        startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        startBrightness = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 }
                            ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                        return true
                    }
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        if (!state.locked && !wasLongPress) { if (player.playWhenReady) player.pause() else player.play() }
                        return true
                    }
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (!state.locked && latestCanSeek()) {
                            val delta = if (e.x < width / 2) -10_000L else 10_000L
                            latestSeek((latestPosition() + delta).coerceIn(0, latestDuration().coerceAtLeast(0)))
                            hint = if (delta < 0) "快退 10 秒" else "快进 10 秒"
                        }
                        return true
                    }
                    override fun onLongPress(e: MotionEvent) {
                        if (!state.locked) { oldSpeed = player.playbackParameters.speed; player.setPlaybackSpeed(2f); speeding = true; wasLongPress = true; hint = "2 倍速" }
                    }
                    override fun onScroll(first: MotionEvent?, current: MotionEvent, dx: Float, dy: Float): Boolean {
                        if (state.locked || speeding || first == null) return true
                        val x = current.x - first.x
                        val y = first.y - current.y
                        if (axis == 0) axis = if (abs(x) > abs(y)) 1 else 2
                        if (axis == 1) {
                            if (latestCanSeek()) { target = gestureSeekPosition(startPosition, latestDuration(), x / width); hint = "跳转至 ${target / 60_000}:${(target / 1000 % 60).toString().padStart(2, '0')}" }
                            else hint = "直播流不可拖动"
                        } else if (first.x < width / 2) {
                            val value = (startBrightness + y / height).coerceIn(0.01f, 1f)
                            activity?.window?.let { it.attributes = it.attributes.apply { screenBrightness = value } }
                            hint = "亮度 ${(value * 100).roundToInt()}%"
                        } else {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val value = (startVolume + y / height * max).roundToInt().coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                            hint = "音量 ${value * 100 / max.coerceAtLeast(1)}%"
                        }
                        return true
                    }
                })
                fun touch(event: MotionEvent): Boolean {
                    if (!state.locked || event.actionMasked == MotionEvent.ACTION_CANCEL) gestures.onTouchEvent(event)
                    if (event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                        if (speeding) { player.setPlaybackSpeed(oldSpeed); speeding = false; hint = null }
                        if (event.actionMasked == MotionEvent.ACTION_UP && axis == 1 && latestCanSeek() && !state.locked) latestSeek(target)
                        axis = 0
                    }
                    return true
                }
            }
        }
        DisposableEffect(detector) {
            onDispose {
                val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                detector.touch(cancel)
                cancel.recycle()
            }
        }
        // Lock consumes the whole frame. Otherwise reserve space for the existing controls.
        Box(Modifier.fillMaxSize().then(if (state.locked) Modifier else Modifier.padding(top = minOf(maxHeight * .15f, 120.dp), bottom = minOf(maxHeight * .25f, 240.dp)))
            .pointerInteropFilter { detector.touch(it) })
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).background(Color.Black.copy(alpha = .6f))) {
            TextButton(onClick = { state.locked = !state.locked }) { Text(if (state.locked) "解锁" else "锁定", color = Color.White) }
            if (!state.locked) {
                TextButton(onClick = { panel = "设置" }) { Text(if (remaining > 0) "${(remaining + 59_999) / 60_000} 分钟" else "播放设置", color = Color.White) }
                TextButton(onClick = {
                    if (activity?.packageManager?.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) == true) {
                        activity.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build())
                    }
                }) { Text("画中画", color = Color.White) }
            }
        }
        hint?.let { Text(it, Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .7f)).padding(16.dp), color = Color.White) }
    }
    if (panel != null) AlertDialog(onDismissRequest = { panel = null }, title = { Text("播放设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("睡眠定时")
            Row { listOf(0, 15, 30, 60).forEach { minutes -> TextButton(onClick = { state.sleepExpired = false; deadline = if (minutes == 0) 0 else SystemClock.elapsedRealtime() + minutes * 60_000L; panel = null }) { Text(if (minutes == 0) "关闭" else "$minutes 分") } } }
            Text("播放速度")
            Row { listOf(.5f, 1f, 1.5f, 2f).forEach { speed -> TextButton(onClick = { player.setPlaybackSpeed(speed) }) { Text("${speed}x") } } }
            // Reading revision keeps the dialog current when the manifest adds tracks asynchronously.
            val tracks = remember(trackRevision, player) { player.currentTracks.groups }
            for ((type, title) in listOf(C.TRACK_TYPE_AUDIO to "音轨", C.TRACK_TYPE_TEXT to "字幕")) {
                Text(title)
                if (type == C.TRACK_TYPE_TEXT) TextButton(onClick = {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, true).build()
                }) { Text("关闭字幕") }
                tracks.filter { it.type == type }.forEach { group ->
                    for (index in 0 until group.length) if (group.isTrackSupported(index)) {
                        val format = group.getTrackFormat(index)
                        TextButton(onClick = {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, false)
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
                            panel = null
                        }) { Text((if (group.isTrackSelected(index)) "✓ " else "") + (format.label ?: format.language ?: "$title ${index + 1}")) }
                    }
                }
            }
            if (onSubtitle != null) TextButton(onClick = { subtitlePicker.launch(arrayOf("*/*")); panel = null }) { Text("选择外挂字幕") }
            Text("左右滑调进度，左侧上下滑调亮度，右侧调音量；双击两侧快退/快进，长按 2 倍速。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { panel = null }) { Text("完成") } })
}
