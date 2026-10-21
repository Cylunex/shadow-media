@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.shadow.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.shadow.media.library.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable internal fun NovelSourceSearchSheet(
    repository: NovelRepository, connection: CatalogConnection, work: JSONObject,
    onDismiss: () -> Unit, onBound: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf(work.optString("title").trim().take(200)) }
    var submitted by remember { mutableStateOf(keyword) }
    var attempt by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<JSONObject?>(null) }
    var searching by remember { mutableStateOf(false) }
    var binding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var bindingNotice by remember { mutableStateOf<String?>(null) }
    fun search() {
        if (binding || keyword.isBlank()) return
        submitted = keyword.trim(); attempt++
    }
    LaunchedEffect(connection.id, submitted, attempt) {
        progress = null; error = null
        if (submitted.isBlank()) return@LaunchedEffect
        searching = true
        try { repository.sourceSearch.search(connection, submitted) { progress = it } }
        catch (e: TimeoutCancellationException) { error = "搜索耗时较长，已保留返回的结果，可重试" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "书源搜索失败，请重试" }
        finally { searching = false }
    }
    val result = progress?.optJSONObject("result")
    val rows = result?.novelObjects("items").orEmpty()
    val sources = result?.novelObjects("sources").orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("搜索在线书源", style = MaterialTheme.typography.titleLarge)
                Text("为《${work.optString("title")}》查找可读版本", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(keyword, { keyword = it.take(200) }, Modifier.weight(1f), enabled = !binding,
                        label = { Text("书名或作者") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
                    TextButton(enabled = keyword.isNotBlank() && !binding, onClick = ::search) { Text("搜索") }
                }
                if (searching || binding) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (searching) Text("正在查询已启用的书源…", style = MaterialTheme.typography.bodySmall)
                bindingNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (error != null || (!searching && sources.any { !it.optBoolean("ok") })) {
                    TextButton(enabled = !binding, onClick = ::search) { Text("重试搜索") }
                }
            }
            items(sources) { source ->
                Text("${source.optString("name")} · ${if (source.optBoolean("ok")) "${source.optInt("count")} 个结果" else source.optString("message").ifBlank { "暂不可用" }}",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (!searching && error == null && rows.isEmpty()) item {
                Text(if (sources.isEmpty()) "暂无可搜索的书源，请先在 Novel 中启用支持搜索的书源。" else "未找到匹配结果，可修改书名或作者后重试。")
            }
            items(rows) { row ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.optString("name"), style = MaterialTheme.typography.titleMedium)
                        Text("${row.optString("author").ifBlank { "作者待补全" }} · ${row.optString("provider_name")}", style = MaterialTheme.typography.bodySmall)
                        if (row.optString("intro").isNotBlank()) Text(row.optString("intro"), maxLines = 3, style = MaterialTheme.typography.bodySmall)
                        if (readableNovelSource(row)) {
                            FilledTonalButton(enabled = !binding, onClick = {
                                scope.launch {
                                    binding = true; error = null; bindingNotice = "正在关联当前作品并获取目录…"
                                    try { repository.sourceSearch.bind(connection, work.getString("id"), row); onBound() }
                                    catch (e: TimeoutCancellationException) { error = "获取目录耗时较长，可关闭搜索并刷新作品详情，稍后查看版本" }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: NovelApiException) {
                                        error = if (e.status == 409 && e.code == "already_linked") "此来源已关联其他作品，请选择其他结果" else e.message
                                    }
                                    catch (e: Exception) { error = e.message ?: "关联失败，请重试" }
                                    finally { binding = false; bindingNotice = null }
                                }
                            }) { Text("关联当前作品并获取目录") }
                        } else Text("此来源仅提供资料，暂无可读正文", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
