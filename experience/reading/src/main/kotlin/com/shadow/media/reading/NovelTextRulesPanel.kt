@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shadow.media.library.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Composable
fun ColumnScope.NovelTextRulesPanel(repository: NovelRepository, scope: String?, sample: String = "", onSaved: () -> Unit = {}) {
    var rules by remember(scope) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var revision by remember(scope) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf(sample.take(20000)) }
    val coroutine = rememberCoroutineScope()
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        coroutine.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "替换规则操作失败，请重试" }
            finally { busy = false }
        }
    }
    fun load() = run {
        val data = repository.textRules(scope, refresh = true)
        rules = data.novelObjects("items"); revision = data.optInt("revision"); loaded = true
    }
    fun edit(index: Int, key: String, value: Any) {
        rules = rules.mapIndexed { i, rule -> if (i == index) JSONObject(rule.toString()).put(key, value) else rule }
        preview = null
    }
    LaunchedEffect(scope) { load() }
    Text("按顺序处理所有正文，替换内容留空可屏蔽广告。原文保留，停用后恢复。", style = MaterialTheme.typography.bodySmall)
    if (scope == null) Text("本机 TXT 使用本机规则；Novel 书籍使用对应连接的同步规则。", style = MaterialTheme.typography.bodySmall)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(enabled = !busy, onClick = ::load) { Text("重新加载规则") } }
    Row {
        TextButton(enabled = loaded && !busy && rules.size < 100, onClick = {
            rules = rules + JSONObject().put("id", UUID.randomUUID().toString()).put("name", "").put("pattern", "")
                .put("replacement", "").put("enabled", true).put("is_regex", false)
        }) { Text("添加规则") }
        Button(enabled = loaded && !busy, onClick = { run {
            require(rules.all { it.optString("pattern").isNotBlank() }) { "匹配内容不能为空" }
            if (scope == null) rules.filter { it.optBoolean("is_regex") }.forEach { java.util.regex.Pattern.compile(it.getString("pattern")) }
            val result = repository.saveTextRules(scope, JSONArray(rules), revision)
            revision = result.optInt("revision"); onSaved()
        } }) { Text("保存并应用") }
    }
    LazyColumn(Modifier.weight(1f).imePadding(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (rules.isEmpty() && loaded) item { Text("暂无规则。选中广告语后可从顶部「替换」添加。") }
        itemsIndexed(rules, key = { _, r -> r.getString("id") }) { index, rule ->
            OutlinedCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(rule.optString("name"), { edit(index, "name", it.take(100)) }, enabled = !busy,
                        label = { Text("规则 ${index + 1} · 名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(rule.optString("pattern"), { edit(index, "pattern", it.take(1000)) }, enabled = !busy,
                        label = { Text("匹配文字或正则") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(rule.optString("replacement"), { edit(index, "replacement", it.take(1000)) }, enabled = !busy,
                        label = { Text("替换为（留空屏蔽）") }, modifier = Modifier.fillMaxWidth())
                    FlowRow {
                        FilterChip(rule.optBoolean("enabled", true), enabled = !busy, onClick = { edit(index, "enabled", !rule.optBoolean("enabled", true)) }, label = { Text("启用") })
                        FilterChip(rule.optBoolean("is_regex"), enabled = !busy, onClick = { edit(index, "is_regex", !rule.optBoolean("is_regex")) }, label = { Text("正则 · 分组用 \$1") })
                        TextButton(enabled = !busy && index > 0, onClick = { rules = rules.toMutableList().apply { add(index - 1, removeAt(index)) } }) { Text("上移") }
                        TextButton(enabled = !busy && index < rules.lastIndex, onClick = { rules = rules.toMutableList().apply { add(index + 1, removeAt(index)) } }) { Text("下移") }
                        TextButton(enabled = !busy, onClick = { rules = rules.filterIndexed { i, _ -> i != index } }) { Text("删除") }
                    }
                }
            }
        }
        item {
            OutlinedTextField(input, { input = it.take(20000) }, enabled = !busy, label = { Text("预览原文") }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
            TextButton(enabled = loaded && !busy, onClick = { run {
                val result = withContext(Dispatchers.Default) { NovelTextRules.apply(input, JSONObject().put("items", JSONArray(rules))) }
                preview = result.text.ifEmpty { "（替换后为空）" }; error = result.warning.takeIf(String::isNotEmpty)
            } }) { Text("预览替换效果") }
            preview?.let { Text(it) }
        }
    }
}
