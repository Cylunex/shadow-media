package com.shadow.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shadow.media.database.ShadowMediaDatabase
import com.shadow.media.library.novelScope
import com.shadow.media.reading.ReaderActivity

internal sealed interface LocalMediaLink {
    data class Commute(val minutes: Int) : LocalMediaLink
    data class Saved(val kind: String, val id: String) : LocalMediaLink
}

private val savedId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

internal fun Uri.localMediaLink(): LocalMediaLink? {
    if (scheme != "shadowmedia" || !isHierarchical || fragment != null) return null
    return when (encodedAuthority) {
        "commute" -> {
            if (!path.isNullOrEmpty() || queryParameterNames != setOf("minutes")) return null
            val minutes = getQueryParameters("minutes").singleOrNull()?.toIntOrNull() ?: return null
            LocalMediaLink.Commute(minutes).takeIf { minutes in 1..180 }
        }
        "saved" -> {
            val parts = pathSegments
            if (query != null || parts.size != 2 || parts[0] !in setOf("moment", "quote") || !savedId.matches(parts[1])) return null
            LocalMediaLink.Saved(parts[0], parts[1])
        }
        else -> null
    }
}

internal fun shareSaved(context: Context, kind: String, id: String, title: String, text: String) {
    val link = Uri.Builder().scheme("shadowmedia").authority("saved").appendPath(kind).appendPath(id).build()
    val body = listOf(title, text, link.toString().takeIf { link.localMediaLink() != null }.orEmpty()).filter(String::isNotBlank).joinToString("\n")
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), "分享所选内容"))
}

@Composable internal fun SavedMediaScreen(container: AppContainer, state: MainUiState, link: LocalMediaLink.Saved,
    onMoment: (com.shadow.media.database.MediaMomentEntity) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        TextButton(onClick = onBack) { Text("返回") }
        Text(if (link.kind == "quote") "已保存的阅读摘录" else "已保存的影视时刻", style = MaterialTheme.typography.titleLarge)
        if (link.kind == "quote") {
            val scopes by remember(container) { container.novel.store.connectedProviderIds(::novelScope) }.collectAsStateWithLifecycle(emptyList())
            key(link, scopes) {
                val note by remember(link, scopes) { container.library.dao.savedNovelNote(link.id, scopes) }
                    .collectAsStateWithLifecycle(null)
                note?.let { mark ->
                    Text(mark.note)
                    Button(onClick = { context.startActivity(Intent(context, ReaderActivity::class.java)
                        .putExtra("assetId", mark.assetId).putExtra("novelLocator", mark.locatorJson)) }) { Text("回到原文") }
                } ?: Text("此摘录在当前手机或已连接的书库中不可用。")
            }
        } else {
            val provider = state.session?.providerId
            key(link, provider) {
                val moment by remember(link, provider) {
                    provider?.let { ShadowMediaDatabase.create(context).dao().savedMoment(link.id, it) } ?: flowOf(null)
                }.collectAsStateWithLifecycle(null)
                moment?.let {
                    Text(it.mediaTitle)
                    Button(onClick = { onMoment(it) }) { Text("从 %d:%02d 播放".format(it.positionMs / 60_000, it.positionMs / 1000 % 60)) }
                } ?: Text("此时刻在当前手机或当前影视账号中不可用。")
            }
        }
    }
}
