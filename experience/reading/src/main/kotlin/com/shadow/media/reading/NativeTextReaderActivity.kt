@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media.reading

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import com.shadow.media.database.LibraryAssetEntity
import com.shadow.media.database.NovelPublicationEntity
import com.shadow.media.database.NovelReadingStateEntity
import com.shadow.media.library.*

/** TXT and Novel share the Legado-derived native layout and the existing atomic progress writer. */
class NativeTextReaderActivity : ComponentActivity() {
    private lateinit var library: LibraryRepository
    private lateinit var novel: NovelRepository
    private lateinit var source: NativeTextSource
    private lateinit var store: ReaderSettingsStore
    private var style by mutableStateOf(ReaderSettings())
    private var asset by mutableStateOf<LibraryAssetEntity?>(null)
    private var publication by mutableStateOf<NovelPublicationEntity?>(null)
    private var chapter by mutableStateOf<NativeTextChapter?>(null)
    private var state by mutableStateOf<NovelReadingStateEntity?>(null)
    private var menu by mutableStateOf(false)
    private var panel by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(true)
    private var message by mutableStateOf<String?>(null)
    private var fatal by mutableStateOf<String?>(null)
    private var offset by mutableIntStateOf(0)
    private var pageLabel by mutableStateOf("")
    private var contents by mutableStateOf<List<NativeChapterRef>>(emptyList())
    private var contentsOffset = 0
    private var contentsTotal by mutableIntStateOf(0)
    private var contentsBusy by mutableStateOf(false)
    private var view: NativeTextView? = null
    private var session: ProgressSession? = null
    private var observer: Job? = null
    private var sync: Job? = null
    private var aloud: ReadAloud? = null
    private var aloudJob: Job? = null
    private var narrating by mutableStateOf(false)
    private var excerpt by mutableStateOf<Pair<String, Int>?>(null)
    private var nativeSaved: JSONObject? = null
    private var pendingShow: Triple<NativeTextChapter, String, Int>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = LibraryRepository(this); novel = NovelRepository(this, library); source = NativeTextSource(library, novel)
        store = ReaderSettingsStore(getSharedPreferences("reader_preferences", MODE_PRIVATE)); style = store.load()
        nativeSaved = savedInstanceState?.getString("native_locator")?.let { runCatching { JSONObject(it) }.getOrNull() }
        WindowCompat.setDecorFitsSystemWindows(window, false); applyWindow()
        setContent { Chrome() }
        lifecycleScope.launch {
            try {
                val item = requireNotNull((savedInstanceState?.getString("active_asset") ?: intent.getStringExtra("assetId"))?.let { library.dao.asset(it) }) { "图书已移除" }
                val pub = novel.dao.publication(item.id)
                val locator = nativeSaved ?: intent.getStringExtra("novelLocator")?.let(::JSONObject)
                    ?: pub?.let { novel.dao.state(NovelRepository.stateId(it.scope, it.workId))?.localPosition?.takeIf(String::isNotBlank)?.let(::JSONObject)?.let { p -> NovelPublicationMap(JSONObject(it.mappingJson)).locator(p) } }
                    ?: library.dao.progress(item.id)?.locatorJson?.let(::JSONObject)
                open(item, locator)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { busy = false; fatal = e.message ?: "正文打开失败" }
        }
    }
    private suspend fun open(item: LibraryAssetEntity, locator: JSONObject?, lastPage: Boolean = false) {
        val pub = novel.dao.publication(item.id)
        require(item.format == "txt" || pub != null) { "此阅读器支持 TXT 和 Novel 正文" }
        val content = source.chapter(item, pub, locator?.optString("href"),
            if (locator?.optJSONObject("native_text") == null) locator?.optJSONObject("locations")?.optDouble("progression", 0.0)?.takeIf { it.isFinite() } else null)
        val restored = if (lastPage) content.text.length else content.restore(locator)
        val newSession = if (asset?.id != item.id || session == null) ProgressWriter.begin(library, item) else session
        asset = item; publication = pub; chapter = content; offset = restored; session = newSession
        if (pub != null) {
            observer?.cancel()
            observer = lifecycleScope.launch { novel.observe(pub.scope, pub.workId).collectLatest { state = it } }
        } else { observer?.cancel(); state = null }
        pendingShow = Triple(content, item.title, restored)
        view?.let { it.isEnabled = false; it.show(content, item.title, restored, style); pendingShow = null }
    }
    private fun positionChanged(value: Int, user: Boolean) {
        offset = value
        pageLabel = view?.let { "${it.pageIndex + 1} / ${it.document?.pages?.size ?: 1} 页" }.orEmpty()
        if (!user || busy) return
        val content = chapter ?: return
        val locator = content.locator(value)
        val fraction = (content.ref.ordinal + value.toDouble() / content.text.length.coerceAtLeast(1)) / content.total.coerceAtLeast(1)
        session?.let { ProgressWriter.save(library, it, "text", locator, fraction, publication == null && value >= content.text.length - 1 && content.ref.ordinal == content.total - 1) }
        if (publication != null) {
            sync?.cancel(); sync = lifecycleScope.launch { delay(1000); ProgressWriter.flush(); NovelRepository.schedule(this@NativeTextReaderActivity) }
        }
    }
    private fun navigate(block: suspend () -> Unit) {
        if (busy) return
        stopAloud(); busy = true; view?.isEnabled = false
        lifecycleScope.launch {
            try { ProgressWriter.flush(); block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { busy = false; view?.isEnabled = true; message = e.message ?: "章节获取失败，当前内容已保留" }
        }
    }
    private fun openChapter(ref: NativeChapterRef, end: Boolean = false) = navigate { loadChapter(ref, end) }
    private suspend fun loadChapter(ref: NativeChapterRef, end: Boolean = false) {
        val currentAsset = requireNotNull(asset)
        val pub = publication
        if (pub == null) open(currentAsset, JSONObject().put("href", ref.href), end)
        else {
            val inPackage = NovelPublicationMap(JSONObject(pub.mappingJson)).chapter(ref.id) != null
            val prepared = if (inPackage) NovelRepository.Prepared(currentAsset, null)
                else novel.cachedChapter(pub.scope, pub.workId, pub.versionId, ref.id)
                    ?: novel.prepare(novel.connection(pub.scope), pub.workId, pub.versionId, ref.id)
            open(prepared.asset, JSONObject().put("href", ref.href), end)
        }
        // Entering another chapter is an intentional movement; save only after its layout is ready.
        saveAfterLayout = true
    }
    private var saveAfterLayout = false
    private fun boundary(direction: Int) = navigate {
        val current = requireNotNull(chapter)
        val next = adjacent(current.ref.ordinal, direction)
        if (next == null) {
            message = if (direction > 0) "已到本书末尾" else "已到本书开头"
            busy = false; view?.isEnabled = true
            if (direction > 0) view?.seek(current.text.length)
        } else loadChapter(next, direction < 0)
    }
    private suspend fun adjacent(ordinal: Int, direction: Int): NativeChapterRef? {
        val pub = publication ?: return source.local(requireNotNull(asset)).getOrNull(ordinal + direction)?.ref
        val mapping = NovelPublicationMap(JSONObject(pub.mappingJson))
        if (direction < 0 && ordinal == 0 || direction > 0 && ordinal >= mapping.json.getInt("total") - 1) return null
        val cached = novel.cachedContents(pub).map(::ref)
        cached.firstOrNull { it.ordinal == ordinal + direction }?.let { return it }
        var start = if (direction > 0) ordinal + 1 else (ordinal - 100).coerceAtLeast(0)
        while (true) {
            val page = novel.get(novel.connection(pub.scope), listOf("works", pub.workId, "contents"), listOf("version_id" to pub.versionId, "offset" to start.toString(), "limit" to "100"))
            val candidates = page.novelObjects("items").map(::ref).filter { it.readable && if (direction > 0) it.ordinal > ordinal else it.ordinal < ordinal }
            (if (direction > 0) candidates.firstOrNull() else candidates.lastOrNull())?.let { return it }
            if (direction > 0) { start += 100; if (start >= page.optInt("total")) return null }
            else { if (start == 0) return null; start = (start - 100).coerceAtLeast(0) }
        }
    }
    private fun ref(json: JSONObject) = NativeChapterRef(json.getString("id"), json.optString("title"), json.getInt("ordinal"), "chapters/${json.getString("id")}.xhtml", json.optBoolean("readable", true))
    private fun directory(more: Boolean = false) {
        if (contentsBusy) return
        contentsBusy = true
        lifecycleScope.launch {
            try {
                val pub = publication
                if (pub == null) { contents = source.local(requireNotNull(asset)).map { it.ref }; contentsTotal = contents.size }
                else {
                    val start = if (more) contentsOffset + 100 else 0
                    val page = novel.get(novel.connection(pub.scope), listOf("works", pub.workId, "contents"), listOf("version_id" to pub.versionId, "offset" to start.toString(), "limit" to "100"))
                    contents = (if (more) contents else emptyList()) + page.novelObjects("items").map(::ref)
                    contentsOffset = start; contentsTotal = page.optInt("total")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!more) { contents = publication?.let { novel.cachedContents(it).map(::ref) }.orEmpty(); contentsTotal = contents.size }
                message = e.message ?: "显示本机已下载目录"
            } finally { contentsBusy = false }
        }
    }
    private fun update(value: ReaderSettings) { style = value.normalized(); store.save(style); view?.configure(style); applyWindow() }
    private fun applyWindow() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = style.paper != ReaderPaper.NIGHT; isAppearanceLightNavigationBars = style.paper != ReaderPaper.NIGHT
            if (style.fullScreen && !menu && panel == null) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
        if (style.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = style.brightness }
    }
    private fun toggleMenu() { menu = !menu; applyWindow() }
    private fun mark(location: JSONObject, note: String) { lifecycleScope.launch { asset?.let { library.bookmark(it.id, location.toString(), note); message = "已保存书签" } } }
    private fun resolve(remote: Boolean) = navigate {
        val pub = requireNotNull(publication)
        if (remote) {
            val prepared = novel.useRemote(pub.scope, pub.workId)
            open(prepared.asset, prepared.locator?.let(::JSONObject))
        } else { novel.keepLocal(pub.scope, pub.workId); busy = false; view?.isEnabled = true }
    }
    private fun stopAloud() { aloudJob?.cancel(); aloud?.stop(); narrating = false }
    private fun speak() {
        if (narrating) { stopAloud(); return }
        val content = chapter ?: return
        aloudJob = lifecycleScope.launch {
            narrating = true
            try {
                val reader = aloud ?: ReadAloud(this@NativeTextReaderActivity).also { aloud = it }
                reader.readText(content.text, offset) { view?.seek(it) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "朗读失败，请检查系统语音包" }
            finally { narrating = false }
        }
    }

    @Composable private fun Chrome() {
        val night = style.paper == ReaderPaper.NIGHT
        MaterialTheme(colorScheme = if (night) darkColorScheme() else lightColorScheme(surface = Color(style.paper.background), background = Color(style.paper.background), primary = Color(0xFF755F39))) {
            val writerError by ProgressWriter.error.collectAsState()
            var bookmarkAssets by remember { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(asset?.id) {
                val pub = publication
                bookmarkAssets = if (pub == null) listOfNotNull(asset?.id) else novel.dao.publications(pub.scope)
                    .filter { it.workId == pub.workId && it.versionId == pub.versionId }.map { it.assetId }
            }
            var query by remember { mutableStateOf("") }
            var hits by remember { mutableStateOf<List<Pair<NativeTextChapter, Int>>>(emptyList()) }
            var searching by remember { mutableStateOf(false) }
            var searchJob by remember { mutableStateOf<Job?>(null) }
            BackHandler(menu || panel != null) { if (panel != null) panel = null else menu = false; applyWindow() }
            Box(Modifier.fillMaxSize().background(Color(style.paper.background)).displayCutoutPadding()) {
                AndroidView(factory = { context -> NativeTextView(context).also { reader ->
                    view = reader
                    reader.onMenu = ::toggleMenu; reader.onBoundary = ::boundary; reader.onPosition = ::positionChanged
                    reader.onSettings = ::update; reader.onExcerpt = { text, position -> excerpt = text to position }
                    reader.onError = { fatal = it; busy = false }
                    reader.onReady = {
                        busy = false; reader.isEnabled = true
                        if (saveAfterLayout) { saveAfterLayout = false; positionChanged(reader.readingOffset, true) }
                    }
                    pendingShow?.let { (content, title, restored) -> reader.show(content, title, restored, style); pendingShow = null }
                } }, modifier = Modifier.fillMaxSize())
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                fatal?.let { text -> Column(Modifier.align(Alignment.Center).padding(28.dp)) { Text(text); TextButton(onClick = { finish() }) { Text("返回书架") } } }
                if (menu) {
                    Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding(), tonalElevation = 4.dp) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { finish() }) { Text("返回") }; Text(asset?.title.orEmpty(), Modifier.weight(1f), maxLines = 1) }
                            Text(chapter?.ref?.title.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            val status = when { state?.conflictJson?.isNotBlank() == true -> "进度有冲突，请选择保留的位置"; state?.message?.isNotBlank() == true -> state?.message; state?.pending == true -> "进度已保存在本机，等待同步"; publication != null -> "进度已同步"; else -> null }
                            (writerError ?: status)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (state?.conflictJson?.isNotBlank() == true) Row { TextButton(onClick = { resolve(false) }, enabled = !busy) { Text("保留本机进度") }; TextButton(onClick = { resolve(true) }, enabled = !busy) { Text("使用云端进度") } }
                        }
                    }
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(), tonalElevation = 4.dp) {
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                listOf("目录", "排版", "搜索", "书签").forEach { label -> TextButton(onClick = { panel = label; if (label == "目录") directory(); applyWindow() }) { Text(label) } }
                            }
                            ReaderProgressControl((offset.toFloat() / (chapter?.text?.length ?: 1).coerceAtLeast(1)).coerceIn(0f, 1f), !busy && chapter != null, "本章") { ratio -> view?.seek(((chapter?.text?.length ?: 0) * ratio).toInt()) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                TextButton(enabled = !busy, onClick = { view?.turn(-1) }) { Text("上一页") }
                                TextButton(onClick = { chapter?.let { mark(it.locator(offset), it.ref.title) } }) { Text("标记") }
                                Text(pageLabel, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = ::speak) { Text(if (narrating) "停止" else "朗读") }
                                TextButton(enabled = !busy, onClick = { view?.turn(1) }) { Text("下一页") }
                            }
                        }
                    }
                }
            }
            if (panel != null) ModalBottomSheet(onDismissRequest = { panel = null; applyWindow() }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
                    Text(panel.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    when (panel) {
                        "排版" -> ReaderPreferencesPanel(style, ::update, nativeText = true, encoding = if (asset?.format == "txt") ({
                            Text("文本编码：${asset?.let { library.textEncoding(it.id) }}")
                            FlowRow { listOf("自动", "UTF-8", "GB18030", "Big5", "UTF-16LE", "UTF-16BE").forEach { encoding -> TextButton(onClick = {
                                navigate { val item = requireNotNull(asset); library.setTextEncoding(item.id, encoding); source.invalidate(); session = null; open(item, null) }
                            }) { Text(encoding) } } }
                        }) else null)
                        "目录" -> {
                            if (contentsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                                items(contents, key = { it.id }) { ref -> TextButton(enabled = !busy && ref.readable, onClick = { panel = null; menu = false; applyWindow(); openChapter(ref) }) { Text("${ref.ordinal + 1}. ${ref.title}${if (ref.id == chapter?.ref?.id) " · 正在读" else ""}") } }
                                if (contents.size < contentsTotal) item { TextButton(enabled = !contentsBusy, onClick = { directory(true) }) { Text("更多目录") } }
                            }
                        }
                        "搜索" -> {
                            Text(if (publication == null) "搜索本书全文" else "搜索本机已下载章节", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("输入正文内容") })
                            TextButton(enabled = query.isNotBlank(), onClick = {
                                searchJob?.cancel(); val term = query.trim()
                                searchJob = lifecycleScope.launch {
                                    searching = true
                                    try {
                                        val currentAsset = requireNotNull(asset); val pub = publication
                                        val chapters = if (pub == null) source.local(currentAsset) else {
                                            novel.cachedContents(pub).mapNotNull { ref ->
                                                val prepared = novel.cachedChapter(pub.scope, pub.workId, pub.versionId, ref.getString("id"))
                                                prepared?.let { source.chapter(it.asset, novel.dao.publication(it.asset.id), ref.getString("href")) }
                                            }
                                        }
                                        hits = withContext(Dispatchers.Default) { buildList { for (content in chapters) {
                                            var index = content.text.indexOf(term, ignoreCase = true)
                                            while (index >= 0 && size < 200) { ensureActive(); add(content to index); index = content.text.indexOf(term, index + term.length, true) }
                                            if (size >= 200) break
                                        } } }
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { message = e.message }
                                    finally { searching = false }
                                }
                            }) { Text("搜索") }
                            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                            LazyColumn(Modifier.heightIn(max = 380.dp)) { items(hits) { (content, found) -> TextButton(onClick = {
                                panel = null; menu = false; applyWindow()
                                if (content.ref.id == chapter?.ref?.id) view?.seek(found)
                                else navigate {
                                    val pub = publication
                                    val item = if (pub == null) requireNotNull(asset) else requireNotNull(novel.cachedChapter(pub.scope, pub.workId, pub.versionId, content.ref.id)).asset
                                    open(item, content.locator(found)); saveAfterLayout = true
                                }
                            }) { Text("${content.ref.title}：${content.text.substring((found - 15).coerceAtLeast(0), (found + 65).coerceAtMost(content.text.length))}", maxLines = 3) } } }
                        }
                        "书签" -> asset?.let { current ->
                            val marks by library.dao.annotationsForAssets(bookmarkAssets).collectAsState(emptyList())
                            LazyColumn(Modifier.heightIn(max = 480.dp)) { items(marks, key = { it.id }) { mark -> Row {
                                TextButton(modifier = Modifier.weight(1f), onClick = {
                                    val locator = JSONObject(mark.locatorJson); panel = null
                                    if (locator.optString("href") == chapter?.ref?.href) view?.seek(chapter!!.restore(locator))
                                    else navigate { open(requireNotNull(library.dao.asset(mark.assetId)) { "书签对应内容已移除" }, locator); saveAfterLayout = true }
                                }) { Text(mark.note, maxLines = 3) }
                                TextButton(onClick = { lifecycleScope.launch { library.dao.removeAnnotation(mark.id) } }) { Text("删除") }
                            } } }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            excerpt?.let { (text, position) -> AlertDialog(onDismissRequest = { excerpt = null }, title = { Text("段落") }, text = { Text(text.take(1500)) },
                confirmButton = { TextButton(onClick = { chapter?.let { mark(it.locator(position), text.take(1000)) }; excerpt = null }) { Text("保存摘录") } },
                dismissButton = { TextButton(onClick = { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("正文", text)); excerpt = null }) { Text("复制") } }) }
            message?.let { text -> AlertDialog(onDismissRequest = { message = null }, text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("确定") } }) }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { chapter?.let { outState.putString("native_locator", it.locator(offset).toString()) }; asset?.let { outState.putString("active_asset", it.id) }; super.onSaveInstanceState(outState) }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (style.volumeToTurn && !menu && panel == null && !narrating && keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) { if (event.repeatCount == 0) view?.turn(if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1); return true }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = if (style.volumeToTurn && !menu && panel == null && !narrating && keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) true else super.onKeyUp(keyCode, event)
    override fun onStop() { stopAloud(); view?.pause(); if (publication != null) lifecycleScope.launch { ProgressWriter.flush(); NovelRepository.schedule(this@NativeTextReaderActivity) }; super.onStop() }
    override fun onDestroy() { view?.dispose(); view = null; aloud?.close(); super.onDestroy() }
}
