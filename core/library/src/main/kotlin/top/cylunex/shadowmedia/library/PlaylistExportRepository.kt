package top.cylunex.shadowmedia.library

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import top.cylunex.shadowmedia.database.*
import java.util.UUID

/** Explicitly exports a snapshot as a server playlist copy; never edits an unrelated remote playlist. */
class PlaylistExportRepository(context: Context, private val catalogs: NativeCatalogRepository, private val library: LibraryRepository) {
    private val database = ShadowMediaDatabase.create(context)
    val dao = database.libraryStateDao()
    private val music = database.musicDao()
    private val lock = Mutex()
    suspend fun forget(provider: String, playlist: String) = lock.withLock { dao.removeExport(provider, playlist) }
    suspend fun export(playlistId: String, providerId: String): String = lock.withLock {
        check(!LibraryResources.offlineOnly) { "仅离线模式下无法导出" }
        val provider = catalogs.musicProvider(catalogs.connection(providerId))
        val playlist = requireNotNull(music.playlist(playlistId)) { "本地歌单已移除" }
        val entries = music.entries(playlistId)
        val assets = library.dao.assetsByIds(entries.map { it.assetId }).associateBy { it.id }
        val ids = entries.mapNotNull { assets[it.assetId]?.takeIf { asset -> asset.providerId == providerId && asset.kind == "MUSIC" }?.itemId }
        require(ids.isNotEmpty()) { "歌单里没有此账号可识别的歌曲" }
        val payload = JSONArray(ids).toString()
        val previous = dao.export(providerId, playlistId)
        val reusable = previous?.takeUnless { it.phase == "DONE" && (it.itemIdsJson != payload || it.title != playlist.title) }
        if (reusable?.phase == "DONE") return@withLock "这份歌单已经导出并核对"
        var checkpoint = reusable ?: PlaylistExportEntity(providerId, playlistId, UUID.randomUUID().toString(), playlist.title, payload, updatedAt = System.currentTimeMillis()).also { dao.putExport(it) }
        if (checkpoint.phase in setOf("CREATING", "UNKNOWN")) error("上次导出结果尚未确认，请到服务器核对；不会自动重复创建")
        val snapshot = JSONArray(checkpoint.itemIdsJson).let { array -> (0 until array.length()).map { array.getString(it) } }
        if (checkpoint.phase == "READY") provider.preparePlaylistExport(checkpoint.title, snapshot)
        try {
            if (checkpoint.phase == "READY") {
                checkpoint = checkpoint.copy(phase = "CREATING", updatedAt = System.currentTimeMillis())
                check(dao.updateExport(checkpoint)) { "本地歌单或导出任务已移除" }
                val remoteId = provider.createPlaylistCopy(checkpoint.title, snapshot)
                checkpoint = checkpoint.copy(remoteId = remoteId, phase = "CREATED", updatedAt = System.currentTimeMillis())
                withContext(NonCancellable) { check(dao.updateExport(checkpoint)) { "本地歌单或导出任务已移除" } }
            }
            val remote = provider.playlistItemIds(checkpoint.remoteId)
            check(remote == snapshot) { "服务器回读的歌曲顺序或重复次数不一致，请到服务器核对" }
            check(dao.updateExport(checkpoint.copy(phase = "DONE", message = "已回读核对", updatedAt = System.currentTimeMillis()))) { "本地歌单或导出任务已移除" }
            "已导出 ${snapshot.size} 首歌曲并核对" + if (entries.size > snapshot.size) "，跳过 ${entries.size - snapshot.size} 首其他来源或已移除歌曲" else ""
        } catch (e: Exception) {
            withContext(NonCancellable) {
                dao.updateExport(checkpoint.copy(phase = if (checkpoint.remoteId.isBlank()) "UNKNOWN" else "CREATED",
                    message = if (checkpoint.remoteId.isBlank()) "创建结果未确认，不会自动重发" else "已创建，等待回读核对", updatedAt = System.currentTimeMillis()))
            }
            throw e
        }
    }
}
