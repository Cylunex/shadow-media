package top.cylunex.shadowmedia

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun OpenSourceNoticesDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val paragraphs by produceState<List<String>?>(null) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("open-source-notices.txt").bufferedReader().use { it.readText() }
                .split("\n\n").flatMap { it.chunked(4000) }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("开源许可", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
                }
                if (paragraphs == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(paragraphs!!) { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
