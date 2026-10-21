package com.shadow.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.shadow.media.model.*

@Composable internal fun VideoQueueControls(container: AppContainer, viewModel: MainViewModel, entry: ExternalMediaEntry, player: Player, modifier: Modifier) {
    val store = container.videoQueue
    val queue by store.queue.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    fun open(value: VideoQueue, playing: Boolean = player.playWhenReady) { value.current?.let { viewModel.playUnifiedItem(UnifiedMediaItem(it.key, it.title, "Video", progressMs = value.positionMs), it.entryId, playing) } }
    val current = entry.queueEntryId ?: return
    if (!store.active || queue.currentEntryId != current) return
    DisposableEffect(player, current) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && store.queue.value.currentEntryId == current) store.advance()?.let { open(it) }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); store.position(current, container.castPlayback.returnedPosition(entry.sourceId, entry.id) ?: player.currentPosition) }
    }
    if (LocalVideoControlState.current.locked) return
    if ((androidx.compose.ui.platform.LocalContext.current.findActivity() as? MainActivity)?.inPictureInPicture == true) return
    Row(modifier) {
        TextButton(enabled = queue.currentIndex > 0, onClick = { open(store.select(queue.entries[queue.currentIndex - 1].entryId)) }) { Text("上一条") }
        TextButton(onClick = { expanded = true }) { Text("队列 ${queue.currentIndex + 1}/${queue.entries.size}") }
        TextButton(enabled = queue.currentIndex < queue.entries.lastIndex || queue.repeat, onClick = { store.advance()?.let { open(it) } }) { Text("下一条") }
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("视频队列") }, text = {
        Column {
            Row { Text("循环队列", Modifier.weight(1f)); Switch(queue.repeat, { store.repeat(it) }) }
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(queue.entries, key = { it.entryId }) { item ->
                    Column {
                        TextButton(onClick = { open(store.select(item.entryId)); expanded = false }) { Text((if (item.entryId == current) "正在播放 · " else "") + item.title) }
                        Row {
                            TextButton(onClick = { store.move(item.entryId, -1) }) { Text("上移") }
                            TextButton(onClick = { store.move(item.entryId, 1) }) { Text("下移") }
                            TextButton(onClick = {
                                val removingCurrent = item.entryId == current
                                val updated = store.remove(item.entryId)
                                if (removingCurrent) { val playing = player.playWhenReady; player.pause(); if (updated.current != null) open(updated, playing) else { store.deactivate(); viewModel.back() } }
                            }) { Text("移除") }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("完成") } })
}
