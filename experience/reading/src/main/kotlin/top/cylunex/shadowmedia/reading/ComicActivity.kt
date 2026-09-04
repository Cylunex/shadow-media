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
    private var file: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = LibraryRepository(this)
        mode = getPreferences(MODE_PRIVATE).getString("mode", "LTR") ?: "LTR"
        lifecycleScope.launch {
            try {
                val item = requireNotNull(intent.getStringExtra("assetId")?.let { library.dao.asset(it) }) { "文件已移除" }
                val source = library.localFile(item)
                val manifest = withContext(Dispatchers.IO) {
                    if (item.format == "pdf") PdfRenderer(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer -> List(renderer.pageCount) { it.toString() } }
                    else { SafeArchives.validate(source); LibraryRepository.comicPages(source) }
                }
                require(manifest.isNotEmpty()) { "未找到可显示的页面" }
                val progress = library.dao.progress(item.id)?.let { runCatching { JSONObject(it.locatorJson) }.getOrNull() }
                initialPage = (savedInstanceState?.getInt("page") ?: progress?.optInt("pageIndex") ?: 0).coerceIn(manifest.indices)
                initialOffset = (savedInstanceState?.getFloat("offset") ?: progress?.optDouble("offset", 0.0)?.toFloat() ?: 0f).coerceIn(0f, 1f)
                currentPage = initialPage; currentOffset = initialOffset
                file = source; asset = item; pages = manifest
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "页面读取失败" }
        }
        setContent { ComicReader() }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("page", currentPage); outState.putFloat("offset", currentOffset); super.onSaveInstanceState(outState) }
    private fun save() {
        val item = asset ?: return
        val page = currentPage; val offset = currentOffset
        lifecycleScope.launch { library.saveProgress(item.id, "page", JSONObject().put("chapterId", item.id).put("pageIndex", page).put("offset", offset),
            (page + offset.toDouble()) / pages.size.coerceAtLeast(1)) }
    }

    @Composable private fun ComicReader() {
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF78ABED), background = Color(0xFF101114))) {
            val scope = rememberCoroutineScope()
            var settings by remember { mutableStateOf(false) }
            var marksVisible by remember { mutableStateOf(false) }
            val perSpread = if (mode == "双页") 2 else 1
            val pager = rememberPagerState(initialPage = initialPage / perSpread) { ((pages.size + perSpread - 1) / perSpread).coerceAtLeast(1) }
            LaunchedEffect(pages, perSpread) { if (pages.isNotEmpty()) pager.scrollToPage((currentPage / perSpread).coerceAtMost(pager.pageCount - 1)) }
            LaunchedEffect(pager, pages, perSpread) {
                if (pages.isEmpty()) return@LaunchedEffect
                snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
                    val index = page * perSpread
                    if (currentPage != index) { currentPage = index; currentOffset = 0f; save() }
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
                    TextButton(onClick = { mode = key; getPreferences(MODE_PRIVATE).edit().putString("mode", key).apply(); settings = false }) { Text(label) }
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
                                    val target = runCatching { JSONObject(mark.locatorJson).optInt("pageIndex") }.getOrDefault(0).coerceIn(pages.indices)
                                    pager.scrollToPage(target / perSpread); marksVisible = false
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
            try { local = withContext(Dispatchers.IO) { preparePage(index) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failure = "第 ${index + 1} 页读取失败" }
        }
        Box(modifier) {
            val image = local
            if (image != null) key(image.path, mode) {
                AndroidView(factory = { context -> SubsamplingScaleImageView(context).apply {
                    setMaxTileSize(1024)
                    setMinimumScaleType(if (mode == "长图") SubsamplingScaleImageView.SCALE_TYPE_START else SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                    setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            if (mode == "长图") {
                                val scale = width.toFloat() / sWidth.coerceAtLeast(1)
                                val offset = if (index == initialPage) initialOffset else 0f
                                setScaleAndCenter(scale, PointF(sWidth / 2f, (height / (2 * scale) + offset * (sHeight - height / scale)).coerceAtLeast(0f)))
                            }
                        }
                        override fun onImageLoadError(e: Exception) { failure = "图片格式损坏或设备不支持" }
                    })
                    setOnStateChangedListener(object : SubsamplingScaleImageView.OnStateChangedListener {
                        override fun onScaleChanged(newScale: Float, origin: Int) {}
                        override fun onCenterChanged(newCenter: PointF, origin: Int) {
                            if (index == currentPage && scale > 0 && origin != 0) {
                                val visible = height / scale
                                currentOffset = ((newCenter.y - visible / 2) / (sHeight - visible).coerceAtLeast(1f)).coerceIn(0f, 1f)
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
        DisposableEffect(local) { onDispose { local?.delete() } }
    }

    private fun preparePage(index: Int): File {
        val source = requireNotNull(file)
        val directory = File(cacheDir, "comic-pages").apply { mkdirs() }
        val result = File.createTempFile("page-", ".img", directory)
        try {
            if (asset?.format == "pdf") {
                PdfRenderer(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer -> renderer.openPage(index).use { page ->
                    val ratio = minOf(2048f / page.width, 2048f / page.height, 2f)
                    val bitmap = Bitmap.createBitmap((page.width * ratio).toInt().coerceAtLeast(1), (page.height * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try { bitmap.eraseColor(android.graphics.Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); result.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
                } }
            } else ZipFile(source).use { zip ->
                val entry = requireNotNull(zip.getEntry(pages[index]))
                require(entry.size in 1..SafeArchives.MAX_ENTRY_BYTES)
                zip.getInputStream(entry).use { input -> result.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024); var total = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= SafeArchives.MAX_ENTRY_BYTES); output.write(buffer, 0, n) }
                } }
            }
            return result
        } catch (e: Exception) { result.delete(); throw e }
    }
}
