package com.shadow.media

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shadow.media.library.*
import com.shadow.media.database.ContentAnnotationEntity
import com.shadow.media.reading.ReaderActivity
import kotlinx.coroutines.CancellationException

@Composable internal fun NovelNotesLibrary(repository: NovelRepository, scope: String, workId: String? = null) {
    val context = LocalContext.current
    val library = remember { LibraryRepository(context) }
    var query by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf<List<ContentAnnotationEntity>>(emptyList()) }
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var identities by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scope) {
        try {
            val publications = repository.dao.publications(scope)
            val names = mutableMapOf<String, String>()
            for (publication in publications) library.dao.asset(publication.assetId)?.let { names[it.id] = it.title }
            titles = names
            identities = publications.associate { it.assetId to it.workId }
            library.dao.novelAnnotations(scope).collect { notes = it }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("书签与摘录", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(query, { query = it }, label = { Text("搜索作品、笔记或原文") }, singleLine = true)
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        val visible = notes.filter { (workId == null || identities[it.assetId] == workId) && (query.isBlank() || (titles[it.assetId].orEmpty() + it.note + it.locatorJson).contains(query, true)) }
        visible.groupBy { identities[it.assetId] ?: it.assetId }.forEach { (work, group) ->
            item(key = "work:$work") { Text(titles[group.first().assetId].orEmpty(), style = MaterialTheme.typography.titleMedium) }
            items(group, key = { it.id }) { note -> Card(onClick = {
                context.startActivity(Intent(context, ReaderActivity::class.java).putExtra("assetId", note.assetId).putExtra("novelLocator", note.locatorJson))
            }) { Column(Modifier.padding(16.dp)) { Text(note.note.ifBlank { "书签" }); Text("点击回到原文", style = MaterialTheme.typography.labelSmall) } } }
        }
        if (visible.isEmpty()) item { Text("这里汇集本机保存的书签与摘录，可从阅读页长按正文添加。") }
    }
}
