@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media.reading

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
    private var jumpHistory by mutableStateOf(NativeJumpHistory())
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
        installReaderBackNavigation({ panel != null || excerpt != null }) { if (excerpt != null) excerpt = null else panel = null; applyWindow() }
        library = LibraryRepository(this); novel = NovelRepository(this, library); source = NativeTextSource(library, novel)
        store = ReaderSettingsStore(getSharedPreferences("reader_preferences", MODE_PRIVATE)); style = store.load()
        jumpHistory = NativeJumpHistory.restore(savedInstanceState?.getString("jump_history"))
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
    private fun location(): NativeReaderLocation? = asset?.let { item -> chapter?.let { NativeReaderLocation(item.id, it.locator(offset).toString()) } }
    private fun closePanel() { panel = null; menu = false; applyWindow() }
    private fun openChapter(ref: NativeChapterRef, end: Boolean = false) = navigate {
        val origin = location()
        loadChapter(ref, end)
        jumpHistory = jumpHistory.remember(origin, location())
    }
    private fun jumpTo(target: NativeReaderLocation) = navigate {
        val origin = location()
        open(requireNotNull(library.dao.asset(target.assetId)) { "跳转对应内容已移除" }, JSONObject(target.locator))
        saveAfterLayout = true
        jumpHistory = jumpHistory.remember(origin, location())
    }
    private fun returnFromJump() = navigate {
        val target = jumpHistory.locations.lastOrNull()
        if (target == null) { busy = false; view?.isEnabled = true }
        else {
            open(requireNotNull(library.dao.asset(target.assetId)) { "原位置对应内容已移除" }, JSONObject(target.locator))
            saveAfterLayout = true
            jumpHistory = NativeJumpHistory(jumpHistory.locations.dropLast(1))
        }
    }
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
        MaterialTheme(colorScheme = readerColorScheme(style.paper)) {
            val writerError by ProgressWriter.error.collectAsState()
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(message) {
                val text = message ?: return@LaunchedEffect
                snackbar.showSnackbar(text)
                if (message == text) message = null
            }
            var bookmarkAssets by remember { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(asset?.id) {
                val pub = publication
                bookmarkAssets = if (pub == null) listOfNotNull(asset?.id) else novel.dao.publications(pub.scope)
                    .filter { it.workId == pub.workId && it.versionId == pub.versionId }.map { it.assetId }
            }
            BoxWithConstraints(Modifier.fillMaxSize().background(Color(style.paper.background)).displayCutoutPadding()) {
                val compactControls = maxHeight < 480.dp
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
                } }, update = { it.gesturesEnabled = !menu && panel == null && excerpt == null }, modifier = Modifier.fillMaxSize())
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                fatal?.let { text -> Column(Modifier.align(Alignment.Center).padding(28.dp)) { Text(text); TextButton(onClick = { finish() }) { Text("返回书架") } } }
                if (menu) {
                    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menu = false; applyWindow() })
                    Box(Modifier.align(Alignment.TopCenter)) {
                        val status = when {
                            state?.conflictJson?.isNotBlank() == true -> "进度有冲突，请选择保留的位置"
                            state?.message?.isNotBlank() == true -> state?.message
                            state?.pending == true -> "进度已保存在本机，等待同步"
                            publication != null && state != null -> "进度已同步"
                            else -> "进度保存在本机"
                        }
                        NativeReaderHeader(asset?.title.orEmpty(), chapter?.ref?.title.orEmpty(), writerError ?: status, !busy,
                            onBack = { finish() }, onClose = { menu = false; applyWindow() },
                            onBookmark = { chapter?.let { mark(it.locator(offset), it.ref.title) } },
                            conflict = if (state?.conflictJson?.isNotBlank() == true) ({
                                FlowRow(Modifier.padding(horizontal = 12.dp)) {
                                    TextButton(onClick = { resolve(false) }, enabled = !busy) { Text("保留本机进度") }
                                    TextButton(onClick = { resolve(true) }, enabled = !busy) { Text("使用云端进度") }
                                }
                            }) else null)
                    }
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        NativeReaderControls(chapter?.ref?.title.orEmpty(), pageLabel,
                            (offset.toFloat() / (chapter?.text?.length ?: 1).coerceAtLeast(1)).coerceIn(0f, 1f),
                            !busy && chapter != null, narrating, jumpHistory.locations.isNotEmpty(),
                            onReturn = { closePanel(); returnFromJump() }, onSeek = { ratio ->
                                val origin = location()
                                stopAloud()
                                view?.seek(((chapter?.text?.length ?: 0) * ratio).toInt())
                                jumpHistory = jumpHistory.remember(origin, location())
                            }, onPrevious = { stopAloud(); view?.turn(-1) }, onNext = { stopAloud(); view?.turn(1) },
                            onSpeak = ::speak, onPanel = { panel = it; applyWindow() }, compact = compactControls)
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp))
            }
            if (panel != null) ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { panel = null; applyWindow() }) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(panel.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { panel = null; applyWindow() }) { Icon(Icons.Rounded.Close, "关闭面板") }
                    }
                    when (panel) {
                        "排版" -> Column(Modifier.weight(1f)) { ReaderPreferencesPanel(style, ::update, nativeText = true, encoding = if (asset?.format == "txt") ({
                            Text("文本编码：${asset?.let { library.textEncoding(it.id) }}")
                            FlowRow { listOf("自动", "UTF-8", "GB18030", "Big5", "UTF-16LE", "UTF-16BE").forEach { encoding -> TextButton(onClick = {
                                navigate { val item = requireNotNull(asset); library.setTextEncoding(item.id, encoding); source.invalidate(); session = null; open(item, null) }
                            }) { Text(encoding) } } }
                        }) else null) }
                        "目录" -> asset?.let { item -> chapter?.let { content ->
                            NativeReaderContentsPanel(item, publication, content, source, novel, !busy) { ref -> closePanel(); openChapter(ref) }
                        } }
                        "搜索" -> asset?.let { item ->
                            NativeReaderSearchPanel(item, publication, source, !busy) { hit -> closePanel(); jumpTo(NativeReaderLocation(hit.assetId, hit.locator)) }
                        }
                        "书签" -> asset?.let {
                            val marks by library.dao.annotationsForAssets(bookmarkAssets).collectAsState(emptyList())
                            NativeReaderBookmarks(marks, !busy, onOpen = { mark ->
                                closePanel(); jumpTo(NativeReaderLocation(mark.assetId, mark.locatorJson))
                            }, onDelete = { mark -> lifecycleScope.launch {
                                deleteReaderBookmark(library, mark, snackbar)
                            } })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SnackbarHost(snackbar)
                }
            }
            excerpt?.let { (text, position) -> AlertDialog(onDismissRequest = { excerpt = null }, title = { Text("段落") }, text = { Text(text.take(1500)) },
                confirmButton = { TextButton(onClick = { chapter?.let { mark(it.locator(position), text.take(1000)) }; excerpt = null }) { Text("保存摘录") } },
                dismissButton = { TextButton(onClick = { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("正文", text)); message = "已复制正文"; excerpt = null }) { Text("复制") } }) }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("jump_history", jumpHistory.toJson()); chapter?.let { outState.putString("native_locator", it.locator(offset).toString()) }; asset?.let { outState.putString("active_asset", it.id) }; super.onSaveInstanceState(outState) }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (style.volumeToTurn && !menu && panel == null && excerpt == null && !narrating && keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) { if (event.repeatCount == 0) view?.turn(if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1); return true }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = if (style.volumeToTurn && !menu && panel == null && excerpt == null && !narrating && keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) true else super.onKeyUp(keyCode, event)
    override fun onStop() { stopAloud(); view?.pause(); if (publication != null) lifecycleScope.launch { ProgressWriter.flush(); NovelRepository.schedule(this@NativeTextReaderActivity) }; super.onStop() }
    override fun onDestroy() { view?.dispose(); view = null; aloud?.close(); super.onDestroy() }
}
