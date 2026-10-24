@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shadow.media.reading

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.*
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.publication.*
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse
import com.shadow.media.database.LibraryAssetEntity
import com.shadow.media.database.NovelPublicationEntity
import com.shadow.media.database.NovelReadingStateEntity
import com.shadow.media.library.LibraryRepository
import com.shadow.media.library.NovelLocatorTracker
import com.shadow.media.library.NovelPublicationMap
import com.shadow.media.library.NovelRepository
import com.shadow.media.library.ProgressWriter
import com.shadow.media.library.novelObjects

class ReaderActivity : FragmentActivity() {
    private lateinit var library: LibraryRepository
    private var publication: Publication? = null
    private var navigator by mutableStateOf<EpubNavigatorFragment?>(null)
    private var asset by mutableStateOf<LibraryAssetEntity?>(null)
    private var error by mutableStateOf<String?>(null)
    private var ready by mutableStateOf(false)
    private var locator by mutableStateOf<Locator?>(null)
    private var readerSettings by mutableStateOf(ReaderSettings())
    private val prefs get() = readerSettings.epubPreferences()
    private lateinit var settingsStore: ReaderSettingsStore
    private var surface: ReaderSurface? = null
    private var chromeVisible by mutableStateOf(false)
    private var panel by mutableStateOf<String?>(null)
    private var turning: Job? = null
    private var paperJob: Job? = null
    private var styledResource: Triple<String, ReaderPaper, Float>? = null
    private var gestureMessage by mutableStateOf<String?>(null)
    private var gestureMessageJob: Job? = null
    private var positions by mutableStateOf<List<Locator>>(emptyList())
    private var initial: Locator? = null
    private var aloud: ReadAloud? = null
    private var narrating by mutableStateOf(false)
    private var searchJob: Job? = null
    private var progressJob: Job? = null
    private var progressSession: com.shadow.media.library.ProgressSession? = null
    private lateinit var novel: NovelRepository
    private var novelPublication by mutableStateOf<NovelPublicationEntity?>(null)
    private var novelState by mutableStateOf<NovelReadingStateEntity?>(null)
    private var novelBusy by mutableStateOf(false)
    private var novelMessage by mutableStateOf<String?>(null)
    private var novelContents by mutableStateOf<List<JSONObject>>(emptyList())
    private var novelContentsOffset by mutableStateOf(0)
    private var novelContentsTotal by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The factory requires an asynchronously opened publication. Restore our versioned Locator,
        // not Android's eagerly reconstructed fragment with an unavailable publication instance.
        super.onCreate(null)
        installReaderBackNavigation({ panel != null || chromeVisible }) {
            if (panel != null) panel = null else chromeVisible = false
            applyReaderWindow()
        }
        library = LibraryRepository(this)
        novel = NovelRepository(this, library)
        val id = intent.getStringExtra("assetId")
        val saved = savedInstanceState?.getString("locator")
        val savedRevision = savedInstanceState?.getString("revision")
        settingsStore = ReaderSettingsStore(getSharedPreferences("reader_preferences", MODE_PRIVATE))
        readerSettings = settingsStore.load()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyReaderWindow()
        lifecycleScope.launch {
            try {
                val item = requireNotNull(id?.let { library.dao.asset(it) }) { "图书已移除" }
                if (item.format == "txt" || item.providerId.startsWith("novel:")) {
                    startActivity(android.content.Intent(this@ReaderActivity, NativeTextReaderActivity::class.java)
                        .putExtra("assetId", item.id).putExtra("novelLocator", intent.getStringExtra("novelLocator")))
                    finish()
                    return@launch
                }
                asset = item
                novelPublication = novel.dao.publication(item.id)
                novelPublication?.let { publication ->
                    lifecycleScope.launch { novel.observe(publication.scope, publication.workId).collectLatest { state -> novelState = state } }
                }
                require(item.format in setOf("epub", "txt")) { "此阅读器支持 EPUB 和 TXT" }
                progressSession = ProgressWriter.begin(library, item)
                val file = readerOpeningStep(ReaderOpenStage.LOCAL_COPY) { library.publicationFile(item) }
                readerOpeningStep(ReaderOpenStage.POSITION) {
                    initial = (saved.takeIf { savedRevision == item.revision } ?: intent.getStringExtra("novelLocator") ?: library.dao.progress(item.id)?.locatorJson)?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
                    if (novelPublication != null && initial == null) {
                        val map = NovelPublicationMap(JSONObject(novelPublication!!.mappingJson))
                        novel.dao.state(NovelRepository.stateId(novelPublication!!.scope, map.workId))?.localPosition?.takeIf { it.isNotBlank() }?.let { position ->
                            initial = map.locator(JSONObject(position))?.let { Locator.fromJSON(it) }
                        }
                    }
                }
                val engine: ReadingEngineAdapter = ReadiumEngineAdapter(this@ReaderActivity)
                val pub = readerOpeningStep(ReaderOpenStage.PUBLICATION) { engine.open(file) }
                publication = pub
                val preferences = readerOpeningStep(ReaderOpenStage.PREFERENCES) { prefs }
                supportFragmentManager.fragmentFactory = readerOpeningStep(ReaderOpenStage.NAVIGATOR) { engine.navigator(pub, initial, preferences, selectionActions()) }
                ready = true
                lifecycleScope.launch {
                    try { positions = pub.positions() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { positions = emptyList() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.e("ShadowReader", "Reader opening failed${(e as? ReaderOpeningException)?.let { " at ${it.stage}" }.orEmpty()}", e)
                error = e.message?.takeUnless { it in setOf("Failed requirement.", "Check failed.") } ?: "图书打开失败，请返回书架后重试"
            }
        }
        setContent { ReaderChrome() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        asset?.let { outState.putString("revision", it.revision) }
        locator?.let { outState.putString("locator", it.toJSON().toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun selectionActions() = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
            menu.add(android.view.Menu.NONE, R.id.reader_save_excerpt, android.view.Menu.NONE, "保存摘录")
            return true
        }
        override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
        override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
        override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
            if (item.itemId != R.id.reader_save_excerpt) return false
            lifecycleScope.launch {
                try {
                    val selection = navigator?.currentSelection()?.locator
                    if (selection != null) asset?.let {
                        library.bookmark(it.id, selection.toJSON().toString(), selection.text.highlight ?: "摘录")
                        showGestureMessage("已保存摘录")
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { showGestureMessage("摘录保存失败，请重试") }
                finally { mode.finish() }
            }
            return true
        }
    }

    private fun attach() {
        if (navigator != null) return
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        supportFragmentManager.beginTransaction().replace(R.id.epub_reader, fragment).commitNow()
        navigator = fragment
        fragment.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                if (panel != null) return false
                val target = readerTap(event.point.x / fragment.publicationView.width.coerceAtLeast(1),
                    readerSettings.tapToTurn, fragment.settings.value.scroll,
                    fragment.settings.value.readingProgression == ReadingProgression.RTL)
                if (target == 0 || chromeVisible) chromeVisible = !chromeVisible
                else turnPage(target > 0)
                applyReaderWindow()
                return true
            }
        })
        applyPaper()
        collectProgress(fragment)
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

    private fun collectProgress(fragment: EpubNavigatorFragment) {
        progressJob = lifecycleScope.launch {
            val tracker = novelPublication?.let { NovelLocatorTracker(NovelPublicationMap(JSONObject(it.mappingJson))) }
            fragment.currentLocator.collectLatest { current ->
                locator = current
                applyPaper()
                if (tracker != null && !tracker.moved(current.toJSON())) return@collectLatest
                progressSession?.let { ProgressWriter.save(library, it, "text", current.toJSON(), current.locations.totalProgression,
                    completed = novelPublication == null && (current.locations.totalProgression ?: 0.0) >= 0.999) }
                if (novelPublication != null) { delay(1200); ProgressWriter.flush(); NovelRepository.schedule(this@ReaderActivity) }
            }
        }
    }

    private fun openNovelChapter(chapter: String) {
        val publication = novelPublication ?: return
        if (novelBusy) return
        novelBusy = true; novelMessage = null
        lifecycleScope.launch {
            progressJob?.cancelAndJoin(); ProgressWriter.flush()
            try {
                val prepared = novel.cachedChapter(publication.scope, publication.workId, publication.versionId, chapter)
                    ?: novel.prepare(novel.connection(publication.scope), publication.workId, publication.versionId, chapter)
                startActivity(android.content.Intent(this@ReaderActivity, ReaderActivity::class.java).putExtra("assetId", prepared.asset.id).putExtra("novelLocator", prepared.locator))
                finish()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { novelMessage = e.message ?: "章节获取失败，当前内容已保留"; navigator?.let(::collectProgress) }
            finally { novelBusy = false }
        }
    }

    private fun loadNovelContents(next: Boolean = false) {
        val publication = novelPublication ?: return
        if (novelBusy) return
        novelBusy = true
        lifecycleScope.launch {
            try {
                val offset = if (next) novelContentsOffset + 100 else 0
                val page = novel.get(novel.connection(publication.scope), listOf("works", publication.workId, "contents"), listOf("version_id" to publication.versionId, "offset" to offset.toString(), "limit" to "100"))
                novelContents = (if (next) novelContents else emptyList()) + page.novelObjects("items")
                novelContentsOffset = offset; novelContentsTotal = page.optInt("total")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!next) {
                    novelContents = novel.cachedContents(publication)
                    novelContentsOffset = 0; novelContentsTotal = novelContents.size
                    novelMessage = "完整目录暂不可用，显示本机已下载章节"
                } else novelMessage = e.message ?: "目录获取失败"
            }
            finally { novelBusy = false }
        }
    }

    private fun updateSettings(next: ReaderSettings) {
        val old = readerSettings
        readerSettings = next.normalized()
        settingsStore.save(readerSettings)
        if (old.epubPreferences() != prefs) {
            surface?.cancelTurn()
            navigator?.submitPreferences(prefs)
        }
        applyReaderWindow()
        if (old.paper != readerSettings.paper || old.paperTexture != readerSettings.paperTexture) applyPaper()
    }

    private fun applyPaper() {
        val fragment = navigator ?: return
        val appearance = readerSettings
        val resource = Triple(fragment.currentLocator.value.href.toString(), appearance.paper, appearance.paperTexture)
        if (styledResource == resource) return
        paperJob?.cancel()
        paperJob = lifecycleScope.launch {
            try {
                val data = if (appearance.paper == ReaderPaper.PARCHMENT) withContext(Dispatchers.IO) {
                    ReaderPaperTexture.dataUri(applicationContext)
                } else ""
                fragment.evaluateJavascript(readerPaperScript(appearance, data))
                styledResource = resource
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Solid paper color remains available during resource replacement. */ }
        }
    }

    private fun applyReaderWindow() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = readerSettings.paper != ReaderPaper.NIGHT
            isAppearanceLightNavigationBars = readerSettings.paper != ReaderPaper.NIGHT
            if (readerSettings.fullScreen) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
        if (readerSettings.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = readerSettings.brightness }
    }

    private fun showGestureMessage(value: String) {
        gestureMessageJob?.cancel(); gestureMessage = value
        gestureMessageJob = lifecycleScope.launch { delay(1200); gestureMessage = null }
    }

    private fun turnPage(forward: Boolean) {
        val fragment = navigator ?: return
        if (turning?.isActive == true || novelBusy) return
        val view = surface
        if (view != null && !view.prepareTurn(forward)) return
        turning = lifecycleScope.launch {
            try {
                val before = fragment.currentLocator.value
                val accepted = if (forward) fragment.goForward(animated = false) else fragment.goBackward(animated = false)
                val moved = accepted && withTimeoutOrNull(1800) {
                    fragment.currentLocator.first { it != before }
                    // Wait for the new document to be loaded and a browser frame to be painted.
                    fragment.evaluateJavascript("document.readyState")
                    delay(48)
                    true
                } == true
                view?.finishTurn(moved)
                if (!accepted) showGestureMessage(if (forward) "已到当前内容末尾，可从目录选择后续章节" else "已到当前内容开头")
            } catch (e: CancellationException) { view?.cancelTurn(); throw e }
            catch (_: Exception) { view?.cancelTurn() }
        }
    }

    private fun handlesVolumeKey(keyCode: Int) = readerSettings.volumeToTurn && !chromeVisible && panel == null &&
        !narrating && navigator != null && keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handlesVolumeKey(keyCode)) {
            if (event.repeatCount == 0) turnPage(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (handlesVolumeKey(keyCode)) true else super.onKeyUp(keyCode, event)

    private fun jumpTo(location: Locator) {
        turning?.cancel(); surface?.cancelTurn(); navigator?.go(location, animated = false)
    }

    private fun jumpTo(link: Link) {
        turning?.cancel(); surface?.cancelTurn(); navigator?.go(link, animated = false)
    }

    @Composable private fun ReaderChrome() {
        val night = readerSettings.paper == ReaderPaper.NIGHT
        MaterialTheme(colorScheme = if (night) darkColorScheme(primary = Color(0xFF87B8F9)) else lightColorScheme(
            background = Color(readerSettings.paper.background), surface = Color(readerSettings.paper.background), primary = Color(0xFF755F39), onSurface = Color(readerSettings.paper.foreground))) {
            var message by remember { mutableStateOf<String?>(null) }
            var searchQuery by remember { mutableStateOf("") }
            var matches by remember { mutableStateOf<List<Locator>>(emptyList()) }
            var searching by remember { mutableStateOf(false) }
            var searchIterator by remember { mutableStateOf<SearchIterator?>(null) }
            var hasMore by remember { mutableStateOf(false) }
            var encodingChange by remember { mutableStateOf<String?>(null) }
            var editingNote by remember { mutableStateOf<com.shadow.media.database.ContentAnnotationEntity?>(null) }
            var noteText by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            val accessibility = remember { getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager }
            var touchExploration by remember { mutableStateOf(accessibility.isTouchExplorationEnabled) }
            DisposableEffect(accessibility) {
                val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
                accessibility.addTouchExplorationStateChangeListener(listener)
                onDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
            }
            LaunchedEffect(touchExploration) { if (touchExploration) chromeVisible = true }
            val menuMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
            val partialNovel = remember(novelPublication) { novelPublication?.let { !NovelPublicationMap(JSONObject(it.mappingJson)).complete } == true }
            val progressLabel = if (partialNovel) "当前下载内容" else "全书进度"
            DisposableEffect(searchIterator) { val iterator = searchIterator; onDispose { iterator?.close() } }
            LaunchedEffect(chromeVisible, panel, readerSettings.fullScreen) { applyReaderWindow() }
            Box(Modifier.fillMaxSize().background(Color(readerSettings.paper.background))) {
                Column(Modifier.fillMaxSize().padding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout).asPaddingValues())) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(locator?.title ?: asset?.title ?: "阅读", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        Text("${if (partialNovel) "本段 " else ""}${((locator?.locations?.totalProgression ?: 0.0) * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                    Box(Modifier.weight(1f)) {
                        when {
                            error != null -> Column(Modifier.padding(24.dp)) {
                                Text(error!!)
                                TextButton(onClick = { finish() }) { Text("返回书架") }
                            }
                            ready -> AndroidView(factory = { context ->
                                ReaderSurface(context).also { view ->
                                    surface = view
                                    view.onTurn = ::turnPage
                                    view.onMenu = { chromeVisible = !chromeVisible; applyReaderWindow() }
                                    view.onBrightness = { updateSettings(readerSettings.copy(brightness = it)); showGestureMessage("亮度 ${(it * 100).toInt()}%") }
                                    view.currentBrightness = {
                                        if (readerSettings.brightness >= 0) readerSettings.brightness else
                                            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
                                    }
                                    view.onFontScale = { scale ->
                                        updateSettings(readerSettings.copy(fontSize = readerSettings.fontSize * scale))
                                        showGestureMessage("字号 ${readerSettings.fontSize.toInt()}%")
                                    }
                                    view.post { if (!isFinishing && !supportFragmentManager.isStateSaved) attach() }
                                }
                            }, update = { view ->
                                view.settings = readerSettings
                                view.gesturesEnabled = !chromeVisible && panel == null && !novelBusy
                                view.rtl = navigator?.settings?.value?.readingProgression == ReadingProgression.RTL
                            }, modifier = Modifier.fillMaxSize())
                            else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        }
                    }
                    if (touchExploration) TextButton(onClick = { chromeVisible = !chromeVisible }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("阅读菜单") }
                    else Text("点击中央打开菜单 · 长按文字可选中", Modifier.align(Alignment.CenterHorizontally).padding(vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                }
                gestureMessage?.let { Text(it, Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium).padding(16.dp)) }
                if (chromeVisible) Column(Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text(asset?.title ?: "阅读", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回书架") } },
                        actions = {
                            IconButton(onClick = { panel = "搜索" }) { Icon(Icons.Rounded.Search, "全文搜索") }
                            IconButton(onClick = { panel = "目录"; if (novelPublication != null) loadNovelContents() }) { Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, "目录") }
                            IconButton(onClick = { panel = "书签" }) { Icon(Icons.Rounded.Bookmarks, "书签") }
                            IconButton(onClick = { updateSettings(readerSettings.copy(fullScreen = !readerSettings.fullScreen)); chromeVisible = false }) {
                                Icon(if (readerSettings.fullScreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "切换全屏")
                            }
                            IconButton(onClick = { panel = "排版" }) { Icon(Icons.Rounded.TextFields, "排版与阅读设置") }
                        }
                    )
                    Box(Modifier.weight(1f).fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClickLabel = "收起阅读菜单") { chromeVisible = false; applyReaderWindow() })
                    Surface(tonalElevation = 6.dp, shadowElevation = 12.dp) {
                        Column(Modifier.heightIn(max = menuMaxHeight).verticalScroll(rememberScrollState())) {
                            novelPublication?.let { publication ->
                                val map = remember(publication.mappingJson) { NovelPublicationMap(JSONObject(publication.mappingJson)) }
                                val state = novelState
                                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                    Text(when {
                                        !state?.conflictJson.isNullOrBlank() -> "进度冲突 · 本机位置已保留"
                                        !state?.message.isNullOrBlank() -> state!!.message
                                        state?.pending == true -> "本机已保存 · 等待同步"
                                        else -> "Novel · 已下载 ${map.chapters.size} 章${if (map.complete) "（完整目录包）" else "（章节窗口）"}"
                                    }, style = MaterialTheme.typography.bodySmall)
                                    novelMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                                    if (novelBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    if (!state?.conflictJson.isNullOrBlank()) {
                                        val remote = runCatching { JSONObject(state!!.conflictJson).optJSONObject("current") }.getOrNull()
                                        Text("另一设备：${map.chapter(remote?.optString("chapter_id").orEmpty())?.optString("title") ?: "其他章节"} · ${((remote?.optDouble("ratio", 0.0) ?: 0.0) * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                        Row {
                                            TextButton(enabled = !novelBusy, onClick = { lifecycleScope.launch {
                                                try { novel.keepLocal(publication.scope, publication.workId) }
                                                catch (e: Exception) { novelMessage = e.message }
                                            } }) { Text("继续本机位置") }
                                            TextButton(enabled = !novelBusy && remote != null, onClick = {
                                                novelBusy = true
                                                lifecycleScope.launch {
                                                    progressJob?.cancelAndJoin(); ProgressWriter.flush()
                                                    try {
                                                        val prepared = novel.useRemote(publication.scope, publication.workId)
                                                        startActivity(android.content.Intent(this@ReaderActivity, ReaderActivity::class.java).putExtra("assetId", prepared.asset.id).putExtra("novelLocator", prepared.locator)); finish()
                                                    } catch (e: CancellationException) { throw e }
                                                    catch (e: Exception) { novelMessage = e.message; navigator?.let(::collectProgress) }
                                                    finally { novelBusy = false }
                                                }
                                            }) { Text("采用另一设备位置") }
                                        }
                                    } else if (state?.pending == true) TextButton(enabled = !novelBusy, onClick = { NovelRepository.schedule(this@ReaderActivity) }) { Text("重试同步") }
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        if (map.json.optInt("offset") > 0) TextButton(enabled = !novelBusy, onClick = { panel = "目录"; loadNovelContents() }) { Text("完整版本目录") }
                                        if (!map.json.isNull("next_offset")) TextButton(enabled = !novelBusy, onClick = {
                                            novelBusy = true
                                            lifecycleScope.launch {
                                                try {
                                                    val next = novel.nextChapter(publication)
                                                    novelBusy = false
                                                    if (next == null) novelMessage = "后续目录暂时没有可读章节" else openNovelChapter(next)
                                                } catch (e: CancellationException) { throw e }
                                                catch (e: Exception) { novelMessage = e.message }
                                                finally { novelBusy = false }
                                            }
                                        }) { Text("下一段章节") }
                                    }
                                }
                            }
                            ReaderProgressControl((locator?.locations?.totalProgression ?: 0.0).toFloat(), positions.isNotEmpty(), progressLabel) { progress ->
                                positions.minByOrNull { kotlin.math.abs((it.locations.totalProgression ?: 0.0) - progress) }?.let(::jumpTo)
                            }
                            Row(Modifier.navigationBarsPadding().fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                IconButton(onClick = { turnPage(false) }) { Icon(Icons.AutoMirrored.Rounded.NavigateBefore, "上一页") }
                                TextButton(onClick = { scope.launch {
                                    val location = navigator?.currentSelection()?.locator ?: locator
                                    if (location != null) asset?.let { library.bookmark(it.id, location.toJSON().toString(), location.text.highlight ?: location.title ?: "书签"); message = "已保存书签或选中文本" }
                                } }) { Text("${((locator?.locations?.totalProgression ?: 0.0) * 100).toInt()}% · 标记") }
                                IconButton(onClick = { turnPage(true) }) { Icon(Icons.AutoMirrored.Rounded.NavigateNext, "下一页") }
                                IconButton(onClick = { updateSettings(readerSettings.toggleNight()) }) {
                                    Icon(if (readerSettings.paper == ReaderPaper.NIGHT) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                        if (readerSettings.paper == ReaderPaper.NIGHT) "恢复日间纸张" else "切换夜间阅读")
                                }
                                IconButton(onClick = {
                                    if (narrating) aloud?.stop() else scope.launch {
                                        try {
                                            narrating = true
                                            val reader = aloud ?: ReadAloud(this@ReaderActivity).also { aloud = it }
                                            publication?.let { reader.read(it, locator) { location -> jumpTo(location) } }
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { message = e.message ?: "朗读失败" }
                                        finally { narrating = false }
                                    }
                                }) { Icon(if (narrating) Icons.Rounded.Stop else Icons.Rounded.RecordVoiceOver, if (narrating) "停止朗读" else "离线朗读，退出阅读时停止") }
                            }
                        }
                    }
                }
            }
            if (panel != null) ModalBottomSheet(onDismissRequest = { panel = null }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp).navigationBarsPadding()) {
                    Text(panel!!, style = MaterialTheme.typography.titleLarge)
                    when (panel) {
                        "搜索" -> {
                            OutlinedTextField(searchQuery, { searchQuery = it }, Modifier.fillMaxWidth(), placeholder = { Text("输入书中内容") }, singleLine = true)
                            TextButton(enabled = searchQuery.isNotBlank(), onClick = {
                                val previous = searchJob
                                val query = searchQuery.trim()
                                previous?.cancel()
                                searchJob = scope.launch {
                                    previous?.join()
                                    searching = true; matches = emptyList(); searchIterator = null; hasMore = false
                                    try {
                                        val iterator = publication?.search(query) ?: error("本书不支持全文搜索")
                                        searchIterator = iterator
                                        val page = withContext(Dispatchers.IO) { iterator.next().getOrElse { error("搜索失败") } }
                                        ensureActive()
                                        matches = page?.locators.orEmpty(); hasMore = page != null
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { message = e.message }
                                    finally { searching = false }
                                }
                            }) { Text("搜索") }
                            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                            LazyColumn(Modifier.heightIn(max = 380.dp)) { items(matches) { found ->
                                TextButton(onClick = { jumpTo(found); panel = null }) { Text(listOfNotNull(found.text.before, found.text.highlight, found.text.after).joinToString(""), maxLines = 3) }
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
                            if (novelPublication != null) {
                                item { Text("本书完整版本目录 · 未下载章节按需获取", style = MaterialTheme.typography.bodySmall) }
                                items(novelContents) { chapter -> TextButton(enabled = !novelBusy && chapter.optBoolean("readable"), onClick = { panel = null; openNovelChapter(chapter.getString("id")) }) { Text("${chapter.optInt("ordinal") + 1}. ${chapter.optString("title")}") } }
                                if (novelContents.size < novelContentsTotal) item { TextButton(enabled = !novelBusy, onClick = { loadNovelContents(true) }) { Text("更多目录") } }
                            } else {
                            fun flatten(links: List<Link>): List<Link> = links.flatMap { listOf(it) + flatten(it.children) }
                            items(flatten(publication?.tableOfContents.orEmpty()).ifEmpty { publication?.readingOrder.orEmpty() }) { link ->
                                TextButton(onClick = { jumpTo(link); panel = null }) { Text(link.title ?: link.href.toString()) }
                            }
                            }
                        }
                        "书签" -> asset?.let { item ->
                            val marks by library.dao.annotations(item.id).collectAsState(emptyList())
                            if (marks.isEmpty()) Text("选中文字或点击底部“标记”保存位置。", Modifier.padding(vertical = 16.dp))
                            LazyColumn(Modifier.heightIn(max = 480.dp)) { items(marks, key = { it.id }) { mark ->
                                Row { TextButton(onClick = { runCatching { Locator.fromJSON(JSONObject(mark.locatorJson)) }.getOrNull()?.let { jumpTo(it) }; panel = null }, Modifier.weight(1f)) { Text(mark.note, maxLines = 3) }
                                    IconButton(onClick = { editingNote = mark; noteText = mark.note }) { Icon(Icons.Rounded.EditNote, "编辑笔记") }
                                    IconButton(onClick = { scope.launch { library.dao.removeAnnotation(mark.id) } }) { Icon(Icons.Rounded.DeleteOutline, "删除书签") } }
                            } }
                        }
                        "排版" -> ReaderPreferencesPanel(readerSettings, ::updateSettings,
                            encoding = if (asset?.format == "txt") ({
                                Text("文本编码：${asset?.let { library.textEncoding(it.id) }}", style = MaterialTheme.typography.labelLarge)
                                FlowRow {
                                    listOf("自动", "UTF-8", "GB18030", "Big5", "UTF-16LE", "UTF-16BE").forEach { encoding ->
                                        TextButton(onClick = { encodingChange = encoding }) { Text(encoding) }
                                    }
                                }
                            }) else null)
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
                    aloud?.stop()
                    progressJob?.cancelAndJoin()
                    ProgressWriter.flush()
                    library.setTextEncoding(item.id, encoding)
                    startActivity(android.content.Intent(this@ReaderActivity, ReaderActivity::class.java).putExtra("assetId", item.id))
                    finish()
                } }; encodingChange = null }) { Text("重新打开") } }, dismissButton = { TextButton(onClick = { encodingChange = null }) { Text("取消") } }) }
        }
    }
    override fun onStop() { turning?.cancel(); surface?.cancelTurn(); aloud?.stop(); if (novelPublication != null) NovelRepository.schedule(this); super.onStop() }
    override fun onDestroy() { turning?.cancel(); paperJob?.cancel(); surface?.cancelTurn(); surface = null; searchJob?.cancel(); aloud?.close(); super.onDestroy(); publication?.close() }
}
