@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media.reading

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Build
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
    private val settingsBinding = ReaderSettingsBinding { key ->
        ReaderSettingsStore(getSharedPreferences("reader_book_" + key, MODE_PRIVATE),
            ReaderSettingsStore(getSharedPreferences("reader_preferences", MODE_PRIVATE)))
    }
    private var style by mutableStateOf(ReaderSettings())
    private var asset by mutableStateOf<LibraryAssetEntity?>(null)
    private var publication by mutableStateOf<NovelPublicationEntity?>(null)
    private var chapter by mutableStateOf<NativeTextChapter?>(null)
    private var state by mutableStateOf<NovelReadingStateEntity?>(null)
    private var menu by mutableStateOf(false)
    private var panel by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(true)
    private var acquiring = true
    private var navigationJob: Job? = null
    private var message by mutableStateOf<String?>(null)
    private var fatal by mutableStateOf<String?>(null)
    private var offset by mutableIntStateOf(0)
    private var pageLabel by mutableStateOf("")
    private var jumpHistory by mutableStateOf(NativeJumpHistory())
    private var view: NativeTextView? = null
    private var session: ProgressSession? = null
    private var observer: Job? = null
    private var sync: Job? = null
    private var prefetch: Job? = null
    private var cacheStatus by mutableStateOf<String?>(null)
    private var narrationOffset by mutableIntStateOf(0)
    private var narrationChapter: String? = null
    private var followNarration by mutableStateOf(true)
    private var narrationSeeking = false
    private var narrating by mutableStateOf(false)
    private var excerpt by mutableStateOf<Pair<String, Int>?>(null)
    private var readerSearch = NativeSearchSession()
    private var nativeSaved: JSONObject? = null
    private var pendingShow: Triple<NativeTextChapter, String, Int>? = null
    private data class PreparedChapter(val asset: LibraryAssetEntity, val publication: NovelPublicationEntity?, val content: NativeTextChapter)
    // Only the two verified neighbours; reuse their text and package metadata on a warm turn.
    private val warmedChapters = mutableMapOf<Int, PreparedChapter>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installReaderBackNavigation({ view?.selectedText?.isNotEmpty() == true || panel != null || excerpt != null || menu || acquiring && chapter != null }) {
            when { view?.clearSelection() == true -> Unit; excerpt != null -> excerpt = null; panel != null -> panel = null; menu -> menu = false; else -> navigationJob?.cancel() }
            applyWindow()
        }
        library = LibraryRepository(this); novel = NovelRepository(this, library); source = NativeTextSource(library, novel)
        store = ReaderSettingsStore(getSharedPreferences("reader_preferences", MODE_PRIVATE)); style = store.load()
        if (savedInstanceState?.getBoolean("contents_open") == true) {
            menu = true
            panel = "目录"
        }
        readerSearch = NativeSearchSession(savedInstanceState?.getInt("search_index") ?: 0, savedInstanceState?.getInt("search_pixels") ?: 0)
        readerSearch.executed = savedInstanceState?.getBoolean("search_executed") ?: false
        readerSearch.query.value = savedInstanceState?.getString("search_query").orEmpty()
        readerSearch.includeFuture.value = savedInstanceState?.getBoolean("search_future") ?: false
        jumpHistory = NativeJumpHistory.restore(savedInstanceState?.getString("jump_history"))
        nativeSaved = savedInstanceState?.getString("native_locator")?.let { runCatching { JSONObject(it) }.getOrNull() }
        lifecycleScope.launch {
            ReaderNarrationService.state.collectLatest { playing ->
                val id = playing.assetId ?: return@collectLatest
                val item = library.dao.asset(id) ?: return@collectLatest
                val pub = novel.dao.publication(id)
                if (id != asset?.id && (pub == null || pub.workId != publication?.workId || pub.scope != publication?.scope)) { narrating = false; return@collectLatest }
                ReaderNarrationService.activeSession?.takeIf { it.first == asset?.id }?.let { session = it.second }
                narrating = playing.playing
                if (playing.error != null) message = playing.error
                val locator = playing.locator?.let(::JSONObject) ?: return@collectLatest
                narrationChapter = locator.optJSONObject("native_text")?.optString("chapter_id")
                narrationOffset = locator.optJSONObject("native_text")?.optInt("offset") ?: 0
                if (!followNarration || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@collectLatest
                if (chapter?.ref?.id != narrationChapter && !busy) open(item, locator)
                if (chapter?.ref?.id == narrationChapter) {
                    narrationSeeking = true; view?.seek(narrationOffset); narrationSeeking = false
                    view?.highlightSentence(if (playing.playing) narrationOffset else null)
                }
            }
        }
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
            catch (e: Exception) { acquiring = false; busy = false; fatal = e.message ?: "正文打开失败" }
        }
    }
    private suspend fun open(item: LibraryAssetEntity, locator: JSONObject?, lastPage: Boolean = false, savePosition: Boolean = false) {
        val pub = novel.dao.publication(item.id)
        require(item.format == "txt" || pub != null) { "此阅读器支持 TXT 和 Novel 正文" }
        val content = source.chapter(item, pub, locator?.optString("href"),
            if (locator?.optJSONObject("native_text") == null) locator?.optJSONObject("locations")?.optDouble("progression", 0.0)?.takeIf { it.isFinite() } else null)
        openPrepared(PreparedChapter(item, pub, content), locator, lastPage, savePosition)
    }
    private suspend fun openPrepared(prepared: PreparedChapter, locator: JSONObject?, lastPage: Boolean = false, savePosition: Boolean = false) {
        val (item, pub, content) = prepared
        val restored = if (lastPage) content.text.length else content.restore(locator)
        val sameWork = pub != null && pub.scope == publication?.scope && pub.workId == publication?.workId
        if (pub != null && !sameWork) novel.beginReading(pub, locator ?: content.locator(restored))
        val newSession = ReaderNarrationService.activeSession?.takeIf { it.first == item.id }?.second ?: if (asset?.id != item.id || session == null) ProgressWriter.begin(library, item) else session
        val (bookStore, bookStyle) = settingsBinding.bind(readerBookSettingsKey(item.id, pub), style)
        store = bookStore; style = bookStyle
        asset = item; publication = pub; chapter = content; offset = restored; session = newSession
        if (content.rulesWarning.isNotBlank()) message = content.rulesWarning
        if (!narrating && narrationChapter != content.ref.id) {
            val saved = getSharedPreferences("reader_narration", MODE_PRIVATE).getString(item.id, null)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            narrationOffset = if (saved?.optJSONObject("native_text")?.optString("chapter_id") == content.ref.id) content.restore(saved) else restored
            narrationChapter = content.ref.id
        }
        if (pub != null && !sameWork) {
            observer?.cancel()
            observer = lifecycleScope.launch { novel.observe(pub.scope, pub.workId).collectLatest { state = it } }
        } else if (pub == null) { observer?.cancel(); state = null }
        saveAfterLayout = savePosition
        pendingShow = Triple(content, item.title, restored)
        acquiring = false
        view?.let { it.isEnabled = false; it.show(content, item.title, restored, style); pendingShow = null }
        prefetchFollowing(pub, content.ref.ordinal)
        val hints = getSharedPreferences("reader_hints", MODE_PRIVATE)
        if (!hints.getBoolean("gestures", false)) {
            message = "轻点中间打开菜单，左右两侧翻页；长按文字可选句。更多中可再次查看手势帮助。"
            hints.edit().putBoolean("gestures", true).apply()
        }
    }
    private fun prefetchFollowing(pub: NovelPublicationEntity?, ordinal: Int) {
        prefetch?.cancel(); warmedChapters.clear(); cacheStatus = null
        prefetch = lifecycleScope.launch {
            warmAdjacent(pub, ordinal)
            if (pub == null || LibraryResources.offlineOnly) return@launch
            repeat(3) { attempt ->
                try {
                    novel.cache.prefetch(novel.connection(pub.scope), pub, ordinal) { warmAdjacent(pub, ordinal) }
                    cacheStatus = "后续章节已提前缓存"; return@launch
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    cacheStatus = "后续章节暂未缓存，联网后可重试"
                    if (attempt < 2) delay(5_000L * (attempt + 1))
                }
            }
        }
    }
    private suspend fun warmAdjacent(pub: NovelPublicationEntity?, ordinal: Int) {
        try {
            val item = asset ?: return
            if (pub == null) {
                val local = source.local(item)
                for (direction in listOf(1, -1)) local.getOrNull(ordinal + direction)?.let { raw ->
                    val warmed = warmedChapters[direction]?.takeIf { it.content.ref.id == raw.ref.id }
                    if (warmed != null) view?.prepareChapter(warmed.content)
                    else {
                        val content = source.chapter(item, null, raw.ref.href)
                        warmedChapters[direction] = PreparedChapter(item, null, content)
                        view?.prepareChapter(content)
                    }
                }
            } else {
                val mapping = NovelPublicationMap(JSONObject(pub.mappingJson))
                for (direction in listOf(1, -1)) {
                    val row = mapping.chapters.firstOrNull { it.optInt("ordinal") == ordinal + direction }
                        ?: novel.cache.adjacent(pub.scope, pub.workId, pub.versionId, ordinal, direction)?.chapter
                        ?: novel.cachedContents(pub).firstOrNull { it.optInt("ordinal") == ordinal + direction }
                        ?: continue
                    val id = row.getString("id")
                    val warmed = warmedChapters[direction]?.takeIf { it.content.ref.id == id }
                    if (warmed != null) { view?.prepareChapter(warmed.content); continue }
                    val prepared = if (mapping.chapter(id) != null) NovelRepository.Prepared(item, null)
                        else novel.cachedChapter(pub.scope, pub.workId, pub.versionId, id) ?: continue
                    val window = novel.dao.publication(prepared.asset.id) ?: continue
                    val content = source.chapter(prepared.asset, window, "chapters/$id.xhtml")
                    warmedChapters[direction] = PreparedChapter(prepared.asset, window, content)
                    view?.prepareChapter(content)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Optional warmup never interrupts foreground reading. */ }
    }
    private fun positionChanged(value: Int, user: Boolean) {
        offset = value
        pageLabel = view?.let { "${it.pageIndex + 1} / ${it.document?.pages?.size ?: 1} 页" }.orEmpty()
        if (user && narrating && !narrationSeeking) followNarration = false
        if (!user || busy || narrating) return
        val content = chapter ?: return
        val locator = content.locator(value)
        val fraction = (content.ref.ordinal + value.toDouble() / content.text.length.coerceAtLeast(1)) / content.total.coerceAtLeast(1)
        session?.let { ProgressWriter.save(library, it, "text", locator, fraction, publication == null && value >= content.text.length - 1 && content.ref.ordinal == content.total - 1) }
        if (publication != null) {
            sync?.cancel(); sync = lifecycleScope.launch { delay(1000); ProgressWriter.flush(); NovelRepository.schedule(this@NativeTextReaderActivity) }
        }
    }
    private fun invalidateSource() { prefetch?.cancel(); warmedChapters.clear(); source.invalidate() }
    private fun navigate(flushProgress: Boolean = true, block: suspend () -> Unit) {
        if (busy) return
        stopAloud(); acquiring = true; busy = true; view?.isEnabled = false
        navigationJob = lifecycleScope.launch {
            try { if (flushProgress) ProgressWriter.flush(); block() }
            catch (e: CancellationException) { busy = false; view?.isEnabled = true; view?.boundaryResolved("已取消章节加载"); throw e }
            catch (e: Exception) { busy = false; view?.isEnabled = true; view?.boundaryResolved(); message = e.message ?: "章节获取失败，当前内容已保留" }
            finally { acquiring = false }
        }
    }
    private fun location(): NativeReaderLocation? = asset?.let { item -> chapter?.let { NativeReaderLocation(item.id, it.locator(offset).toString()) } }
    private fun closePanel() { panel = null; menu = false; applyWindow() }
    private fun openChapter(ref: NativeChapterRef, end: Boolean = false) = navigate(flushProgress = false) {
        val origin = location()
        loadChapter(ref, end)
        jumpHistory = jumpHistory.remember(origin, location())
        closePanel()
    }
    private fun jumpTo(target: NativeReaderLocation) = navigate {
        val origin = location()
        open(requireNotNull(library.dao.asset(target.assetId)) { "跳转对应内容已移除" }, JSONObject(target.locator), savePosition = true)
        jumpHistory = jumpHistory.remember(origin, location())
        closePanel()
    }
    private fun returnFromJump() = navigate {
        val target = jumpHistory.locations.lastOrNull()
        if (target == null) { busy = false; view?.isEnabled = true }
        else {
            open(requireNotNull(library.dao.asset(target.assetId)) { "原位置对应内容已移除" }, JSONObject(target.locator), savePosition = true)
            jumpHistory = NativeJumpHistory(jumpHistory.locations.dropLast(1))
            closePanel()
        }
    }
    private suspend fun loadChapter(ref: NativeChapterRef, end: Boolean = false) {
        warmedChapters.values.firstOrNull { it.content.ref.id == ref.id }?.let {
            openPrepared(it, JSONObject().put("href", ref.href), end, savePosition = true)
            return
        }
        val currentAsset = requireNotNull(asset)
        val pub = publication
        if (pub == null) open(currentAsset, JSONObject().put("href", ref.href), end, savePosition = true)
        else {
            val inPackage = NovelPublicationMap(JSONObject(pub.mappingJson)).chapter(ref.id) != null
            val prepared = if (inPackage) NovelRepository.Prepared(currentAsset, null)
                else novel.cachedChapter(pub.scope, pub.workId, pub.versionId, ref.id)
                    ?: novel.prepare(novel.connection(pub.scope), pub.workId, pub.versionId, ref.id)
            open(prepared.asset, JSONObject().put("href", ref.href), end, savePosition = true)
        }
    }
    private var saveAfterLayout = false
    private fun boundary(direction: Int) = navigate(flushProgress = false) {
        val current = requireNotNull(chapter)
        val next = adjacent(current.ref.ordinal, direction)
        if (next == null) {
            busy = false; view?.isEnabled = true
            if (direction > 0 && publication == null) view?.seek(current.text.length)
            view?.boundaryResolved(if (direction > 0) "已到本书末尾" else "已到本书开头")
        } else loadChapter(next, direction < 0)
    }
    private fun skipChapter(direction: Int) = navigate(flushProgress = false) {
        val origin = location()
        val next = adjacent(requireNotNull(chapter).ref.ordinal, direction)
        if (next == null) {
            busy = false; view?.isEnabled = true
            message = if (direction > 0) "已到本书末尾" else "已到本书开头"
        } else {
            loadChapter(next)
            jumpHistory = jumpHistory.remember(origin, location())
            closePanel()
        }
    }
    private suspend fun adjacent(ordinal: Int, direction: Int): NativeChapterRef? {
        warmedChapters[direction]?.takeIf { chapter?.ref?.ordinal == ordinal }?.let { return it.content.ref }
        val pub = publication ?: return source.local(requireNotNull(asset)).getOrNull(ordinal + direction)?.ref
        val mapping = NovelPublicationMap(JSONObject(pub.mappingJson))
        if (direction < 0 && ordinal == 0 || direction > 0 && ordinal >= mapping.json.getInt("total") - 1) return null
        mapping.chapters.map(::ref).firstOrNull { it.readable && it.ordinal == ordinal + direction }?.let { return it }
        val cached = novel.cachedContents(pub).map(::ref)
        cached.firstOrNull { it.readable && it.ordinal == ordinal + direction }?.let { return it }
        novel.cache.adjacent(pub.scope, pub.workId, pub.versionId, ordinal, direction)?.let { return it.chapter?.let(::ref) }
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
    private fun update(value: ReaderSettings) {
        if (value.leftHanded != style.leftHanded) message = if (value.leftHanded) "左手点击：左侧下一页，中央菜单，右侧上一页" else "常规点击：左侧上一页，中央菜单，右侧下一页"
        style = value.normalized(); store.save(style); view?.configure(style); applyWindow() }
    private fun applyWindow() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = style.paper != ReaderPaper.NIGHT; isAppearanceLightNavigationBars = style.paper != ReaderPaper.NIGHT
            // Keep the clock and battery visible without changing the viewport when opening menus.
            show(WindowInsetsCompat.Type.statusBars())
            if (style.fullScreen) hide(WindowInsetsCompat.Type.navigationBars()) else show(WindowInsetsCompat.Type.navigationBars())
        }
        window.setBackgroundDrawable(ColorDrawable(style.paper.background))
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (style.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = style.brightness }
    }
    private fun toggleMenu() {
        if (style.haptics) view?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        menu = !menu; applyWindow()
    }
    private var savingMark by mutableStateOf(false)
    private var undoMark: String? = null
    private fun mark(location: JSONObject, note: String) {
        if (savingMark) return
        val item = asset ?: return
        savingMark = true
        lifecycleScope.launch {
            try {
                val id = java.util.UUID.randomUUID().toString()
                library.dao.putAnnotation(com.shadow.media.database.ContentAnnotationEntity(id, item.id,
                    location.toString(), note, System.currentTimeMillis()))
                undoMark = id
                excerpt = null
                message = "已保存书签或摘录"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "尚未保存，请重试或复制文字" }
            finally { savingMark = false }
        }
    }
    private fun resolve(remote: Boolean) = navigate {
        val pub = requireNotNull(publication)
        if (remote) {
            val prepared = novel.useRemote(pub.scope, pub.workId)
            open(prepared.asset, prepared.locator?.let(::JSONObject))
        } else { novel.keepLocal(pub.scope, pub.workId); busy = false; view?.isEnabled = true }
    }
    private fun switchSource(version: String, chapterId: String?) = navigate {
        val pub = requireNotNull(publication)
        val origin = location()
        val prepared = novel.prepare(novel.connection(pub.scope), pub.workId, version, chapterId)
        open(prepared.asset, prepared.locator?.let(::JSONObject), savePosition = true)
        jumpHistory = jumpHistory.remember(origin, location())
        closePanel()
    }
    private fun stopAloud() {
        if (narrating) ReaderNarrationService.command(this, ReaderNarrationService.PAUSE)
        narrating = false
    }
    private fun speak(fromHere: Boolean = false) {
        if (narrating && !fromHere) { stopAloud(); return }
        val current = chapter ?: return
        val item = asset ?: return
        followNarration = true
        lifecycleScope.launch {
            val saved = if (fromHere) null else getSharedPreferences("reader_narration", MODE_PRIVATE)
                .getString("resume:" + narrationBookKey(item.id, publication), null)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val legacy = if (fromHere) null else getSharedPreferences("reader_narration", MODE_PRIVATE).getString(item.id, null)
            val resumed = saved?.optString("asset")?.let { library.dao.asset(it) }
            val resumedPub = resumed?.let { novel.dao.publication(it.id) }
            val sameBook = resumed != null && narrationBookKey(resumed.id, resumedPub) == narrationBookKey(item.id, publication)
            val target = if (sameBook) resumed!!.id else item.id
            val locator = if (sameBook) saved!!.optJSONObject("locator")?.toString() else null
            ReaderNarrationService.command(this@NativeTextReaderActivity, ReaderNarrationService.PLAY, target,
                locator ?: legacy ?: current.locator(offset).toString())
        }
    }
    private fun followSpeech() {
        val playing = ReaderNarrationService.state.value
        val id = playing.assetId ?: return
        val locator = playing.locator?.let(::JSONObject) ?: return
        followNarration = true
        lifecycleScope.launch {
            val item = library.dao.asset(id) ?: return@launch
            open(item, locator)
            closePanel()
        }
    }

    @Composable private fun Chrome() {
        MaterialTheme(colorScheme = readerColorScheme(style.paper)) {
            val writerError by ProgressWriter.error.collectAsState()
            val snackbar = remember { SnackbarHostState() }
            val sourceState = remember(publication?.scope, publication?.workId) { NovelReaderSourceState() }
            val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) lifecycleScope.launch {
                    try { val (id, name) = ReaderFonts(this@NativeTextReaderActivity).import(uri); update(style.copy(customFontId = id, customFontName = name)); message = "已应用字体：$name" }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message ?: "字体导入失败，原设置已保留" }
                }
            }
            LaunchedEffect(message) {
                val text = message ?: return@LaunchedEffect
                val undo = undoMark
                undoMark = null
                val result = snackbar.showSnackbar(text, actionLabel = if (undo != null) "撤销" else null)
                if (result == SnackbarResult.ActionPerformed && undo != null) library.dao.removeAnnotation(undo)
                if (message == text) message = null
            }
            var bookmarkAssets by remember { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(asset?.id) {
                val pub = publication
                bookmarkAssets = if (pub == null) listOfNotNull(asset?.id) else novel.dao.publications(pub.scope)
                    .filter { it.workId == pub.workId && it.versionId == pub.versionId }.map { it.assetId }
            }
            LaunchedEffect(publication?.scope) {
                val scope = publication?.scope ?: return@LaunchedEffect
                val previous = novel.textRules(scope).optInt("revision")
                try {
                    val fresh = novel.textRules(scope, refresh = true)
                    if (fresh.optInt("revision") != previous) {
                        snapshotFlow { busy }.first { !it }
                        if (publication?.scope == scope) {
                            val locator = chapter?.locator(offset)
                            invalidateSource()
                            navigate { asset?.let { open(it, locator) } }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Cached rules remain available when offline or on an older service. */ }
            }
            BoxWithConstraints(Modifier.fillMaxSize().background(Color(style.paper.background))
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
                ReaderPaperBackground(style, Modifier.matchParentSize())
                val density = LocalDensity.current
                val paperTopInset = WindowInsets.statusBars.getTop(density)
                val paperBottomInset = if (style.fullScreen) 0 else WindowInsets.navigationBars.getBottom(density)
                val compactControls = maxHeight < 480.dp
                AndroidView(factory = { context -> NativeTextView(context).also { reader ->
                    view = reader
                    reader.onMenu = ::toggleMenu; reader.onBoundary = ::boundary; reader.onPosition = ::positionChanged
                    reader.onSettings = ::update
                    reader.onReadFromSelection = { point ->
                        chapter?.let { ReaderNarrationService.command(this@NativeTextReaderActivity, ReaderNarrationService.PLAY,
                            asset?.id, it.locator(point).toString()) }
                    }
                    reader.onExcerpt = { text, position -> excerpt = text to position }
                    reader.onBrightnessPreview = { brightness -> window.attributes = window.attributes.apply { screenBrightness = brightness ?: style.brightness } }
                    reader.onError = { fatal = it; busy = false }
                    reader.onReady = ready@{
                        if (acquiring) return@ready
                        busy = false; reader.isEnabled = true
                        if (saveAfterLayout) { saveAfterLayout = false; positionChanged(reader.readingOffset, true) }
                    }
                    pendingShow?.let { (content, title, restored) -> reader.show(content, title, restored, style); pendingShow = null }
                } }, update = {
                    it.gesturesEnabled = !busy && !menu && panel == null && excerpt == null
                    it.setPaperInsets(paperTopInset, paperBottomInset)
                }, modifier = Modifier.fillMaxSize().statusBarsPadding()
                    .then(if (style.fullScreen) Modifier else Modifier.navigationBarsPadding()))
                var showLoading by remember { mutableStateOf(false) }
                LaunchedEffect(busy) { showLoading = false; if (busy) { delay(220); showLoading = true } }
                if (showLoading) LinearProgressIndicator(Modifier.fillMaxWidth().statusBarsPadding().align(Alignment.TopCenter))
                fatal?.let { text -> Column(Modifier.align(Alignment.Center).padding(28.dp)) { Text(text); TextButton(onClick = { finish() }) { Text("返回书架") } } }
                if (menu) {
                    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menu = false; applyWindow() })
                }
                    AnimatedVisibility(menu, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { -it / 3 }, exit = fadeOut(tween(120))) {
                        val status = when {
                            state?.conflictJson?.isNotBlank() == true -> "进度有冲突，请选择保留的位置"
                            state?.message?.isNotBlank() == true -> state?.message
                            state?.pending == true -> "进度已保存在本机，等待同步"
                            publication != null && state != null -> listOfNotNull("进度已同步", cacheStatus).joinToString(" · ")
                            else -> "进度保存在本机"
                        }
                        NativeReaderHeader(asset?.title.orEmpty(), chapter?.ref?.title.orEmpty(), writerError ?: status, menu && !busy,
                            onBack = { finish() }, onClose = { menu = false; applyWindow() },
                            onBookmark = { chapter?.let { mark(it.locator(offset), it.ref.title) } },
                            night = style.paper == ReaderPaper.NIGHT, onToggleNight = { update(style.toggleNight()) },
                            sources = publication != null,
                            onPanel = { if (it == "书源") sourceState.selectedVersion = null; panel = it; applyWindow() },
                            conflict = if (state?.conflictJson?.isNotBlank() == true) ({
                                FlowRow(Modifier.padding(horizontal = 12.dp)) {
                                    TextButton(onClick = { resolve(false) }, enabled = !busy) { Text("保留本机进度") }
                                    TextButton(onClick = { resolve(true) }, enabled = !busy) { Text("使用云端进度") }
                                }
                            }) else null)
                    }
                    AnimatedVisibility(menu, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 }, exit = fadeOut(tween(120))) {
                        NativeReaderControls(chapter?.ref?.title.orEmpty(), pageLabel,
                            (offset.toFloat() / (chapter?.text?.length ?: 1).coerceAtLeast(1)).coerceIn(0f, 1f),
                            menu && !busy && chapter != null, narrating, jumpHistory.locations.isNotEmpty(),
                            onReturn = ::returnFromJump, onSeek = { ratio ->
                                val origin = location()
                                stopAloud()
                                view?.seek(((chapter?.text?.length ?: 0) * ratio).toInt())
                                jumpHistory = jumpHistory.remember(origin, location())
                            }, onPrevious = { skipChapter(-1) }, onNext = { skipChapter(1) },
                            onSpeak = { speak() }, onPanel = { if (it == "书源") sourceState.selectedVersion = null; panel = it; applyWindow() }, compact = compactControls, sources = publication != null)
                    }
                if (panel == null) SnackbarHost(snackbar, Modifier.align(if (menu) Alignment.Center else Alignment.BottomCenter)
                    .navigationBarsPadding().padding(12.dp))
            }
            val browsingContents = panel == "目录" || (panel == "书源" && sourceState.selectedVersion != null)
            if (panel != null) ModalBottomSheet(
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                sheetGesturesEnabled = !browsingContents,
                dragHandle = if (browsingContents) null else ({ BottomSheetDefaults.DragHandle() }),
                onDismissRequest = { panel = null; applyWindow() },
            ) {
                Box {
                    Column(Modifier.fillMaxWidth().fillMaxHeight(if (panel == "排版") .58f else .92f).padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(panel.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            IconButton(onClick = { panel = null; applyWindow() }) { Icon(Icons.Rounded.Close, "关闭面板") }
                        }
                        when (panel) {
                            "听读" -> NarrationControls(onFromHere = { speak(true) }, onFollow = ::followSpeech, onContinue = { speak() })
                            "排版" -> Column(Modifier.weight(1f)) {
                                Row {
                                    TextButton(onClick = { store.followDefaults(); style = store.load(); view?.configure(style); applyWindow() }) { Text("跟随默认") }
                                    TextButton(onClick = { store.makeDefault(style) }) { Text("设为默认排版") }
                                }
                                ReaderPreferencesPanel(style, ::update, nativeText = true,
                                importFont = { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/vnd.ms-opentype", "application/octet-stream")) }, encoding = if (asset?.format == "txt") ({
                                Text("文本编码：${asset?.let { library.textEncoding(it.id) }}")
                                FlowRow { listOf("自动", "UTF-8", "GB18030", "Big5", "UTF-16LE", "UTF-16BE").forEach { encoding -> TextButton(onClick = {
                                    navigate { val item = requireNotNull(asset); val locator = chapter?.locator(offset); library.setTextEncoding(item.id, encoding); invalidateSource(); session = null; open(item, locator) }
                                }) { Text(encoding) } } }
                            }) else null) }
                            "更多" -> {
                                TextButton(onClick = { panel = "搜索" }) { Text("搜正文") }
                                TextButton(onClick = { panel = "书签" }) { Text("书签与摘录") }
                                TextButton(onClick = { message = "轻点中间打开菜单，左右两侧翻页。长按正文选字，拖动两端手柄调整范围。" }) { Text("手势帮助") }
                            }
                            "目录" -> asset?.let { item -> chapter?.let { content ->
                                Row {
                                    TextButton(onClick = { panel = "目录" }) { Text("目录") }
                                    TextButton(onClick = { panel = "书签" }) { Text("书签与摘录") }
                                }
                                NativeReaderContentsPanel(item, publication, content, source, novel, !busy, ::openChapter)
                            } }
                            "书源" -> publication?.let { pub -> chapter?.let { content ->
                                NovelReaderSourcesPanel(novel, pub, asset?.title.orEmpty(), content.ref.title, !busy, sourceState, ::switchSource)
                            } }
                            "替换" -> NovelTextRulesPanel(novel, publication?.scope,
                                sample = view?.selectedText?.takeIf(String::isNotBlank) ?: chapter?.let { it.originalText ?: it.text }.orEmpty(),
                                onSaved = {
                                    val locator = chapter?.locator(offset)
                                    invalidateSource(); panel = null
                                    navigate { asset?.let { open(it, locator) } }
                                })
                            "搜索" -> asset?.let { item ->
                                NativeReaderSearchPanel(item, publication, source, !busy, chapter?.let { it.ref.ordinal to offset }, session = readerSearch) { hit -> jumpTo(NativeReaderLocation(hit.assetId, hit.locator)) }
                            }
                            "书签" -> asset?.let {
                                TextButton(onClick = { panel = "目录" }) { Text("返回目录") }
                                val marks by library.dao.annotationsForAssets(bookmarkAssets).collectAsState(emptyList())
                                NativeReaderBookmarks(marks, !busy, onOpen = { mark ->
                                    jumpTo(NativeReaderLocation(mark.assetId, mark.locatorJson))
                                }, onDelete = { mark -> lifecycleScope.launch {
                                    deleteReaderBookmark(library, mark, snackbar)
                                } })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (!browsingContents) SnackbarHost(snackbar)
                    }
                    // Transient feedback must not resize the chapter list under the reader’s finger.
                    if (browsingContents) SnackbarHost(snackbar, Modifier.align(Alignment.TopStart).padding(start = 12.dp, end = 72.dp))
                    if (acquiring) ReaderChapterLoading(onCancel = { navigationJob?.cancel() },
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(12.dp))
                }
            }
            excerpt?.let { (text, position) -> AlertDialog(onDismissRequest = { excerpt = null }, title = { Text("摘录") }, text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(if (text.length > 1000) "最多保存 1000 字，请返回缩短选区。\n\n$text" else text) } },
                confirmButton = { TextButton(enabled = !savingMark && text.length <= 1000, onClick = { chapter?.let { mark(it.locator(position).put("selection_end", position + text.length), text) } }) { Text("保存摘录") } },
                dismissButton = { TextButton(onClick = { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("正文", text)); message = "已复制正文"; excerpt = null }) { Text("复制") } }) }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("contents_open", panel == "目录")
        outState.putInt("search_index", readerSearch.list.firstVisibleItemIndex)
        outState.putInt("search_pixels", readerSearch.list.firstVisibleItemScrollOffset)
        outState.putBoolean("search_executed", readerSearch.executed)
        outState.putString("search_query", readerSearch.query.value); outState.putBoolean("search_future", readerSearch.includeFuture.value); outState.putString("jump_history", jumpHistory.toJson()); chapter?.let { outState.putString("native_locator", it.locator(offset).toString()) }; asset?.let { outState.putString("active_asset", it.id) }; super.onSaveInstanceState(outState) }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (style.volumeToTurn && !menu && panel == null && excerpt == null && !narrating && keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) { if (event.repeatCount == 0) view?.turn(if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1); return true }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = if (style.volumeToTurn && !menu && panel == null && excerpt == null && !narrating && keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) true else super.onKeyUp(keyCode, event)
    override fun onStart() { super.onStart(); chapter?.let { prefetchFollowing(publication, it.ref.ordinal) } }
    override fun onStop() { prefetch?.cancel(); warmedChapters.clear(); view?.pause(); if (publication != null) lifecycleScope.launch { ProgressWriter.flush(); NovelRepository.schedule(this@NativeTextReaderActivity) }; super.onStop() }
    override fun onDestroy() { view?.dispose(); view = null; super.onDestroy() }

}
