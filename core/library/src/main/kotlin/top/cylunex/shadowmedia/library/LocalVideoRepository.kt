package top.cylunex.shadowmedia.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import top.cylunex.shadowmedia.model.*
import top.cylunex.shadowmedia.provider.*

/** Stores only user-selected URI grants and metadata; videos stay in their original location. */
class LocalVideoRepository(private val context: Context) : MediaProvider {
    private val mutations = Mutex()
    private val preferences = context.getSharedPreferences("local_videos", Context.MODE_PRIVATE)
    override val descriptor = ProviderDescriptor("local-video", "本机视频与链接", ProviderKind.LOCAL_LIBRARY,
        setOf(ProviderCapability.BROWSE, ProviderCapability.SEARCH, ProviderCapability.DETAIL, ProviderCapability.PLAYBACK, ProviderCapability.SUBTITLES))
    private val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "m2ts", "3gp", "flv", "mpeg", "mpg")

    private data class DocumentSnapshot(val document: DocumentFile, val name: String, val directory: Boolean, val mimeType: String?)

    private fun records(): List<JSONObject> = synchronized(this) {
        val array = JSONArray(preferences.getString("items", "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun save(records: List<JSONObject>) = synchronized(this) {
        preferences.edit().putString("items", JSONArray(records).toString()).apply()
    }
    private fun record(uri: Uri, name: String, group: String, subtitles: List<ExternalSubtitle> = emptyList()) =
        JSONObject().put("uri", uri.toString()).put("name", name).put("group", group).put("subtitles", JSONArray(
            subtitles.map { JSONObject().put("uri", it.uri).put("name", it.label) }))

    suspend fun addDocuments(uris: List<Uri>) = withContext(Dispatchers.IO) { mutations.withLock {
        val imported = uris.map { uri ->
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "视频"
            record(uri, name, "所选文件")
        }
        save((records() + imported).distinctBy { it.getString("uri") })
    } }
    suspend fun addLink(url: String) = withContext(Dispatchers.IO) { mutations.withLock {
        val uri = Uri.parse(url.trim())
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "请输入有效的 HTTP(S) 视频或 m3u8 地址" }
        val entry = record(uri, uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "视频链接", "视频链接")
        save((records() + entry).distinctBy { it.getString("uri") })
        entry.item()
    } }
    suspend fun addFolder(uri: Uri) = withContext(Dispatchers.IO) {
        mutations.withLock {
            val folders = preferences.getStringSet("folders", emptySet()).orEmpty() + uri.toString()
            preferences.edit().putStringSet("folders", folders).apply()
        }
        refresh()
    }
    suspend fun refresh() = withContext(Dispatchers.IO) { mutations.withLock {
        val previous = records()
        val result = previous.filter { it.getString("group") in setOf("所选文件", "视频链接") }.toMutableList()
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)) {
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    ensureActive()
                    result += record(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)), cursor.getString(1), "手机视频")
                }
            }
        }
        val failed = mutableListOf<String>()
        for (folder in preferences.getStringSet("folders", emptySet()).orEmpty()) {
            try {
                val root = requireNotNull(DocumentFile.fromTreeUri(context, Uri.parse(folder)))
                check(root.canRead()) { "文件夹授权失效" }
                val pending = ArrayDeque<Pair<DocumentFile, Int>>()
                pending.add(root to 0)
                var visited = 0
                while (pending.isNotEmpty()) {
                    ensureActive()
                    check(++visited <= 10_000) { "目录过大，请添加较小的子目录" }
                    val (directory, depth) = pending.removeFirst()
                    val children = directory.listFiles().map { DocumentSnapshot(it, it.name.orEmpty(), it.isDirectory, it.type) }
                    val sidecars = children.filter { !it.directory && subtitleMimeType(it.name) != null }
                    for (file in children) {
                        ensureActive()
                        if (file.directory) {
                            check(depth < 32) { "目录层级过深，请添加子目录" }
                            pending.add(file.document to depth + 1)
                        } else if (file.mimeType?.startsWith("video/") == true || file.name.substringAfterLast('.').lowercase() in videoExtensions) {
                            val subtitles = sidecars.filter { isCompanionSubtitle(file.name, it.name) }.take(16).map {
                                ExternalSubtitle(it.document.uri.toString(), requireNotNull(subtitleMimeType(it.name)), it.name)
                            }
                            result += record(file.document.uri, file.name, folder, subtitles)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { failed += folder; result += previous.filter { it.getString("group") == folder } }
        }
        save(result.distinctBy { it.getString("uri") })
        check(failed.isEmpty()) { "${failed.size} 个文件夹无法完整读取，请重新授权或选择较小的目录" }
    } }
    fun folders(): List<String> = preferences.getStringSet("folders", emptySet()).orEmpty().sorted()
    suspend fun removeFolder(uri: String) = withContext(Dispatchers.IO) { mutations.withLock {
        preferences.edit().putStringSet("folders", preferences.getStringSet("folders", emptySet()).orEmpty() - uri).apply()
        save(records().filterNot { it.getString("group") == uri })
    } }
    private fun JSONObject.item() = UnifiedMediaItem(MediaKey(descriptor.id, getString("uri")), getString("name"), "Video",
        subtitle = if (getString("group").startsWith("content:")) "已添加文件夹" else getString("group"))
    suspend fun videos(): List<UnifiedMediaItem> = withContext(Dispatchers.IO) { records().map { it.item() }.sortedBy { it.title.lowercase() } }
    override suspend fun home() = listOf(ProviderSection("videos", "本机视频", videos().take(60)))
    override suspend fun browse(request: ProviderBrowseRequest): UnifiedMediaPage = page(videos(), request.pageToken, request.pageSize)
    override suspend fun search(request: ProviderSearchRequest) = page(videos().filter { it.title.contains(request.query, true) }, request.pageToken, request.pageSize)
    private fun page(items: List<UnifiedMediaItem>, token: String?, size: Int): UnifiedMediaPage {
        val start = token?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val end = (start + size.coerceIn(1, 200)).coerceAtMost(items.size)
        return UnifiedMediaPage(items.drop(start).take(size.coerceIn(1, 200)), end.toString().takeIf { end < items.size }, items.size)
    }
    override suspend fun detail(key: MediaKey) = MediaDetail(requireNotNull(videos().find { it.key == key }) { "视频已移除，请刷新本机视频库" })
    override suspend fun resolve(request: UnifiedPlaybackRequest): List<PlaybackCandidate> = withContext(Dispatchers.IO) {
        val record = requireNotNull(records().find { it.getString("uri") == request.key.itemId }) { "视频已移除" }
        val array = record.optJSONArray("subtitles") ?: JSONArray()
        val subtitles = (0 until array.length()).map { array.getJSONObject(it) }.map {
            ExternalSubtitle(it.getString("uri"), requireNotNull(subtitleMimeType(it.getString("name"))), it.getString("name"))
        }
        listOf(PlaybackCandidate(record.getString("uri"), PlayMethod.DIRECT_PLAY, emptyMap(), subtitles = subtitles))
    }
}
