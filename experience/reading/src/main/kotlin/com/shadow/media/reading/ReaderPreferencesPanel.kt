@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun ColumnScope.ReaderPreferencesPanel(value: ReaderSettings, update: (ReaderSettings) -> Unit,
    encoding: (@Composable () -> Unit)? = null, nativeText: Boolean = false, importFont: (() -> Unit)? = null) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val scrolls = listOf(rememberScrollState(), rememberScrollState(), rememberScrollState(), rememberScrollState())
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        listOf("文字", "页面", "翻页", "手势").forEachIndexed { index, label ->
            SegmentedButton(selected = tab == index, onClick = { tab = index }, shape = SegmentedButtonDefaults.itemShape(index, 4)) { Text(label) }
        }
    }
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scrolls[tab]), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (tab) {
            0 -> {
                ReaderTypographyPreview(value)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderTypographyPreset.entries.forEach { preset ->
                        FilterChip(selected = preset.matches(value), onClick = { update(preset.apply(value)) }, label = { Text(preset.label) })
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("字号", Modifier.weight(1f))
                    FilledTonalIconButton(enabled = value.fontSize > 80f, onClick = { update(value.copy(fontSize = (value.fontSize - 5f).coerceAtLeast(80f))) }) { Icon(Icons.Rounded.Remove, "减小字号") }
                    Text("${(value.fontSize / 5).roundToInt()} sp", style = MaterialTheme.typography.labelLarge)
                    FilledTonalIconButton(enabled = value.fontSize < 220f, onClick = { update(value.copy(fontSize = (value.fontSize + 5f).coerceAtMost(220f))) }) { Icon(Icons.Rounded.Add, "增大字号") }
                }
                Text("字体", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderFont.entries.forEach { font -> FilterChip(selected = value.font == font && value.customFontId.isBlank(),
                        onClick = { update(value.copy(font = font, customFontId = "", customFontName = "")) }, label = { Text(font.label) }) }
                    if (nativeText) importFont?.let { onImport -> FilterChip(selected = value.customFontId.isNotBlank(), onClick = onImport,
                        label = { Text(if (value.customFontId.isBlank()) "导入字体" else value.customFontName.ifBlank { "自选字体" }, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                }
                ReaderSlider("行距", "%.1f".format(value.lineHeight), value.lineHeight, 1.2f..2.5f) { update(value.copy(lineHeight = it)) }
                ReaderSlider("段距", "%.1f".format(value.paragraphSpacing), value.paragraphSpacing, 0f..2f) { update(value.copy(paragraphSpacing = it)) }
                ReaderSlider("字距", "%.2f".format(value.letterSpacing), value.letterSpacing, 0f..0.15f) { update(value.copy(letterSpacing = it)) }
                ReaderSwitch("加粗文字", value.bold) { update(value.copy(bold = it)) }
                ReaderSwitch("段首缩进", value.indent) { update(value.copy(indent = it)) }
                if (value.indent) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..4).forEach { count -> FilterChip(selected = value.indentSize == count, onClick = { update(value.copy(indentSize = count)) }, label = { Text("$count 字") }) }
                }
                ReaderSwitch("正文两端对齐", value.justify) { update(value.copy(justify = it)) }
                encoding?.invoke()
            }
            1 -> {
                Text("纸张与背景", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderPaper.entries.forEach { paper -> FilterChip(selected = value.paper == paper, onClick = { update(value.selectPaper(paper)) }, label = { Text(paper.label) },
                        leadingIcon = { ReaderPaperBackground(value.copy(paper = paper), Modifier.size(18.dp).clip(CircleShape)) }) }
                }
                if (value.paper == ReaderPaper.PARCHMENT) ReaderSlider("纸张纹理", "${(value.paperTexture * 100).roundToInt()}%", value.paperTexture, 0f..1f) { update(value.copy(paperTexture = it)) }
                ReaderSlider("左右留白", "%.1f".format(value.margins), value.margins, .4f..2f) { update(value.copy(margins = it)) }
                if (nativeText) {
                    ReaderSlider("上下留白", "%.1f".format(value.verticalMargins), value.verticalMargins, .5f..2.5f) { update(value.copy(verticalMargins = it)) }
                    ReaderSwitch("宽屏限制行长", value.comfortableWidth) { update(value.copy(comfortableWidth = it)) }
                    ReaderSwitch("显示页眉与本章进度", value.showProgress) { update(value.copy(showProgress = it)) }
                    HorizontalDivider()
                    ReaderSwitch("显示章节标题", value.showChapterTitle) { update(value.copy(showChapterTitle = it)) }
                    if (value.showChapterTitle) {
                        Text("标题位置", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ReaderTitleAlignment.entries.forEach { alignment ->
                            FilterChip(selected = value.titleAlignment == alignment, onClick = { update(value.copy(titleAlignment = alignment)) }, label = { Text(alignment.label) })
                        } }
                        ReaderSlider("标题大小", "%.1f 倍".format(value.titleScale), value.titleScale, 1f..1.8f) { update(value.copy(titleScale = it)) }
                        ReaderSlider("标题与正文间距", "%.1f".format(value.titleSpacing), value.titleSpacing, .25f..2f) { update(value.copy(titleSpacing = it)) }
                    }
                }
            }
            2 -> {
                Text("翻页方式", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ReaderPageMode.entries.forEach { mode ->
                    FilterChip(selected = value.pageMode == mode, onClick = { update(value.copy(pageMode = mode)) }, label = { Text(mode.label) })
                } }
                Text(when (value.pageMode) {
                    ReaderPageMode.CURL -> "拖动纸页翻页，短距离拖动会回弹。"
                    ReaderPageMode.SLIDE -> "纸页跟随手指左右移动，松手完成或回弹。"
                    ReaderPageMode.COVER -> "当前纸页移开，露出下一页。"
                    ReaderPageMode.NONE -> "直接切换页面。"
                    ReaderPageMode.SCROLL -> "上下滚动；章末继续上拉并松手进入下一章，惯性滚动停在章末。"
                }, style = MaterialTheme.typography.bodySmall)
                ReaderSwitch("全屏阅读", value.fullScreen) { update(value.copy(fullScreen = it)) }
                ReaderSwitch("阅读时保持亮屏", value.keepAwake) { update(value.copy(keepAwake = it)) }
                HorizontalDivider()
                ReaderSlider("阅读亮度", if (value.brightness < 0) "跟随系统" else "${(value.brightness * 100).roundToInt()}%",
                    if (value.brightness < 0) .5f else value.brightness, .02f..1f) { update(value.copy(brightness = it)) }
                TextButton(onClick = { update(value.copy(brightness = -1f)) }) { Text("跟随系统亮度") }
            }
            3 -> {
                ReaderSwitch("点击左右区域翻页", value.tapToTurn) { update(value.copy(tapToTurn = it)) }
                ReaderSwitch("音量键翻页", value.volumeToTurn) { update(value.copy(volumeToTurn = it)) }
                ReaderSwitch("左侧上下滑动调亮度", value.brightnessGesture) { update(value.copy(brightnessGesture = it)) }
                ReaderSwitch("双指缩放调整字号", value.pinchToResize) { update(value.copy(pinchToResize = it)) }
                if (nativeText) ReaderSwitch("边界与长按触感反馈", value.haptics) { update(value.copy(haptics = it)) }
                Text("点击中央呼出菜单；长按正文复制或摘录。跨章在松手后执行，拖动取消不会跳章。", style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = { update(ReaderTypographyPreset.CLASSIC.apply(value).copy(comfortableWidth = true, showProgress = true)) }) { Text("恢复默认排版") }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable internal fun ReaderTypographyPreview(value: ReaderSettings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val custom = remember(value.customFontId, value.bold) { ReaderFonts(context).typeface(value) }
    val font = custom?.let { FontFamily(it) } ?: when (value.font) { ReaderFont.SANS -> FontFamily.SansSerif; ReaderFont.SERIF -> FontFamily.Serif; ReaderFont.MONO -> FontFamily.Monospace }
    val size = 20f * value.fontSize / 100f
    Box(Modifier.fillMaxWidth().height(156.dp).clip(RoundedCornerShape(16.dp)).semantics { contentDescription = "阅读排版预览" }) {
        ReaderPaperBackground(value, Modifier.matchParentSize())
        Column(Modifier.padding(horizontal = (18f * value.margins).dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("山海之间", color = Color(value.paper.foreground), fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text((if (value.indent) "　".repeat(value.indentSize) else "") + "山间的雾气渐渐散去，石阶旁的竹叶还挂着露珠。他收起书卷，望向远处的灯火。",
                color = Color(value.paper.foreground), fontFamily = font, fontSize = size.sp, lineHeight = (size * value.lineHeight).sp,
                letterSpacing = value.letterSpacing.em, fontWeight = if (value.bold) FontWeight.Bold else FontWeight.Normal,
                textAlign = if (value.justify) TextAlign.Justify else TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun ReaderSlider(label: String, suffix: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
    var pending by remember(value) { mutableFloatStateOf(value) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(if (pending != value) "%.2f".format(pending) else suffix, style = MaterialTheme.typography.bodySmall)
    }
    Slider(modifier = Modifier.semantics { contentDescription = label }, value = pending, onValueChange = { pending = it }, onValueChangeFinished = { change(pending) }, valueRange = range)
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
