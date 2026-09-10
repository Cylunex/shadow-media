@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package top.cylunex.shadowmedia.audio

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.first
import top.cylunex.shadowmedia.database.*

/** Every browse request reads Room only, so notification/Auto metadata works without a connection. */
internal class AudioLibraryTree(private val db: ShadowMediaDatabase, private val resolve: suspend (LibraryAssetEntity) -> MediaItem) {
    val root = folder("root", "Shadow Media")
    private val roots = linkedMapOf("recent" to "最近收听", "albums" to "专辑", "artists" to "艺人", "songs" to "歌曲", "playlists" to "歌单", "favorites" to "收藏", "books" to "听书", "podcasts" to "播客")
    suspend fun item(id: String): MediaItem? {
        if (id == "root") return root
        roots[id]?.let { return folder(id, it) }
        val group = when {
            id.startsWith("album:") -> db.musicDao().album(Uri.decode(id.removePrefix("album:")))?.title
            id.startsWith("artist:") -> db.musicDao().artist(Uri.decode(id.removePrefix("artist:")))?.title
            id.startsWith("playlist:") -> db.musicDao().playlist(id.removePrefix("playlist:"))?.title
            else -> null
        }
        if (group != null) return folder(id, group.ifBlank { "未标注" })
        return db.libraryDao().asset(id)?.takeIf { it.kind in setOf("MUSIC", "AUDIOBOOK", "PODCAST") }?.let { resolve(it) }
    }
    suspend fun children(parent: String, page: Int, pageSize: Int, query: String = ""): List<MediaItem> {
        require(page >= 0 && pageSize in 1..200)
        val offset = (page.toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val dao = db.musicDao()
        fun group(id: String, title: String) = folder(id, title)
        if (parent == "root") return roots.map { group(it.key, it.value) }.drop(offset).take(pageSize)
        if (parent == "albums" || parent == "artists") return (if (parent == "albums") dao.albums() else dao.artists()).read(offset, pageSize)
            .map { group((if (parent == "albums") "album:" else "artist:") + Uri.encode(it.key), it.title.ifBlank { "未标注" }) }
        if (parent == "playlists") return dao.playlists().first().drop(offset).take(pageSize).map { group("playlist:${it.id}", it.title) }
        if (parent.startsWith("playlist:")) {
            val entries = dao.entries(parent.removePrefix("playlist:")).drop(offset).take(pageSize)
            val assets = db.libraryDao().assetsByIds(entries.map { it.assetId }).associateBy { it.id }
            return entries.mapNotNull { assets[it.assetId] }.map { resolve(it) }
        }
        if (parent == "books" || parent == "podcasts") return db.libraryDao().searchPage(if (parent == "books") "AUDIOBOOK" else "PODCAST", "%", offset, pageSize).map { resolve(it) }
        if (parent !in setOf("songs", "favorites", "recent") && !parent.startsWith("album:") && !parent.startsWith("artist:")) return emptyList()
        val pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return dao.page(pattern, if (parent.startsWith("album:")) Uri.decode(parent.removePrefix("album:")) else "",
            if (parent.startsWith("artist:")) Uri.decode(parent.removePrefix("artist:")) else "", "", parent == "favorites", parent == "recent", "")
            .read(offset, pageSize).map { resolve(it.asset) }
    }
    private suspend fun <T : Any> PagingSource<Int, T>.read(offset: Int, size: Int): List<T> = try {
        when (val result = load(PagingSource.LoadParams.Refresh(offset, size, false))) {
            is PagingSource.LoadResult.Page -> result.data
            is PagingSource.LoadResult.Error -> throw result.throwable
            else -> emptyList()
        }
    } finally { invalidate() }
    private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder()
        .setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()
}
