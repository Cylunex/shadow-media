@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package top.cylunex.shadowmedia

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState

/** Compose's ContentFrame renders the surface only; SubtitleView renders text and bitmap cues. */
@Composable
internal fun VideoSubtitles(player: Player) {
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
            update = { it.setCues(cues.cues) })
    }
}
