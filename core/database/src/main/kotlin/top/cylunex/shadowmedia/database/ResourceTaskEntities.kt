package top.cylunex.shadowmedia.database

import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** User intent survives process death. No resolved URL, cookie or request headers are stored. */
@Entity(tableName = "resource_tasks", indices = [Index("state")])
data class ResourceTaskEntity(@PrimaryKey val assetId: String, val operationId: String, val revision: String,
    val state: String, val transport: String, val bytes: Long = 0, val totalBytes: Long = -1,
    val validator: String = "", val unmetered: Boolean = true, val charging: Boolean = false, val message: String = "", val updatedAt: Long)
@Dao interface ResourceTaskDao {
    @Query("SELECT * FROM resource_tasks ORDER BY updatedAt DESC") fun observe(): Flow<List<ResourceTaskEntity>>
    @Query("SELECT * FROM resource_tasks ORDER BY updatedAt DESC") fun page(): PagingSource<Int, ResourceTaskEntity>
    @Query("SELECT * FROM resource_tasks WHERE assetId = :id") suspend fun task(id: String): ResourceTaskEntity?
    @Query("SELECT * FROM resource_tasks WHERE transport = 'MEDIA3' AND state = 'OFFLINE_PENDING'") suspend fun activeMedia(): List<ResourceTaskEntity>
    @Query("UPDATE resource_tasks SET bytes = :bytes, totalBytes = :total WHERE assetId = :id AND operationId = :operation AND state = 'OFFLINE_PENDING'")
    suspend fun progress(id: String, operation: String, bytes: Long, total: Long)
    @Query("UPDATE resource_tasks SET validator = :validator WHERE assetId = :id AND operationId = :operation") suspend fun validator(id: String, operation: String, validator: String)
    @Upsert suspend fun put(task: ResourceTaskEntity)
    @Query("UPDATE resource_tasks SET state = :state, bytes = :bytes, totalBytes = :total, message = :message, updatedAt = :now WHERE assetId = :id AND operationId = :operation")
    suspend fun update(id: String, operation: String, state: String, bytes: Long, total: Long, message: String, now: Long): Int
    @Query("DELETE FROM resource_tasks WHERE assetId = :id AND operationId = :operation") suspend fun delete(id: String, operation: String)
}

@Entity(tableName = "catalog_scopes")
data class CatalogScopeEntity(@PrimaryKey val id: String, val generation: String, val completed: Boolean,
    val refreshedAt: Long, val message: String = "")
@Entity(tableName = "catalog_entries", primaryKeys = ["scopeId", "assetId"], indices = [Index("assetId")])
data class CatalogEntryEntity(val scopeId: String, val assetId: String, val generation: String, val present: Boolean = true)
@Dao interface CatalogSnapshotDao {
    @Query("SELECT * FROM catalog_scopes ORDER BY refreshedAt DESC") fun scopes(): Flow<List<CatalogScopeEntity>>
    @Query("SELECT * FROM catalog_scopes WHERE id = :id") fun observe(id: String): Flow<CatalogScopeEntity?>
    @Query("SELECT * FROM catalog_scopes WHERE id = :id") suspend fun scope(id: String): CatalogScopeEntity?
    @Upsert suspend fun put(scope: CatalogScopeEntity)
    @Upsert suspend fun putEntries(entries: List<CatalogEntryEntity>)
    @Query("UPDATE catalog_entries SET present = 0 WHERE scopeId = :id AND generation != :generation") suspend fun tombstoneOld(id: String, generation: String)
    @Transaction suspend fun begin(id: String, generation: String) { put(CatalogScopeEntity(id, generation, false, scope(id)?.refreshedAt ?: 0)) }
    @Transaction suspend fun append(id: String, generation: String, assetIds: List<String>) {
        if (scope(id)?.generation != generation) return
        putEntries(assetIds.map { CatalogEntryEntity(id, it, generation) })
    }
    @Transaction suspend fun finish(id: String, generation: String, now: Long, complete: Boolean, message: String = "") {
        if (scope(id)?.generation != generation) return
        if (complete) tombstoneOld(id, generation)
        put(CatalogScopeEntity(id, generation, complete, if (complete) now else scope(id)?.refreshedAt ?: 0, message))
    }
}
