package top.cylunex.shadowmedia.database

import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** User-owned collection state is independent from replaceable catalog metadata. */
@Entity(tableName = "user_collection", primaryKeys = ["profileId", "assetId"], indices = [Index("assetId")], foreignKeys = [ForeignKey(entity = LibraryAssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)])
data class UserCollectionEntity(val profileId: String = "default", val assetId: String, val collected: Boolean, val favorite: Boolean, val addedAt: Long)
@Entity(tableName = "works")
data class WorkEntity(@PrimaryKey val id: String, val title: String)
@Entity(tableName = "renditions", indices = [Index("workId")], foreignKeys = [ForeignKey(entity = LibraryAssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = WorkEntity::class, parentColumns = ["id"], childColumns = ["workId"], onDelete = ForeignKey.CASCADE)])
data class RenditionEntity(@PrimaryKey val assetId: String, val workId: String)
@Entity(tableName = "playlist_exports", primaryKeys = ["providerId", "playlistId"], indices = [Index("playlistId")], foreignKeys = [ForeignKey(entity = MusicPlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE)])
data class PlaylistExportEntity(val providerId: String, val playlistId: String, val operationId: String, val title: String, val itemIdsJson: String,
    val remoteId: String = "", val phase: String = "READY", val message: String = "", val updatedAt: Long)
@Entity(tableName = "catalog_pages")
data class CatalogPageEntity(@PrimaryKey val id: String, val payload: String, val updatedAt: Long)
@Entity(tableName = "remote_user_states", primaryKeys = ["providerId", "itemId"], indices = [Index("pending")])
data class RemoteUserStateEntity(val providerId: String, val itemId: String, val favorite: Boolean, val operationId: String, val pending: Boolean, val updatedAt: Long)

data class ContinueRow(val rowId: String, val assetId: String?, val providerId: String, val itemId: String, val title: String,
    val kind: String, val positionMs: Long?, val durationMs: Long?, val progression: Double?, val updatedAt: Long)

@Dao interface LibraryStateDao {
    @Query("SELECT * FROM remote_user_states WHERE providerId = :provider") fun userStates(provider: String): Flow<List<RemoteUserStateEntity>>
    @Query("SELECT * FROM remote_user_states WHERE providerId = :provider") suspend fun readUserStates(provider: String): List<RemoteUserStateEntity>
    @Query("SELECT * FROM remote_user_states WHERE providerId = :provider AND itemId = :item") suspend fun userState(provider: String, item: String): RemoteUserStateEntity?
    @Query("UPDATE remote_user_states SET pending = 0 WHERE providerId = :provider AND itemId = :item AND operationId = :operation") suspend fun acknowledgeUserState(provider: String, item: String, operation: String)
    @Upsert suspend fun putUserState(value: RemoteUserStateEntity)
    @Query("UPDATE library_assets SET favorite = :favorite WHERE providerId = :provider AND itemId = :item") suspend fun mirrorFavorite(provider: String, item: String, favorite: Boolean)
    @Query("UPDATE user_collection SET favorite = :favorite WHERE profileId = 'default' AND assetId IN (SELECT id FROM library_assets WHERE providerId = :provider AND itemId = :item)") suspend fun mirrorCollectionFavorite(provider: String, item: String, favorite: Boolean)
    @Transaction suspend fun favorite(value: RemoteUserStateEntity) {
        putUserState(value); mirrorFavorite(value.providerId, value.itemId, value.favorite); mirrorCollectionFavorite(value.providerId, value.itemId, value.favorite)
    }
    @Query("SELECT * FROM user_collection WHERE assetId = :id AND profileId = :profile") suspend fun collection(id: String, profile: String = "default"): UserCollectionEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun seedCollection(state: UserCollectionEntity)
    @Query("UPDATE user_collection SET collected = 1 WHERE assetId = :id AND profileId = :profile") suspend fun collect(id: String, profile: String = "default")
    @Query("SELECT * FROM works WHERE id = :id") suspend fun work(id: String): WorkEntity?
    @Query("SELECT * FROM renditions WHERE assetId = :id") suspend fun rendition(id: String): RenditionEntity?
    @Query("SELECT a.* FROM library_assets a JOIN renditions r ON r.assetId = a.id WHERE r.workId = :workId ORDER BY a.kind, a.title") fun editions(workId: String): Flow<List<LibraryAssetEntity>>
    @Upsert suspend fun putWork(work: WorkEntity)
    @Upsert suspend fun putRendition(rendition: RenditionEntity)
    @Query("DELETE FROM renditions WHERE assetId = :id") suspend fun unlink(id: String)
    @Query("DELETE FROM works WHERE id NOT IN (SELECT workId FROM renditions)") suspend fun cleanEmptyWorks()
    @Transaction suspend fun associate(source: String, target: String, title: String) {
        require(source != target)
        val workId = rendition(target)?.workId ?: java.util.UUID.randomUUID().toString().also { putWork(WorkEntity(it, title)) }
        putRendition(RenditionEntity(target, workId)); putRendition(RenditionEntity(source, workId)); cleanEmptyWorks()
    }
    @Query("SELECT 'library:' || a.id AS rowId, a.id AS assetId, a.providerId, a.itemId, a.title, a.kind, NULL AS positionMs, NULL AS durationMs, p.progression, p.updatedAt FROM library_assets a JOIN progress_records p ON p.assetId = a.id WHERE p.completed = 0 AND a.kind != 'MUSIC' UNION ALL SELECT 'history:' || h.stableKey AS rowId, NULL AS assetId, h.providerId, h.itemId, h.title, 'MOVIE' AS kind, h.positionMs, h.durationMs, CASE WHEN h.durationMs > 0 THEN 1.0 * h.positionMs / h.durationMs ELSE NULL END AS progression, h.lastPlayedAtEpochMs AS updatedAt FROM media_history h WHERE h.completed = 0 AND h.positionMs > 0 AND NOT EXISTS(SELECT 1 FROM library_assets a JOIN progress_records p ON p.assetId = a.id WHERE a.providerId = h.providerId AND a.itemId = h.itemId) UNION ALL SELECT 'music:' || a.id AS rowId, a.id AS assetId, a.providerId, a.itemId, a.title, a.kind, NULL AS positionMs, t.durationMs, NULL AS progression, s.lastPlayedAt AS updatedAt FROM music_listening s JOIN library_assets a ON a.id = s.assetId JOIN music_tracks t ON t.assetId = a.id WHERE a.kind = 'MUSIC' AND s.lastPlayedAt > 0 ORDER BY updatedAt DESC")
    fun continuing(): PagingSource<Int, ContinueRow>
    @Query("SELECT * FROM playlist_exports WHERE providerId = :provider AND playlistId = :playlist") suspend fun export(provider: String, playlist: String): PlaylistExportEntity?
    @Query("SELECT * FROM playlist_exports WHERE playlistId = :playlist ORDER BY updatedAt DESC") fun exports(playlist: String): Flow<List<PlaylistExportEntity>>
    @Upsert suspend fun putExport(value: PlaylistExportEntity)
    @Transaction suspend fun updateExport(value: PlaylistExportEntity): Boolean {
        if (export(value.providerId, value.playlistId)?.operationId != value.operationId) return false
        putExport(value)
        return true
    }
    @Query("DELETE FROM playlist_exports WHERE providerId = :provider AND playlistId = :playlist") suspend fun removeExport(provider: String, playlist: String)
    @Query("SELECT * FROM catalog_pages WHERE id = :id") suspend fun catalogPage(id: String): CatalogPageEntity?
    @Query("DELETE FROM catalog_pages WHERE id IN (SELECT id FROM catalog_pages ORDER BY updatedAt DESC LIMIT -1 OFFSET 256)") suspend fun trimCatalogPages()
    @Upsert suspend fun putCatalogPage(page: CatalogPageEntity)
    @Query("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))), 0) FROM catalog_pages") suspend fun catalogBytes(): Long
    @Query("DELETE FROM catalog_pages WHERE id = (SELECT id FROM catalog_pages ORDER BY updatedAt, id LIMIT 1)") suspend fun removeOldestCatalogPage()
    @Transaction suspend fun cacheCatalogPage(page: CatalogPageEntity) {
        putCatalogPage(page); trimCatalogPages()
        while (catalogBytes() > 32L * 1024 * 1024) removeOldestCatalogPage()
    }
}
