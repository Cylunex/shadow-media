@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package top.cylunex.shadowmedia.reading

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import top.cylunex.shadowmedia.database.LibraryAssetEntity
import top.cylunex.shadowmedia.library.*

/** CBZ uses tile decoding from a bounded on-disk page, never a full-size in-memory bitmap. */
class ComicActivity : ComponentActivity() {
    private lateinit var library: LibraryRepository
    private var asset by mutableStateOf<LibraryAssetEntity?>(null)
    private var pages by mutableStateOf<List<String>>(emptyList())
    private var initialPage = 0
    private var initialOffset = 0f
    private var error by mutableStateOf<String?>(null)
    private var mode by mutableStateOf("LTR")
    private var currentPage by mutableIntStateOf(0)
    private var currentOffset = 0f
    private var sourceAdapter: ComicSourceAdapter? = null
    private val renderAdapter: ComicRenderAdapter = PlatformComicRenderer()
    private var progressSession: ProgressSession? = null
    private var lastSavedPage = -1
    private var lastSaveTime = 0L
    private var restoreEpoch by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = LibraryRepository(this)
        mode = getPreferences(MODE_PRIVATE).getString("mode", "LTR") ?: "LTR"
        lifecycleScope.launch {
            try {
                val item = requireNotNull(intent.getStringExtra("assetId")?.let { library.dao.asset(it) }) { "文件已移除" }
                val source = LibraryComicSource(item, library)
                val manifest = withContext(Dispatchers.IO) { source.pages() }
                require(manifest.isNotEmpty()) { "未找到可显示的页面" }
                progressSession = ProgressWriter.begin(library, item)
                val saved = savedInstanceState?.takeIf { it.getString("revision") == item.revision }
                val progress = library.dao.progress(item.id)?.let { runCatching { JSONObject(it.locatorJson) }.getOrNull() }
                initialPage = (saved?.getInt("page") ?: progress?.optInt("pageIndex") ?: 0).coerceIn(manifest.indices)
                initialOffset = (saved?.getFloat("offset") ?: progress?.optDouble("offset", 0.0)?.toFloat() ?: 0f).coerceIn(0f, 1f)
                currentPage = initialPage; currentOffset = initialOffset
                sourceAdapter = source; asset = item; pages = manifest
                save()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "页面读取失败" }
        }
        setContent { ComicReader() }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("revision", asset?.revision); outState.putInt("page", currentPage); outState.putFloat("offset", currentOffset); super.onSaveInstanceState(outState) }
    private fun save(force: Boolean = false) {
        val item = asset ?: return
        val page = currentPage; val offset = currentOffset
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && page == lastSavedPage && now - lastSaveTime < 250) return
        lastSavedPage = page; lastSaveTime = now
        ProgressWriter.save(library, progressSession ?: return, "page", JSONObject().put("chapterId", item.id).put("pageIndex", page).put("offset", offset),
            (page + offset.toDouble()) / pages.size.coerceAtLeast(1), completed = comicCompleted(page, pages.size, mode, offset))
    }
    override fun onStop() { save(force = true); super.onStop() }

    @Composable private fun ComicReader() {
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF78ABED), background = Color(0xFF101114))) {
            val scope = rememberCoroutineScope()
            var settings by remember { mutableStateOf(false) }
            var marksVisible by remember { mutableStateOf(false) }
            val perSpread = if (mode == "双页") 2 else 1
            val pager = rememberPagerState(initialPage = initialPage / perSpread) { ((pages.size + perSpread - 1) / perSpread).coerceAtLeast(1) }
            LaunchedEffect(pager, pages, perSpread) {
                if (pages.isEmpty()) return@LaunchedEffect
                pager.scrollToPage((currentPage / perSpread).coerceAtMost(pager.pageCount - 1))
                snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
                    val index = page * perSpread
                    if (currentPage / perSpread != page) { currentPage = index; currentOffset = 0f; save() }
                }
            }
            Scaffold(topBar = { TopAppBar(title = { Text(asset?.title ?: "漫画", maxLines = 1) }, navigationIcon = {
                IconButton(onClick = { save(); finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回书架") }
            }, actions = {
                IconButton(onClick = { marksVisible = true }) { Icon(Icons.Rounded.Bookmarks, "书签") }
                IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Tune, "阅读方向与模式") }
            }) }, bottomBar = {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp)) {
                    if (pages.isNotEmpty()) Slider(value = currentPage.toFloat(), onValueChange = { scope.launch { pager.scrollToPage(it.toInt() / perSpread) } }, valueRange = 0f..(pages.size - 1).coerceAtLeast(1).toFloat())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${if (pages.isEmpty()) 0 else currentPage + 1} / ${pages.size}")
                        TextButton(onClick = { scope.launch { asset?.let { library.bookmark(it.id, JSONObject().put("pageIndex", currentPage).put("offset", currentOffset).toString(), "第 ${currentPage + 1} 页") } } }) { Text("添加书签") }
                    }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
                    if (error != null) Text(error!!, Modifier.padding(24.dp))
                    else if (pages.isEmpty()) CircularProgressIndicator(Modifier.padding(24.dp))
                    else HorizontalPager(pager, reverseLayout = mode == "RTL", beyondViewportPageCount = 0, modifier = Modifier.fillMaxSize()) { spread ->
                        Row(Modifier.fillMaxSize()) {
                            repeat(perSpread) { offset ->
                                val index = spread * perSpread + offset
                                if (index in pages.indices) PageImage(index, Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    }
                }
            }
            if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("阅读模式") }, text = {
                Column { listOf("LTR" to "从左向右", "RTL" to "从右向左", "长图" to "长图 · 宽度适配，可上下拖动", "双页" to "双页对开").forEach { (key, label) ->
                    TextButton(onClick = { initialPage = currentPage; initialOffset = currentOffset; mode = key; getPreferences(MODE_PRIVATE).edit().putString("mode", key).apply(); settings = false }) { Text(label) }
                } }
            }, confirmButton = { TextButton(onClick = { settings = false }) { Text("关闭") } })
            if (marksVisible) asset?.let { item ->
                val marks by library.dao.annotations(item.id).collectAsState(emptyList())
                ModalBottomSheet(onDismissRequest = { marksVisible = false }) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 480.dp).padding(20.dp)) {
                        item { Text("阅读书签", style = MaterialTheme.typography.titleLarge) }
                        items(marks.size) { index -> val mark = marks[index]
                            Row {
                                TextButton(onClick = { scope.launch {
                                    val value = runCatching { JSONObject(mark.locatorJson) }.getOrNull()
                                    val target = (value?.optInt("pageIndex") ?: 0).coerceIn(pages.indices)
                                    initialPage = target; initialOffset = (value?.optDouble("offset", 0.0)?.toFloat() ?: 0f).coerceIn(0f, 1f)
                                    currentPage = target; currentOffset = initialOffset; restoreEpoch++
                                    pager.scrollToPage(target / perSpread); save(force = true); marksVisible = false
                                } }, Modifier.weight(1f)) { Text(mark.note) }
                                IconButton(onClick = { scope.launch { library.dao.removeAnnotation(mark.id) } }) { Icon(Icons.Rounded.DeleteOutline, "删除书签") }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun PageImage(index: Int, modifier: Modifier) {
        var local by remember(index, asset?.revision) { mutableStateOf<File?>(null) }
        var failure by remember(index) { mutableStateOf<String?>(null) }
        LaunchedEffect(index, asset?.revision) {
            var prepared: File? = null
            try { withContext(Dispatchers.IO) { prepared = preparePage(index) }; local = prepared; prepared = null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failure = "第 ${index + 1} 页读取失败" }
            finally { prepared?.delete() }
        }
        Box(modifier) {
            val image = local
            if (image != null) key(image.path, mode, restoreEpoch) {
                AndroidView(factory = { context -> SubsamplingScaleImageView(context).apply {
                    setMaxTileSize(1024)
                    setMinimumScaleType(if (mode == "长图") SubsamplingScaleImageView.SCALE_TYPE_START else SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                    setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            if (mode == "长图") {
                                val scale = width.toFloat() / sWidth.coerceAtLeast(1)
                                val offset = if (index == initialPage) initialOffset else 0f
                                setScaleAndCenter(scale, PointF(sWidth / 2f, (height / (2 * scale) + offset * (sHeight - height / scale)).coerceAtLeast(0f)))
                                if (index == currentPage && sHeight * scale <= height) { currentOffset = 1f; save(force = true) }
                            }
                        }
                        override fun onImageLoadError(e: Exception) { failure = "图片格式损坏或设备不支持" }
                    })
                    setOnStateChangedListener(object : SubsamplingScaleImageView.OnStateChangedListener {
                        override fun onScaleChanged(newScale: Float, origin: Int) {}
                        override fun onCenterChanged(newCenter: PointF, origin: Int) {
                            if (index == currentPage && scale > 0 && origin != 0) {
                                val visible = height / scale
                                currentOffset = if (sHeight <= visible) 1f else ((newCenter.y - visible / 2) / (sHeight - visible)).coerceIn(0f, 1f)
                                save()
                            }
                        }
                    })
                    setImage(ImageSource.uri(Uri.fromFile(image)))
                } }, modifier = Modifier.fillMaxSize(), onRelease = { it.recycle() })
            }
            if (failure != null) Text(failure!!, Modifier.padding(16.dp))
            else if (image == null) CircularProgressIndicator(Modifier.padding(16.dp))
        }
        DisposableEffect(local) { val ownedFile = local; onDispose { ownedFile?.delete() } }
    }

    private suspend fun preparePage(index: Int): File {
        val directory = File(cacheDir, "comic-pages").apply { mkdirs() }
        val result = File.createTempFile("page-", ".img", directory)
        try {
            val resource = requireNotNull(sourceAdapter).page(pages[index], result)
            return renderAdapter.render(resource, result)
        } catch (e: Exception) { result.delete(); throw e }
    }
}
