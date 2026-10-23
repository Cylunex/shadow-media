package com.shadow.media

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shadow.media.library.*
import com.shadow.media.reading.ReaderActivity
import com.shadow.media.ui.theme.ShadowMediaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit destination choice for a granted document; external intents never upload automatically. */
class NovelFileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        @Suppress("DEPRECATION")
        val uri = if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else intent.data
        setContent { ShadowMediaTheme { Surface(Modifier.fillMaxSize()) {
            val task = rememberNovelTask()
            val container = (application as ShadowMediaApplication).container
            var name by remember { mutableStateOf("") }
            var connections by remember { mutableStateOf<List<CatalogConnection>>(emptyList()) }
            var valid by remember { mutableStateOf(false) }
            LaunchedEffect(uri) { task.run {
                require(uri?.scheme == "content") { "请分享系统文件提供器中的 TXT 或 EPUB 文件" }
                name = withContext(Dispatchers.IO) { novelDocumentName(this@NovelFileActivity, uri!!) }
                require(name.substringAfterLast('.').lowercase() in setOf("txt", "epub")) { "此入口支持 TXT 或 EPUB" }
                connections = container.novel.store.load(); valid = true
            } }
            LazyColumn(Modifier.fillMaxSize().systemBarsPadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Text("打开阅读文件", style = MaterialTheme.typography.headlineSmall); Text(name); NovelTaskStatus(task)
                    Button(enabled = valid && !task.busy, onClick = { task.run {
                        val asset = container.library.importDocument(requireNotNull(uri))
                        startActivity(Intent(this@NovelFileActivity, ReaderActivity::class.java).putExtra("assetId", asset.id)); finish()
                    } }) { Text("导入本机并阅读") }
                    Text("也可以上传到已有 Novel 连接：", style = MaterialTheme.typography.titleMedium)
                }
                items(connections, key = { it.id }) { connection -> OutlinedButton(enabled = valid && !task.busy, onClick = { task.run {
                    container.novel.tools.upload(connection, name) { requireNotNull(contentResolver.openInputStream(requireNotNull(uri))) }
                    task.message = "已上传到 ${connection.name}，可从该连接的书库打开"; valid = false
                } }, modifier = Modifier.fillMaxWidth()) { Text("上传到 ${connection.name}") } }
                item { TextButton(onClick = { finish() }) { Text("关闭") } }
            }
        } } }
    }
}
