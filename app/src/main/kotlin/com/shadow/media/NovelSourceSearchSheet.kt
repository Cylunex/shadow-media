@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.shadow.media

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.shadow.media.library.*
import org.json.JSONObject

@Composable internal fun NovelSourceSearchSheet(repository: NovelRepository, connection: CatalogConnection, work: JSONObject,
    onDismiss: () -> Unit, onBound: () -> Unit, onWork: (String) -> Unit = {}) {
    val state = remember(connection.id, work.optString("id")) { NovelBrowserState(work.optString("title").take(200)) }
    var management by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.95f)) {
            if (management) Column { TextButton(onClick = { management = false }) { Text("返回找书源") }
                NovelToolsScreen(repository, connection, onWork, "sources", onSearch = { state.keyword = it; management = false })
            } else NovelSourceBrowser(repository, connection, state, onWork, { management = true }, work, onBound)
        }
    }
}
