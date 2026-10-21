package com.shadow.media

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.shadow.media.model.ExternalMediaEntry

@Composable internal fun LiveLinePicker(state: MainUiState, viewModel: MainViewModel, entry: ExternalMediaEntry, modifier: Modifier) {
    val channel = state.liveChannels.firstOrNull { it.streams.any { line -> line.id == entry.id && line.sourceId == entry.sourceId } } ?: return
    if (channel.streams.size <= 1) return
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }, modifier = modifier) { Text("切换线路") }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(channel.title) }, text = {
        Column { channel.streams.forEachIndexed { index, line ->
            TextButton(onClick = { expanded = false; viewModel.playExternalEntry(line) }) { Text((if (line.id == entry.id) "✓ " else "") + "线路 ${index + 1} · ${viewModel.liveLineDescription(line)}") }
        } }
    }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("关闭") } })
}
