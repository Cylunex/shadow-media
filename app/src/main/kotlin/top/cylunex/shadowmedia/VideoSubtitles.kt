@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package top.cylunex.shadowmedia

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import top.cylunex.shadowmedia.playback.VideoPreferenceStore
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState

/** Compose's ContentFrame renders the surface only; SubtitleView renders text and bitmap cues. */
@Composable
internal fun VideoSubtitles(player: Player) {
    val settings by VideoPreferenceStore.get(LocalContext.current).settings.collectAsStateWithLifecycle()
    var cues by remember(player) { mutableStateOf(player.currentCues) }
    val presentation = rememberPresentationState(player)
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onCues(cueGroup: CueGroup) { cues = cueGroup } }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { context -> SubtitleView(context).apply { setUserDefaultStyle(); setUserDefaultTextSize() } },
            modifier = Modifier.fillMaxSize().resizeWithContentScale(ContentScale.Fit, presentation.videoSizeDp),
            update = {
                it.setCues(cues.cues)
                it.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * settings.subtitleScale)
                it.setBottomPaddingFraction(settings.subtitleBottomPadding)
                it.setApplyEmbeddedStyles(!settings.subtitleBackground)
                it.setStyle(androidx.media3.ui.CaptionStyleCompat(android.graphics.Color.WHITE,
                    if (settings.subtitleBackground) 0xB3000000.toInt() else android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    android.graphics.Color.BLACK, null))
            })
    }
}
