package com.shadow.media.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "novel_publications", indices = [Index("scope"), Index(value = ["scope", "workId", "versionId"])])
data class NovelPublicationEntity(
    @PrimaryKey val assetId: String, val scope: String, val workId: String, val versionId: String,
    val mappingJson: String, val packageRevision: String,
)

/** A frozen request and the newest locator are independent durable slots. */
@Entity(tableName = "novel_reading_states", indices = [Index("scope")])
data class NovelReadingStateEntity(
    @PrimaryKey val id: String, val scope: String, val workId: String, val clientId: String,
    val revision: Long = 0, val localSequence: Long = 0, val acknowledgedSequence: Long = 0,
    val localPosition: String = "", val nextClientSeq: Long = 0,
    val requestJson: String = "", val requestSequence: Long = 0,
    val conflictJson: String = "", val message: String = "", val updatedAt: Long = 0,
) {
    val pending: Boolean get() = localSequence > acknowledgedSequence || requestJson.isNotEmpty()
    fun acknowledged(revision: Long) = copy(revision = revision, acknowledgedSequence = requestSequence,
        requestJson = "", requestSequence = 0, conflictJson = "", message = "")
}

@Dao interface NovelDao {
    @Query("SELECT * FROM novel_publications WHERE assetId = :id") suspend fun publication(id: String): NovelPublicationEntity?
    @Query("SELECT * FROM novel_publications WHERE scope = :scope AND workId = :work AND versionId = :version LIMIT 1")
    suspend fun publication(scope: String, work: String, version: String): NovelPublicationEntity?
    @Query("SELECT * FROM novel_publications WHERE scope = :scope") suspend fun publications(scope: String): List<NovelPublicationEntity>
    @Upsert suspend fun putPublication(value: NovelPublicationEntity)
    @Query("SELECT * FROM novel_reading_states WHERE id = :id") suspend fun state(id: String): NovelReadingStateEntity?
    @Query("SELECT * FROM novel_reading_states WHERE id = :id") fun observe(id: String): Flow<NovelReadingStateEntity?>
    @Query("SELECT * FROM novel_reading_states WHERE scope = :scope AND (localSequence > acknowledgedSequence OR requestJson != '')")
    suspend fun pending(scope: String): List<NovelReadingStateEntity>
    @Upsert suspend fun putState(value: NovelReadingStateEntity)
}
