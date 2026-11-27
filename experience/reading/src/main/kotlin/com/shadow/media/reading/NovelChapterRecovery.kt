package com.shadow.media.reading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shadow.media.library.NovelApiException
import com.shadow.media.library.novelId
import com.shadow.media.library.novelObjects
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal class NovelChapterMissing(val chapter: NativeChapterRef, cause: NovelApiException) : Exception(cause)

internal fun NovelApiException.isMissingNovelChapter(): Boolean =
    code == "source_chapter_missing" ||
        (status == 400 && code == "invalid_input" &&
            detail.optString("message") in setOf("来源返回 HTTP 404", "来源返回 HTTP 410"))

@Composable
internal fun NovelChapterRecoveryDialog(
    chapter: NativeChapterRef,
    currentVersion: String,
    load: suspend () -> JSONObject,
    onDismiss: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenChapter: (versionId: String, chapterId: String) -> Unit,
) {
    var items by remember(chapter.id) { mutableStateOf<List<JSONObject>?>(null) }
    var failed by remember(chapter.id) { mutableStateOf(false) }
    var retry by remember(chapter.id) { mutableIntStateOf(0) }
    LaunchedEffect(chapter.id, retry) {
        failed = false
        try {
            items = load().novelObjects("items").filter {
                val version = it.novelId("version_id")
                version != null && version != currentVersion && it.novelId("chapter_id") != null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("当前书源的章节已失效") },
        text = {
            Column(
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(chapter.title)
                Text("当前阅读位置已保留。")
                val alternatives = items
                when {
                    failed -> {
                        Text("暂时无法查找匹配章节。")
                        TextButton(onClick = { retry++ }) { Text("重新查找") }
                    }
                    alternatives == null -> {
                        Text("正在从已关联书源查找同一章…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    alternatives.isEmpty() -> Text("已关联书源中没有可确认的对应章节。")
                    else -> {
                        Text("选择一个来源继续读这一章")
                        alternatives.forEach { item ->
                            OutlinedButton(
                                onClick = { onOpenChapter(item.getString("version_id"), item.getString("chapter_id")) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column {
                                    Text(item.optString("label").ifBlank { "其他来源" })
                                    Text(item.optString("title").ifBlank { chapter.title })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onOpenSources) { Text("打开换源") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("留在当前章") } },
    )
}
