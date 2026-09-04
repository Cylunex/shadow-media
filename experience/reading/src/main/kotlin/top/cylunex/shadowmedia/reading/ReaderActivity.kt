@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package top.cylunex.shadowmedia.reading

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import org.readium.r2.navigator.epub.*
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.*
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import top.cylunex.shadowmedia.database.LibraryAssetEntity
import top.cylunex.shadowmedia.library.LibraryRepository
import top.cylunex.shadowmedia.library.ProgressWriter

class ReaderActivity : FragmentActivity() {
    private lateinit var library: LibraryRepository
    private var publication: Publication? = null
    private var navigator by mutableStateOf<EpubNavigatorFragment?>(null)
    private var asset by mutableStateOf<LibraryAssetEntity?>(null)
    private var error by mutableStateOf<String?>(null)
    private var ready by mutableStateOf(false)
    private var locator by mutableStateOf<Locator?>(null)
    private var prefs by mutableStateOf(EpubPreferences())
    private var initial: Locator? = null
    private var aloud: ReadAloud? = null
    private var narrating by mutableStateOf(false)
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // The factory requires an asynchronously opened publication. Restore our versioned Locator,
        // not Android's eagerly reconstructed fragment with an unavailable publication instance.
        super.onCreate(null)
        library = LibraryRepository(this)
        val id = intent.getStringExtra("assetId")
        val saved = savedInstanceState?.getString("locator")
        val settings = getSharedPreferences("reader_preferences", MODE_PRIVATE)
        prefs = EpubPreferences(fontSize = settings.getFloat("font", 115f).toDouble(), lineHeight = settings.getFloat("line", 1.7f).toDouble(),
            theme = runCatching { Theme.valueOf(settings.getString("theme", "SEPIA")!!) }.getOrDefault(Theme.SEPIA), publisherStyles = false, scroll = settings.getBoolean("scroll", false))
        lifecycleScope.launch {
            try {
                val item = requireNotNull(id?.let { library.dao.asset(it) }) { "图书已移除" }
                asset = item
                require(item.format in setOf("epub", "txt")) { "此阅读器支持 EPUB 和 TXT" }
                val file = library.publicationFile(item)
                initial = (saved ?: library.dao.progress(item.id)?.locatorJson)?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
                val pub = withContext(Dispatchers.IO) {
                    val http = DefaultHttpClient()
                    val retriever = AssetRetriever(contentResolver, http)
                    val resource = retriever.retrieve(file.toUrl()).getOrElse { throw IllegalArgumentException("无法读取本地图书") }
                    PublicationOpener(DefaultPublicationParser(this@ReaderActivity, http, retriever, pdfFactory = null)).open(resource, allowUserInteraction = false)
                        .getOrElse { throw IllegalArgumentException("图书格式损坏或受 DRM 保护") }
                }
                publication = pub
                supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(initialLocator = initial, initialPreferences = prefs)
                ready = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "图书打开失败" }
        }
        setContent { ReaderChrome() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        locator?.let { outState.putString("locator", it.toJSON().toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun attach() {
        if (navigator != null) return
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        supportFragmentManager.beginTransaction().replace(R.id.epub_reader, fragment).commitNow()
        navigator = fragment
        fragment.addInputListener(DirectionalNavigationAdapter(fragment))
        lifecycleScope.launch {
            fragment.currentLocator.collectLatest { current ->
                locator = current
                asset?.let { ProgressWriter.save(library, it.id, "text", current.toJSON(), current.locations.totalProgression) }
            }
        }
        asset?.let { item -> lifecycleScope.launch {
            library.dao.annotations(item.id).collectLatest { marks ->
                fragment.applyDecorations(marks.mapNotNull { mark ->
                    val location = runCatching { Locator.fromJSON(JSONObject(mark.locatorJson)) }.getOrNull()
                    location?.takeIf { !it.text.highlight.isNullOrBlank() }?.let {
                        Decoration(mark.id, it, Decoration.Style.Highlight(android.graphics.Color.rgb(180, 202, 230)))
                    }
                }, "shadow.annotations")
            }
        } }
    }

    private fun updatePrefs(next: EpubPreferences) {
        prefs = next; navigator?.submitPreferences(next)
        getSharedPreferences("reader_preferences", MODE_PRIVATE).edit().putFloat("font", (next.fontSize ?: 115.0).toFloat())
            .putFloat("line", (next.lineHeight ?: 1.7).toFloat()).putString("theme", (next.theme ?: Theme.SEPIA).name).putBoolean("scroll", next.scroll == true).apply()
    }

    @Composable private fun ReaderChrome() {
        val night = prefs.theme == Theme.DARK
        MaterialTheme(colorScheme = if (night) darkColorScheme(primary = Color(0xFF87B8F9)) else lightColorScheme(
            background = Color(0xFFF2E9D6), surface = Color(0xFFF2E9D6), primary = Color(0xFF755F39), onSurface = Color(0xFF342D22))) {
            var panel by remember { mutableStateOf<String?>(null) }
            var message by remember { mutableStateOf<String?>(null) }
            var searchQuery by remember { mutableStateOf("") }
            var matches by remember { mutableStateOf<List<Locator>>(emptyList()) }
            var searching by remember { mutableStateOf(false) }
            var searchIterator by remember { mutableStateOf<SearchIterator?>(null) }
            var hasMore by remember { mutableStateOf(false) }
            var encodingChange by remember { mutableStateOf<String?>(null) }
            var editingNote by remember { mutableStateOf<top.cylunex.shadowmedia.database.ContentAnnotationEntity?>(null) }
            var noteText by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            DisposableEffect(searchIterator) { val iterator = searchIterator; onDispose { iterator?.close() } }
            Scaffold(topBar = { TopAppBar(title = { Text(asset?.title ?: "阅读", maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回书架") }
            }, actions = {
                IconButton(onClick = { panel = "搜索" }) { Icon(Icons.Rounded.Search, "全文搜索") }
                IconButton(onClick = { panel = "目录" }) { Icon(Icons.Rounded.FormatListBulleted, "目录") }
                IconButton(onClick = { panel = "书签" }) { Icon(Icons.Rounded.Bookmarks, "书签") }
                IconButton(onClick = { panel = "排版" }) { Icon(Icons.Rounded.TextFields, "排版") }
            }) }, bottomBar = {
                Row(Modifier.navigationBarsPadding().fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { navigator?.goBackward() }) { Icon(Icons.AutoMirrored.Rounded.NavigateBefore, "上一页") }
                    TextButton(onClick = { scope.launch {
                        val location = navigator?.currentSelection()?.locator ?: locator
                        if (location != null) asset?.let { library.bookmark(it.id, location.toJSON().toString(), location.text.highlight ?: location.title ?: "书签"); message = "已保存书签或选中文本" }
                    } }) { Text("${((locator?.locations?.totalProgression ?: 0.0) * 100).toInt()}% · 标记") }
                    IconButton(onClick = { navigator?.goForward() }) { Icon(Icons.AutoMirrored.Rounded.NavigateNext, "下一页") }
                    IconButton(onClick = {
                        if (narrating) aloud?.stop() else scope.launch {
                            try {
                                narrating = true
                                val reader = aloud ?: ReadAloud(this@ReaderActivity).also { aloud = it }
                                publication?.let { reader.read(it, locator) { location -> navigator?.go(location) } }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = e.message ?: "朗读失败" }
                            finally { narrating = false }
                        }
                    }) { Icon(if (narrating) Icons.Rounded.Stop else Icons.Rounded.RecordVoiceOver, if (narrating) "停止朗读" else "离线朗读，退出阅读时停止") }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when {
                        error != null -> Text(error!!, Modifier.padding(24.dp))
                        ready -> AndroidView(factory = { FragmentContainerView(it).apply { id = R.id.epub_reader; post { if (!isFinishing && !supportFragmentManager.isStateSaved) attach() } } }, modifier = Modifier.fillMaxSize())
                        else -> CircularProgressIndicator(Modifier.padding(24.dp))
                    }
                }
            }
            if (panel != null) ModalBottomSheet(onDismissRequest = { panel = null }) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(panel!!, style = MaterialTheme.typography.titleLarge)
                    when (panel) {
                        "搜索" -> {
                            OutlinedTextField(searchQuery, { searchQuery = it }, Modifier.fillMaxWidth(), placeholder = { Text("输入书中内容") }, singleLine = true)
                            TextButton(enabled = searchQuery.isNotBlank(), onClick = {
                                searchJob?.cancel()
                                searchJob = scope.launch {
                                    searching = true; matches = emptyList(); searchIterator = null; hasMore = false
                                    try {
                                        val iterator = publication?.search(searchQuery.trim()) ?: error("本书不支持全文搜索")
                                        searchIterator = iterator
                                        val page = withContext(Dispatchers.IO) { iterator.next().getOrElse { error("搜索失败") } }
                                        matches = page?.locators.orEmpty(); hasMore = page != null
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { message = e.message }
                                    finally { searching = false }
                                }
                            }) { Text("搜索") }
                            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                            LazyColumn(Modifier.heightIn(max = 380.dp)) { items(matches) { found ->
                                TextButton(onClick = { navigator?.go(found); panel = null }) { Text(listOfNotNull(found.text.before, found.text.highlight, found.text.after).joinToString(""), maxLines = 3) }
                            } }
                            if (hasMore) TextButton(enabled = !searching, onClick = { searchJob = scope.launch {
                                searching = true
                                try {
                                    val page = withContext(Dispatchers.IO) { searchIterator?.next()?.getOrElse { error("搜索失败") } }
                                    matches = matches + page?.locators.orEmpty(); hasMore = page != null
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { message = e.message }
                                finally { searching = false }
                            } }) { Text("更多结果") }
                        }
                        "目录" -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                            fun flatten(links: List<Link>): List<Link> = links.flatMap { listOf(it) + flatten(it.children) }
                            items(flatten(publication?.tableOfContents.orEmpty()).ifEmpty { publication?.readingOrder.orEmpty() }) { link ->
                                TextButton(onClick = { navigator?.go(link); panel = null }) { Text(link.title ?: link.href.toString()) }
                            }
                        }
                        "书签" -> asset?.let { item ->
                            val marks by library.dao.annotations(item.id).collectAsState(emptyList())
                            if (marks.isEmpty()) Text("选中文字或点击底部“标记”保存位置。", Modifier.padding(vertical = 16.dp))
                            LazyColumn(Modifier.heightIn(max = 480.dp)) { items(marks, key = { it.id }) { mark ->
                                Row { TextButton(onClick = { runCatching { Locator.fromJSON(JSONObject(mark.locatorJson)) }.getOrNull()?.let { navigator?.go(it) }; panel = null }, Modifier.weight(1f)) { Text(mark.note, maxLines = 3) }
                                    IconButton(onClick = { editingNote = mark; noteText = mark.note }) { Icon(Icons.Rounded.EditNote, "编辑笔记") }
                                    IconButton(onClick = { scope.launch { library.dao.removeAnnotation(mark.id) } }) { Icon(Icons.Rounded.DeleteOutline, "删除书签") } }
                            } }
                        }
                        "排版" -> {
                            Text("字号 ${prefs.fontSize?.toInt()}%")
                            Slider(value = (prefs.fontSize ?: 115.0).toFloat(), onValueChange = { updatePrefs(prefs.copy(fontSize = it.toDouble())) }, valueRange = 80f..200f)
                            Text("行距")
                            Slider(value = (prefs.lineHeight ?: 1.7).toFloat(), onValueChange = { updatePrefs(prefs.copy(lineHeight = it.toDouble())) }, valueRange = 1.2f..2.5f)
                            Row { listOf("纸色" to Theme.SEPIA, "夜间" to Theme.DARK, "浅色" to Theme.LIGHT).forEach { (label, theme) -> TextButton(onClick = { updatePrefs(prefs.copy(theme = theme)) }) { Text(label) } } }
                            Row { Text("连续滚动", Modifier.weight(1f)); Switch(checked = prefs.scroll == true, onCheckedChange = { updatePrefs(prefs.copy(scroll = it)) }) }
                            if (asset?.format == "txt") {
                                Text("文本编码：${asset?.let { library.textEncoding(it.id) }}", style = MaterialTheme.typography.labelLarge)
                                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                                    items(listOf("自动", "UTF-8", "GB18030", "Big5", "UTF-16LE", "UTF-16BE")) { encoding ->
                                        TextButton(onClick = { encodingChange = encoding }) { Text(encoding) }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            message?.let { AlertDialog(onDismissRequest = { message = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { message = null }) { Text("确定") } }) }
            editingNote?.let { mark -> AlertDialog(onDismissRequest = { editingNote = null }, title = { Text("笔记") },
                text = { OutlinedTextField(noteText, { noteText = it.take(10000) }, label = { Text("你的想法") }, minLines = 3) },
                confirmButton = { TextButton(onClick = { scope.launch { library.dao.putAnnotation(mark.copy(note = noteText)); editingNote = null } }) { Text("保存") } },
                dismissButton = { TextButton(onClick = { editingNote = null }) { Text("取消") } }) }
            encodingChange?.let { encoding -> AlertDialog(onDismissRequest = { encodingChange = null }, title = { Text("切换为 $encoding？") },
                text = { Text("重新解码可能改变章节位置，将从头打开。原 TXT 和已有书签不会删除，但旧书签位置可能失效。") },
                confirmButton = { TextButton(onClick = { asset?.let { item -> lifecycleScope.launch {
                    library.setTextEncoding(item.id, encoding)
                    startActivity(android.content.Intent(this@ReaderActivity, ReaderActivity::class.java).putExtra("assetId", item.id))
                    finish()
                } }; encodingChange = null }) { Text("重新打开") } }, dismissButton = { TextButton(onClick = { encodingChange = null }) { Text("取消") } }) }
        }
    }
    override fun onStop() { aloud?.stop(); super.onStop() }
    override fun onDestroy() { searchJob?.cancel(); aloud?.close(); super.onDestroy(); publication?.close() }
}
