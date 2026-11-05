@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shadow.media.playback.VideoPreferenceStore

internal enum class ModuleSettingAction(val label: String, val description: String) {
    SERVERS("影视服务账号", "管理 Emby 连接"),
    MDC("Shadow MDC", "使用统一账号浏览片库"),
    STORAGE("网络媒体库", "OpenList、WebDAV 与 SMB"),
    SUBSCRIPTIONS("订阅与来源", "导入、更新和管理影视或直播源"),
    LOCAL_VIDEO("本机视频", "手机视频、文件夹与链接"),
    NOVEL_ACCOUNT("小说服务与统一账号", "连接、登录或退出小说服务"),
    SOURCES("来源连接", "管理本模块的内容服务"),
    LOCAL_LIBRARY("本机书库", "管理本模块的本机内容"),
    IMPORT("导入文件", "通过系统文件选择器添加内容"),
    COMIC_FOLDER("导入漫画目录", "将图片文件夹加入漫画书架"),
    COMMUTE("通勤听", "从已下载的音频中安排收听"),
    HISTORY("继续记录", "查看本模块保存的继续位置"),
    OFFLINE("下载与存储", "管理离线任务和网络下载偏好"),
    ADVANCED("功能与诊断", "功能开关、许可与问题排查"),
    SHARED("通用设置与全部连接", "应用主题和跨模块服务连接"),
}

internal fun moduleSettingsActions(module: MediaModule): List<ModuleSettingAction> = buildList {
    addAll(when (module) {
        MediaModule.VIDEO -> listOf(ModuleSettingAction.SERVERS, ModuleSettingAction.MDC, ModuleSettingAction.STORAGE,
            ModuleSettingAction.SUBSCRIPTIONS, ModuleSettingAction.LOCAL_VIDEO)
        MediaModule.NOVEL -> listOf(ModuleSettingAction.NOVEL_ACCOUNT, ModuleSettingAction.LOCAL_LIBRARY, ModuleSettingAction.IMPORT)
        MediaModule.MUSIC -> listOf(ModuleSettingAction.SOURCES, ModuleSettingAction.IMPORT)
        MediaModule.COMIC -> listOf(ModuleSettingAction.SOURCES, ModuleSettingAction.LOCAL_LIBRARY, ModuleSettingAction.COMIC_FOLDER)
        MediaModule.AUDIOBOOK -> listOf(ModuleSettingAction.SOURCES, ModuleSettingAction.IMPORT, ModuleSettingAction.COMMUTE)
        MediaModule.LIVE -> listOf(ModuleSettingAction.SUBSCRIPTIONS)
    })
    if (module != MediaModule.LIVE) addAll(listOf(ModuleSettingAction.HISTORY, ModuleSettingAction.OFFLINE))
    if (module in setOf(MediaModule.VIDEO, MediaModule.LIVE)) add(ModuleSettingAction.ADVANCED)
    add(ModuleSettingAction.SHARED)
}

@Composable internal fun ModuleSettingsScreen(
    module: MediaModule, showContinue: Boolean, onShowContinue: (Boolean) -> Unit,
    onAction: (ModuleSettingAction) -> Unit,
) {
    val context = LocalContext.current
    val videoPreferences = remember(context) { VideoPreferenceStore.get(context) }
    val video by videoPreferences.settings.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag("settings-${module.name}"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (module != MediaModule.LIVE) item {
            ListItem(headlineContent = { Text("首页显示继续记录") },
                supportingContent = { Text("只显示${module.title}内容，不混入其他模块") },
                trailingContent = { Switch(showContinue, onShowContinue, Modifier.testTag("show-module-continue")) })
        }
        if (module == MediaModule.LIVE) item {
            ListItem(headlineContent = { Text("自动切换可用线路") },
                supportingContent = { Text("当前直播线路失效时尝试其他线路") },
                trailingContent = { Switch(video.autoSwitchLines, { value -> videoPreferences.update { it.copy(autoSwitchLines = value) } }) })
        }
        if (module in setOf(MediaModule.VIDEO, MediaModule.LIVE)) item {
            Text("播放失败重试次数", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..5).forEach { count -> FilterChip(video.retries == count,
                    { videoPreferences.update { it.copy(retries = count) } }, { Text("$count 次") }) }
            }
        }
        moduleSettingsActions(module).forEach { action -> item(key = action.name) {
            Card(onClick = { onAction(action) }, modifier = Modifier.fillMaxWidth().testTag("setting-${action.name}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(action.label, style = MaterialTheme.typography.titleMedium)
                    Text(action.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        } }
    }
}
