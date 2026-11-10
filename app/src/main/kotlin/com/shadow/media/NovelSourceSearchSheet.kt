@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.shadow.media

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.shadow.media.library.*
import com.shadow.media.reading.ReaderModalBottomSheet
import org.json.JSONObject

@Composable internal fun NovelSourceSearchSheet(repository: NovelRepository, connection: CatalogConnection, work: JSONObject,
    onDismiss: () -> Unit, onBound: () -> Unit, onWork: (String) -> Unit = {},
    state: NovelBrowserState = remember(connection.id, work.optString("id")) { NovelBrowserState(work.optString("title").take(200)) },
    onRead: (NovelSourceOpening) -> Unit = {}) {
    var management by remember { mutableStateOf(false) }
    ReaderModalBottomSheet(onDismissRequest = onDismiss, heightFraction = .95f) {
        Box(Modifier.fillMaxSize()) {
            if (management) Column { TextButton(onClick = { management = false }) { Text("返回找书源") }
                NovelToolsScreen(repository, connection, onWork, "sources", onSearch = { state.keyword = it; management = false })
            } else NovelSourceBrowser(repository, connection, state, onWork, { management = true }, work, onBound, onRead)
        }
    }
}
