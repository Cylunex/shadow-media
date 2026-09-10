package top.cylunex.shadowmedia

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.cylunex.shadowmedia.model.parseLyrics

@Composable internal fun AudioLyricsPanel(lyrics: String, position: Long, seek: (Long) -> Unit) {
    val lines = remember(lyrics) { parseLyrics(lyrics) }
    val active = lines.indexOfLast { it.timeMs?.let { time -> time <= position } == true }
    val scroll = rememberLazyListState()
    LaunchedEffect(active) { if (active >= 0 && !scroll.isScrollInProgress) scroll.animateScrollToItem((active - 2).coerceAtLeast(0)) }
    if (lines.isEmpty()) Text("暂无歌词", Modifier.padding(24.dp))
    LazyColumn(Modifier.heightIn(max = 480.dp), state = scroll) { itemsIndexed(lines) { index, line ->
        TextButton(onClick = { line.timeMs?.let(seek) }, enabled = line.timeMs != null, modifier = Modifier.fillMaxWidth()) {
            Text(line.text, color = if (index == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    } }
}
