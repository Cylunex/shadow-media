@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun ReaderPreferencesPanel(value: ReaderSettings, update: (ReaderSettings) -> Unit, encoding: (@Composable () -> Unit)? = null, nativeText: Boolean = false) {
    var tab by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("外观", "翻页", "手势").forEachIndexed { index, label ->
            FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) })
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (tab) {
            0 -> {
                Text("纸张与背景", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderPaper.entries.forEach { paper ->
                        FilterChip(selected = value.paper == paper, onClick = { update(value.copy(paper = paper)) }, label = { Text(paper.label) },
                            leadingIcon = { Box(Modifier.size(18.dp).background(Color(paper.background), CircleShape)) })
                    }
                }
                Text("字体", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderFont.entries.forEach { font ->
                        FilterChip(selected = value.font == font, onClick = { update(value.copy(font = font)) }, label = { Text(font.label) })
                    }
                }
                Text("使用设备字体，缺少的字形由系统补全。固定版式书籍按原书排版。", style = MaterialTheme.typography.bodySmall)
                ReaderSlider("字号", "${value.fontSize.roundToInt()}%", value.fontSize, 80f..220f) { update(value.copy(fontSize = it)) }
                ReaderSlider("行距", "%.1f".format(value.lineHeight), value.lineHeight, 1.2f..2.5f) { update(value.copy(lineHeight = it)) }
                ReaderSlider("段距", "%.1f".format(value.paragraphSpacing), value.paragraphSpacing, 0f..2f) { update(value.copy(paragraphSpacing = it)) }
                ReaderSlider("字距", "%.2f".format(value.letterSpacing), value.letterSpacing, 0f..0.15f) { update(value.copy(letterSpacing = it)) }
                ReaderSlider("页边距", "%.1f".format(value.margins), value.margins, 0.4f..2f) { update(value.copy(margins = it)) }
                ReaderSwitch("加粗文字", value.bold) { update(value.copy(bold = it)) }
                ReaderSwitch("段首缩进两字", value.indent) { update(value.copy(indent = it)) }
                encoding?.invoke()
                TextButton(onClick = { update(value.copy(fontSize = 115f, lineHeight = 1.7f, paragraphSpacing = 0.6f, letterSpacing = 0f, margins = 1f, font = ReaderFont.SERIF, bold = false, indent = true)) }) { Text("恢复默认排版") }
            }
            1 -> {
                Text("翻页方式", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderPageMode.entries.forEach { mode ->
                        FilterChip(selected = value.pageMode == mode, onClick = { update(value.copy(pageMode = mode)) }, label = { Text(mode.label) })
                    }
                }
                Text(when (value.pageMode) {
                    ReaderPageMode.CURL -> "拖动纸页查看卷曲与背面，松手完成翻页；短距离拖动会回弹。"
                    ReaderPageMode.SLIDE -> "左右滑动切换整页，点击页边或使用底部按钮也可翻页。"
                    ReaderPageMode.COVER -> "当前纸页移开，露出下一页。"
                    ReaderPageMode.NONE -> "直接切换页面，适合偏好静态阅读的场景。"
                    ReaderPageMode.SCROLL -> "上下连续滚动，点击页面呼出菜单；左侧窄边仍可调亮度。"
                }, style = MaterialTheme.typography.bodyMedium)
                ReaderSwitch("全屏阅读", value.fullScreen) { update(value.copy(fullScreen = it)) }
                ReaderSwitch("阅读时保持亮屏", value.keepAwake) { update(value.copy(keepAwake = it)) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("屏幕亮度", style = MaterialTheme.typography.titleSmall)
                Text(if (value.brightness < 0) "跟随系统" else "${(value.brightness * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                ReaderSlider("阅读亮度", "", if (value.brightness < 0) 0.5f else value.brightness, 0.02f..1f) { update(value.copy(brightness = it)) }
                TextButton(onClick = { update(value.copy(brightness = -1f)) }) { Text("跟随系统亮度") }
            }
            2 -> {
                ReaderSwitch("点击左右区域翻页", value.tapToTurn) { update(value.copy(tapToTurn = it)) }
                ReaderSwitch("音量键翻页", value.volumeToTurn) { update(value.copy(volumeToTurn = it)) }
                ReaderSwitch("左侧上下滑动调亮度", value.brightnessGesture) { update(value.copy(brightnessGesture = it)) }
                ReaderSwitch("双指缩放调整字号", value.pinchToResize) { update(value.copy(pinchToResize = it)) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("操作提示", style = MaterialTheme.typography.titleSmall)
                Text((if (nativeText) "• 长按段落：复制或保存摘录\n" else "• 长按文字：选择、复制或保存摘录\n") + "• 点击中央：显示或收起菜单\n• 左右滑动：上一页 / 下一页\n• 音量上 / 下：上一页 / 下一页（开启后）\n• 返回键：先关闭目录等面板，再返回书架\n• 目录、搜索、书签与朗读均在菜单中", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun ReaderSlider(label: String, suffix: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
    var pending by remember(value) { mutableFloatStateOf(value) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(if (pending != value) "%.2f".format(pending) else suffix, style = MaterialTheme.typography.bodySmall)
    }
    Slider(value = pending, onValueChange = { pending = it }, onValueChangeFinished = { change(pending) }, valueRange = range)
}

@Composable private fun ReaderSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = change), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable internal fun ReaderProgressControl(progress: Float, enabled: Boolean, label: String, compact: Boolean = false, modifier: Modifier = Modifier, seek: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var pending by remember { mutableFloatStateOf(progress) }
    Column(modifier.fillMaxWidth().padding(horizontal = if (compact) 0.dp else 20.dp)) {
        if (!compact) Text("$label ${((if (dragging) pending else progress) * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(value = if (dragging) pending else progress, onValueChange = { dragging = true; pending = it },
            onValueChangeFinished = { seek(pending); dragging = false }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "${label}进度" })
    }
}
