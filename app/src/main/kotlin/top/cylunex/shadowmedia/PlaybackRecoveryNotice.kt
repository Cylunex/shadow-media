package top.cylunex.shadowmedia

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.cylunex.shadowmedia.playback.PlaybackRecoveryState

@Composable
internal fun PlaybackRecoveryNotice(
    state: PlaybackRecoveryState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    val message = state.message ?: error ?: return
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xEE18212B), contentColor = Color.White)) {
        Column(Modifier.padding(12.dp)) {
            Text(message)
            TextButton(onClick = onRetry) { Text("从当前位置重试") }
        }
    }
}
