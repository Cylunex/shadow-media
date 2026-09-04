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

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_assets ORDER BY addedAt DESC")
    fun assets(): Flow<List<LibraryAssetEntity>>
    @Query("SELECT * FROM library_assets WHERE id = :id")
    suspend fun asset(id: String): LibraryAssetEntity?
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
    @Transaction suspend fun removeFromShelf(id: String) {
        removeAssetOperations(id)
        removeAnnotations(id)
        removeProgress(id)
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
    @Transaction suspend fun saveAndEnqueue(progress: ContentProgressEntity, operation: SyncOperationEntity?) {
        if (asset(progress.assetId) == null) return
        putProgress(progress)
        if (operation != null) {
            removeSuperseded(operation.scope, operation.target, operation.kind)
            enqueue(operation)
        }
    }
    @Transaction suspend fun seedProgress(record: ContentProgressEntity) {
        if (asset(record.assetId) != null && progress(record.assetId) == null) putProgress(record)
    }
}
