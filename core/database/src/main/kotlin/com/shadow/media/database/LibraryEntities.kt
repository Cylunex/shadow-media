package com.shadow.media.database

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

data class AssetAvailability(val localCopy: Boolean, val state: String, val currentRevision: Boolean)

data class LibraryShelfRow(@Embedded val asset: LibraryAssetEntity, val progression: Double?, val completed: Boolean?, val updatedAt: Long?)

/** Transport windows keep independent locators/files, but a Novel work has one shelf card per account. */
internal const val LOGICAL_LIBRARY_ASSETS = """
WITH shelf_assets AS (
 SELECT a.* FROM library_assets a
 WHERE COALESCE((SELECT c.collected FROM user_collection c WHERE c.assetId = a.id AND c.profileId = 'default'), 1) = 1
 AND a.id = COALESCE((
   SELECT b.id FROM novel_publications n
   JOIN novel_publications other ON other.scope = n.scope AND other.workId = n.workId
   JOIN library_assets b ON b.id = other.assetId
   WHERE n.assetId = a.id
     AND COALESCE((SELECT c.collected FROM user_collection c WHERE c.assetId = b.id AND c.profileId = 'default'), 1) = 1
   ORDER BY b.favorite DESC, b.addedAt, b.id LIMIT 1
 ), a.id)
)
"""

internal const val LOGICAL_LIBRARY_PROGRESS = LOGICAL_LIBRARY_ASSETS + """
, shelf_rows AS (
 SELECT a.*, p.progression, p.completed, p.updatedAt
 FROM shelf_assets a LEFT JOIN progress_records p ON p.assetId = COALESCE((
   SELECT recent.assetId FROM novel_publications n
   JOIN novel_publications other ON other.scope = n.scope AND other.workId = n.workId
   JOIN library_assets b ON b.id = other.assetId
   JOIN progress_records recent ON recent.assetId = b.id
   WHERE n.assetId = a.id ORDER BY recent.updatedAt DESC, recent.assetId LIMIT 1
 ), a.id)
)
"""

@Dao
interface LibraryDao {
    @Query("SELECT a.localUri != '' AS localCopy, COALESCE(t.state, '') AS state, COALESCE(t.revision = a.revision, 0) AS currentRevision FROM library_assets a LEFT JOIN resource_tasks t ON t.assetId = a.id WHERE a.providerId = :provider AND a.itemId = :item LIMIT 1")
    fun availability(provider: String, item: String): Flow<AssetAvailability?>
    @Query(LOGICAL_LIBRARY_PROGRESS + "SELECT * FROM shelf_rows WHERE kind IN (:kinds) AND (:favorite = 0 OR favorite = 1) AND (:unfinished = 0 OR completed IS NULL OR completed = 0) AND (title LIKE :query ESCAPE '\\' OR author LIKE :query ESCAPE '\\') ORDER BY CASE WHEN :byName THEN title END COLLATE NOCASE, addedAt DESC, id")
    fun shelf(kinds: List<String>, query: String, favorite: Boolean, unfinished: Boolean, byName: Boolean): androidx.paging.PagingSource<Int, LibraryShelfRow>
    @Query(LOGICAL_LIBRARY_PROGRESS + "SELECT * FROM shelf_rows WHERE kind IN (:kinds) AND completed = 0 ORDER BY updatedAt DESC, id LIMIT 1")
    fun continuing(kinds: List<String>): Flow<LibraryShelfRow?>
    @Query(LOGICAL_LIBRARY_ASSETS + "SELECT * FROM shelf_assets WHERE (:kind = '' OR kind = :kind) AND (title LIKE :query ESCAPE '\\' OR author LIKE :query ESCAPE '\\') ORDER BY addedAt DESC, id LIMIT :limit OFFSET :offset")
    suspend fun searchPage(kind: String, query: String, offset: Int, limit: Int): List<LibraryAssetEntity>
    @Query(LOGICAL_LIBRARY_ASSETS + "SELECT COUNT(*) FROM shelf_assets WHERE (:kind = '' OR kind = :kind) AND (title LIKE :query ESCAPE '\\' OR author LIKE :query ESCAPE '\\')")
    suspend fun searchCount(kind: String, query: String): Int

    @Query("SELECT * FROM library_assets ORDER BY addedAt DESC")
    fun assets(): Flow<List<LibraryAssetEntity>>
    @Query("SELECT * FROM library_assets WHERE id = :id")
    suspend fun asset(id: String): LibraryAssetEntity?
    @Query("SELECT * FROM library_assets WHERE providerId = :providerId AND itemId = :itemId LIMIT 1")
    suspend fun assetForKey(providerId: String, itemId: String): LibraryAssetEntity?
    @Query("SELECT * FROM library_assets WHERE id IN (:ids)")
    suspend fun assetsByIds(ids: List<String>): List<LibraryAssetEntity>
    @Query("SELECT * FROM library_assets WHERE providerId LIKE 'catalog:OPDS:%' AND itemId NOT LIKE 'opds-ref:v1:%'") suspend fun legacyOpdsAssets(): List<LibraryAssetEntity>
    @Query("UPDATE library_assets SET itemId = :value WHERE id = :id AND itemId = :expected") suspend fun updateItemId(id: String, expected: String, value: String)
    @Upsert suspend fun putAsset(asset: LibraryAssetEntity)
    @Query("UPDATE library_assets SET localUri = '' WHERE id = :id AND localUri = :expectedUri")
    suspend fun clearLocalCopy(id: String, expectedUri: String)
    @Query("UPDATE library_assets SET favorite = :favorite WHERE id = :id")
    suspend fun legacyFavorite(id: String, favorite: Boolean)
    @Query("INSERT OR IGNORE INTO user_collection SELECT 'default', id, 1, favorite, addedAt FROM library_assets WHERE id = :id") suspend fun seedPersonalState(id: String)
    @Query("UPDATE user_collection SET collected = 0 WHERE assetId = :id AND profileId = 'default' AND favorite = 0") suspend fun hideCacheWindow(id: String)
    @Query("UPDATE user_collection SET favorite = :favorite, collected = 1 WHERE assetId = :id AND profileId = 'default'") suspend fun userFavorite(id: String, favorite: Boolean)
    @Query("SELECT other.assetId FROM novel_publications n JOIN novel_publications other ON other.scope = n.scope AND other.workId = n.workId JOIN library_assets a ON a.id = other.assetId WHERE n.assetId = :id")
    suspend fun novelWorkAssets(id: String): List<String>
    @Query("UPDATE user_collection SET favorite = :favorite WHERE profileId = 'default' AND assetId = :id")
    suspend fun mirrorWindowFavorite(id: String, favorite: Boolean)
    @Transaction suspend fun favorite(id: String, favorite: Boolean) {
        val ids = novelWorkAssets(id).ifEmpty { listOf(id) }
        for (window in ids) {
            seedPersonalState(window); mirrorWindowFavorite(window, favorite); legacyFavorite(window, favorite)
        }
        userFavorite(id, favorite)
    }
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
    @Query("SELECT * FROM content_annotations WHERE assetId IN (:ids) ORDER BY createdAt DESC")
    fun annotationsForAssets(ids: List<String>): Flow<List<ContentAnnotationEntity>>
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
    @Query("UPDATE audio_chapters SET resourceRevision = :revision WHERE assetId = :id AND resourceRevision = ''") suspend fun bindInitialChapterRevision(id: String, revision: String)
    @Transaction suspend fun installAsset(asset: LibraryAssetEntity) {
        val old = this.asset(asset.id)
        if (old != null && old.revision != asset.revision) {
            if (old.providerId != "local" && old.localUri.isBlank() && old.revision.isBlank()) {
                // The first verified copy establishes a byte hash, not a different edition.
                removeProgressSession(asset.id)
                bindInitialChapterRevision(asset.id, asset.revision)
            } else {
                resetProgress(asset.id)
                removeAssetOperations(asset.id)
            }
        }
        putAsset(asset.copy(favorite = old?.favorite ?: asset.favorite))
    }
    @Query("DELETE FROM novel_reading_states WHERE EXISTS (SELECT 1 FROM novel_publications n WHERE n.assetId = :id AND n.scope = novel_reading_states.scope AND n.workId = novel_reading_states.workId)")
    suspend fun removeNovelReadingState(id: String)
    @Query("DELETE FROM novel_publications WHERE assetId = :id") suspend fun removeNovelPublication(id: String)
    @Transaction suspend fun removeFromShelf(id: String) {
        val ids = novelWorkAssets(id).ifEmpty { listOf(id) }
        removeNovelReadingState(id)
        for (window in ids) {
            removeAssetOperations(window); removeAnnotations(window); resetProgress(window)
            removeNovelPublication(window); removeAsset(window)
        }
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
