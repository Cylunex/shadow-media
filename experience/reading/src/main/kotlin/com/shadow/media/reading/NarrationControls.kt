package com.shadow.media.reading

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun NarrationControls(onFromHere: () -> Unit, onFollow: () -> Unit, onContinue: () -> Unit = onFromHere) {
    val state by ReaderNarrationService.state.collectAsState()
    val context = LocalContext.current
    fun command(action: String) = ReaderNarrationService.command(context, action)
    if (state.title.isNotBlank()) Text(state.title)
    Row {
        TextButton(onClick = { command(ReaderNarrationService.PREVIOUS) }, enabled = state.assetId != null) { Text("上一句") }
        TextButton(onClick = { if (state.playing) command(ReaderNarrationService.PAUSE) else onContinue() }) { Text(if (state.playing) "暂停" else "继续听") }
        TextButton(onClick = { command(ReaderNarrationService.NEXT) }, enabled = state.assetId != null) { Text("下一句") }
    }
    Row {
        TextButton(onClick = onFromHere) { Text("从这里听") }
        TextButton(onClick = onFollow, enabled = state.locator != null) { Text("回到正在听") }
    }
    Row {
        TextButton(onClick = { ReaderNarrationService.command(context, ReaderNarrationService.OPTIONS) { putExtra("speed", state.speed - .25f) } }) { Text("慢一点") }
        Text("${state.speed}×")
        TextButton(onClick = { ReaderNarrationService.command(context, ReaderNarrationService.OPTIONS) { putExtra("speed", state.speed + .25f) } }) { Text("快一点") }
    }
    Row {
        Checkbox(state.stopAtChapter, onCheckedChange = { checked ->
            ReaderNarrationService.command(context, ReaderNarrationService.OPTIONS) { putExtra("chapter", checked) }
        })
        Text("本章结束停止")
    }
    Row {
        listOf(15, 30, 60, 0).forEach { minutes ->
            TextButton(onClick = { ReaderNarrationService.command(context, ReaderNarrationService.OPTIONS) { putExtra("minutes", minutes) } }) {
                Text(if (minutes == 0) "取消定时" else "$minutes 分钟")
            }
        }
    }
    if (state.deadline != null) Text("定时停止已设置")
    if (state.voices.isNotEmpty()) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { expanded = true }) { Text("音色 · ${state.voices.firstOrNull { it.first == state.voice }?.second ?: "系统默认"}") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                state.voices.forEach { (name, language) ->
                    DropdownMenuItem(text = { Text("$language · $name") }, onClick = {
                        expanded = false
                        ReaderNarrationService.command(context, ReaderNarrationService.OPTIONS) { putExtra("voice", name) }
                    })
                }
            }
        }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    TextButton(onClick = {
        runCatching { context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS")) }
    }) { Text("系统语音设置") }
    TextButton(onClick = { command(ReaderNarrationService.STOP); onFollow() }, enabled = state.assetId != null) { Text("结束听读，回正文") }
}
