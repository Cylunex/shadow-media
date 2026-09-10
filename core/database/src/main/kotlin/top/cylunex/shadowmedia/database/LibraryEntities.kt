package top.cylunex.shadowmedia.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "library_assets", indices = [Index("kind"), Index("providerId")])
data class LibraryAssetEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val itemId: String,
    val title: String,
    val author: String = "",
    val kind: String,
    val format: String,
    val localUri: String = "",
    val coverPath: String = "",
    val revision: String = "",
    val addedAt: Long,
    val favorite: Boolean = false,
)

@Entity(tableName = "progress_records", indices = [Index("updatedAt")])
data class ContentProgressEntity(
    @PrimaryKey val assetId: String,
    val schemaVersion: Int = 1,
    val locatorType: String,
    val locatorJson: String,
    val progression: Double? = null,
    val completed: Boolean = false,
    val updatedAt: Long,
)

@Entity(tableName = "content_annotations", indices = [Index("assetId")])
data class ContentAnnotationEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    val locatorJson: String,
    val note: String,
    val createdAt: Long,
)

@Entity(tableName = "sync_operations", indices = [Index("scope"), Index("createdAt")])
data class SyncOperationEntity(
    @PrimaryKey val id: String,
    val scope: String,
    val target: String,
    val kind: String,
    val payload: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
)

@Entity(tableName = "migration_imports")
data class MigrationImportEntity(@PrimaryKey val id: String)

@Entity(tableName = "progress_sessions")
data class ProgressSessionEntity(
    @PrimaryKey val assetId: String,
    val resourceRevision: String,
    val sessionId: String,
    val sequence: Long = 0,
) {
    fun accepts(incoming: ProgressSessionEntity, revision: String): Boolean =
        assetId == incoming.assetId && resourceRevision == revision && incoming.resourceRevision == revision &&
            sessionId == incoming.sessionId && incoming.sequence > sequence
}

data class LibraryShelfRow(@Embedded val asset: LibraryAssetEntity, val progression: Double?, val completed: Boolean?, val updatedAt: Long?)

@Dao
interface LibraryDao {
    @Query("SELECT a.*, p.progression, p.completed, p.updatedAt FROM library_assets a LEFT JOIN progress_records p ON p.assetId = a.id WHERE a.kind IN (:kinds) AND (:favorite = 0 OR a.favorite = 1) AND (:unfinished = 0 OR p.completed IS NULL OR p.completed = 0) AND (a.title LIKE :query ESCAPE '\\' OR a.author LIKE :query ESCAPE '\\') ORDER BY CASE WHEN :byName THEN a.title END COLLATE NOCASE, a.addedAt DESC, a.id")
    fun shelf(kinds: List<String>, query: String, favorite: Boolean, unfinished: Boolean, byName: Boolean): androidx.paging.PagingSource<Int, LibraryShelfRow>
    @Query("SELECT a.*, p.progression, p.completed, p.updatedAt FROM library_assets a JOIN progress_records p ON p.assetId = a.id WHERE a.kind IN (:kinds) AND p.completed = 0 ORDER BY p.updatedAt DESC LIMIT 1")
    fun continuing(kinds: List<String>): Flow<LibraryShelfRow?>
    @Query("SELECT * FROM library_assets WHERE (:kind = '' OR kind = :kind) AND (title LIKE :query ESCAPE '\\' OR author LIKE :query ESCAPE '\\') ORDER BY addedAt DESC, id LIMIT :limit OFFSET :offset")
    suspend fun searchPage(kind: String, query: String, offset: Int, limit: Int): List<LibraryAssetEntity>
    @Query("SELECT COUNT(*) FROM library_assets WHERE (:kind = '' OR kind = :kind) AND (title LIKE :query ESCAPE '\\' OR author LIKE :query ESCAPE '\\')")
    suspend fun searchCount(kind: String, query: String): Int

    @Query("SELECT * FROM library_assets ORDER BY addedAt DESC")
    fun assets(): Flow<List<LibraryAssetEntity>>
    @Query("SELECT * FROM library_assets WHERE id = :id")
    suspend fun asset(id: String): LibraryAssetEntity?
    @Query("SELECT * FROM library_assets WHERE id IN (:ids)")
    suspend fun assetsByIds(ids: List<String>): List<LibraryAssetEntity>
    @Upsert suspend fun putAsset(asset: LibraryAssetEntity)
    @Query("UPDATE library_assets SET favorite = :favorite WHERE id = :id")
    suspend fun favorite(id: String, favorite: Boolean)
    @Query("UPDATE library_assets SET coverPath = :path WHERE id = :id AND coverPath = ''")
    suspend fun fillCover(id: String, path: String): Int
    @Query("DELETE FROM library_assets WHERE id = :id")
    suspend fun removeAsset(id: String)
    @Query("SELECT * FROM progress_records")
    fun progress(): Flow<List<ContentProgressEntity>>
    @Query("SELECT * FROM progress_records WHERE assetId = :id")
    suspend fun progress(id: String): ContentProgressEntity?
    @Upsert suspend fun putProgress(progress: ContentProgressEntity)
    @Query("SELECT * FROM content_annotations WHERE assetId = :id ORDER BY createdAt DESC")
    fun annotations(id: String): Flow<List<ContentAnnotationEntity>>
    @Upsert suspend fun putAnnotation(annotation: ContentAnnotationEntity)
    @Query("DELETE FROM content_annotations WHERE id = :id")
    suspend fun removeAnnotation(id: String)
    @Query("DELETE FROM content_annotations WHERE assetId = :id")
    suspend fun removeAnnotations(id: String)
    @Query("DELETE FROM progress_records WHERE assetId = :id")
    suspend fun removeProgress(id: String)
    @Query("SELECT * FROM progress_sessions WHERE assetId = :id")
    suspend fun progressSession(id: String): ProgressSessionEntity?
    @Upsert suspend fun putProgressSession(session: ProgressSessionEntity)
    @Query("DELETE FROM progress_sessions WHERE assetId = :id")
    suspend fun removeProgressSession(id: String)
    @Transaction suspend fun beginProgressSession(session: ProgressSessionEntity): Boolean {
        if (asset(session.assetId)?.revision != session.resourceRevision) return false
        putProgressSession(session)
        return true
    }
    @Transaction suspend fun resetProgress(id: String) {
        removeProgressSession(id)
        removeProgress(id)
    }
    @Transaction suspend fun installAsset(asset: LibraryAssetEntity) {
        val old = this.asset(asset.id)
        if (old != null && old.revision != asset.revision) {
            resetProgress(asset.id)
            removeAssetOperations(asset.id)
        }
        putAsset(asset)
    }
    @Transaction suspend fun removeFromShelf(id: String) {
        removeAssetOperations(id)
        removeAnnotations(id)
        resetProgress(id)
        removeAsset(id)
    }
    @Query("DELETE FROM sync_operations WHERE target = :id")
    suspend fun removeAssetOperations(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(operation: SyncOperationEntity)
    @Transaction suspend fun enqueueCoalesced(operation: SyncOperationEntity, coalesce: Boolean) {
        if (coalesce) removeSuperseded(operation.scope, operation.target, operation.kind)
        enqueue(operation)
    }
    @Query("SELECT * FROM sync_operations WHERE scope = :scope ORDER BY createdAt, id")
    suspend fun pending(scope: String): List<SyncOperationEntity>
    @Query("SELECT COUNT(*) FROM sync_operations")
    fun pendingCount(): Flow<Int>
    @Query("DELETE FROM sync_operations WHERE id = :id")
    suspend fun acknowledge(id: String)
    @Query("DELETE FROM sync_operations WHERE scope = :scope AND target = :target AND kind = :kind")
    suspend fun removeSuperseded(scope: String, target: String, kind: String)
    @Query("DELETE FROM sync_operations WHERE scope = :scope")
    suspend fun discardScope(scope: String)
    @Query("UPDATE sync_operations SET attempts = attempts + 1, nextAttemptAt = :next WHERE id = :id")
    suspend fun retry(id: String, next: Long)
    @Query("SELECT EXISTS(SELECT 1 FROM migration_imports WHERE id = :id)")
    suspend fun imported(id: String): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markImported(record: MigrationImportEntity)
    @Transaction suspend fun importOnce(id: String, operations: List<SyncOperationEntity>) {
        if (imported(id)) return
        operations.forEach { enqueue(it) }
        markImported(MigrationImportEntity(id))
    }
    @Transaction suspend fun saveAndEnqueue(progress: ContentProgressEntity, operation: SyncOperationEntity?, session: ProgressSessionEntity): Boolean {
        val asset = asset(progress.assetId) ?: return false
        if (progressSession(progress.assetId)?.accepts(session, asset.revision) != true) return false
        putProgress(progress)
        putProgressSession(session)
        if (operation != null) {
            removeSuperseded(operation.scope, operation.target, operation.kind)
            enqueue(operation)
        }
        return true
    }
    @Transaction suspend fun seedProgress(record: ContentProgressEntity) {
        if (asset(record.assetId) != null && progress(record.assetId) == null) putProgress(record)
    }
}
