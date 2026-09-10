package top.cylunex.shadowmedia.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.cylunex.shadowmedia.database.*
import top.cylunex.shadowmedia.model.*

class MusicRepository(private val context: Context, private val library: LibraryRepository) {
    private val database = ShadowMediaDatabase.create(context)
    val dao = database.musicDao()
    val snapshots = database.catalogSnapshotDao()

    suspend fun importDocument(uri: Uri, folder: String = "", lyrics: String = ""): LibraryAssetEntity = withContext(Dispatchers.IO) {
        val asset = library.importDocument(uri, AudioMode.MUSIC)
        require(asset.kind == "MUSIC") { "请选择音频文件" }
        val retriever = MediaMetadataRetriever()
        try {
            val tags = runCatching {
                retriever.setDataSource(context, uri)
                fun tag(key: Int) = retriever.extractMetadata(key).orEmpty()
                MusicTrackEntity(asset.id, album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST), albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    disc = tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER).substringBefore('/').toIntOrNull() ?: 0,
                    track = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/').toIntOrNull() ?: 0,
                    durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0, folder = folder, lyrics = lyrics)
            }.getOrElse { MusicTrackEntity(asset.id, folder = folder, lyrics = lyrics) }
            dao.putTrack(tags.scoped(asset.providerId))
            asset
        } finally { retriever.release() }
    }

    /** Only traverse the user-granted tree, preserving SAF permissions and original media. */
    suspend fun importDirectory(uri: Uri, onProgress: (Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, uri))
        val pending = ArrayDeque<Pair<DocumentFile, Int>>().apply { add(root to 0) }
        var count = 0; var visited = 0
        while (pending.isNotEmpty()) {
            ensureActive()
            val (dir, depth) = pending.removeFirst()
            require(++visited <= 10000 && depth <= 32) { "目录层级或数量过多，请选择较小的音乐目录" }
            val files = dir.listFiles()
            val sidecars = files.filter { it.isFile && it.name?.endsWith(".lrc", true) == true }.associateBy { it.name!!.substringBeforeLast('.').lowercase() }
            for (file in files) {
                ensureActive()
                if (file.isDirectory) { pending.add(file to depth + 1); continue }
                if (!file.isFile || contentKindForFile(file.name.orEmpty()) != ContentKind.AUDIOBOOK) continue
                val lrc = sidecars[file.name.orEmpty().substringBeforeLast('.').lowercase()]?.let { sidecar ->
                    runCatching { context.contentResolver.openInputStream(sidecar.uri)?.use { it.readBytesBounded(512 * 1024).toString(Charsets.UTF_8) } }.getOrNull()
                }.orEmpty()
                importDocument(file.uri, dir.name.orEmpty(), lrc)
                onProgress(++count)
            }
        }
        count
    }

    suspend fun importRemote(item: UnifiedMediaItem): LibraryAssetEntity {
        val tags = item.music ?: MusicMetadata()
        val asset = library.addRemote(item.copy(type = "MUSIC"), "audio")
        database.withTransaction {
            library.dao.asset(asset.id)?.let { library.dao.putAsset(it.copy(kind = "MUSIC", title = item.title, author = tags.artist)) }
            dao.putTrack(MusicTrackEntity(asset.id, album = tags.album, artist = tags.artist,
                albumArtist = tags.albumArtist, disc = tags.disc, track = tags.track,
                durationMs = item.durationMs ?: 0).scoped(asset.providerId, tags.albumId))
        }
        return asset.copy(kind = "MUSIC")
    }
}

private fun MusicTrackEntity.scoped(provider: String, albumId: String = "") = copy(
    albumKey = scopedContentId(provider, albumId.ifBlank { scopedContentId(albumArtist.ifBlank { artist }, album) }),
    artistKey = scopedContentId(provider, artist))
private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; require(output.size() + n <= limit); output.write(buffer, 0, n) }
    return output.toByteArray()
}
