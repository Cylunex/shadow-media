package com.shadow.media.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "novel_publications", indices = [Index("scope"), Index(value = ["scope", "workId", "versionId"])])
data class NovelPublicationEntity(
    @PrimaryKey val assetId: String, val scope: String, val workId: String, val versionId: String,
    val mappingJson: String, val packageRevision: String,
)

@Entity(
    tableName = "novel_chapter_memberships",
    primaryKeys = ["assetId", "chapterId"],
    foreignKeys = [ForeignKey(
        entity = NovelPublicationEntity::class, parentColumns = ["assetId"], childColumns = ["assetId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["scope", "workId", "versionId", "chapterId"]),
        Index(value = ["scope", "workId", "versionId", "rowOffset"])],
)
data class NovelChapterMembership(
    val assetId: String, val chapterId: String, val scope: String, val workId: String, val versionId: String, val rowOffset: Int,
)

data class NovelChapterAsset(val chapterId: String, val assetId: String)

data class NovelResumeWindow(@Embedded val publication: NovelPublicationEntity, val locatorJson: String?, val lastRead: Long?)

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
    @Query("""SELECT p.*, r.locatorJson, r.updatedAt AS lastRead FROM novel_publications p
        LEFT JOIN progress_records r ON r.assetId = p.assetId
        WHERE p.scope = :scope AND p.workId = :work AND (:version IS NULL OR p.versionId = :version)""")
    suspend fun resumeWindows(scope: String, work: String, version: String?): List<NovelResumeWindow>
    @Query("SELECT * FROM novel_publications WHERE scope = :scope AND workId = :work AND versionId = :version")
    suspend fun windows(scope: String, work: String, version: String): List<NovelPublicationEntity>
    @Upsert suspend fun putPublication(value: NovelPublicationEntity)
    @Query("""SELECT * FROM novel_publications AS p WHERE scope = :scope AND workId = :work AND versionId = :version
        AND NOT EXISTS (SELECT 1 FROM novel_chapter_memberships AS m WHERE m.assetId = p.assetId) LIMIT 32""")
    suspend fun unindexedWindows(scope: String, work: String, version: String): List<NovelPublicationEntity>
    @Query("""SELECT chapterId, assetId FROM novel_chapter_memberships WHERE scope = :scope AND workId = :work
        AND versionId = :version AND chapterId IN (:ids)""")
    suspend fun chapterAssets(scope: String, work: String, version: String, ids: List<String>): List<NovelChapterAsset>
    @Query("""SELECT chapterId, assetId FROM novel_chapter_memberships WHERE scope = :scope AND workId = :work
        AND versionId = :version AND rowOffset = :offset""")
    suspend fun chapterAssetsAt(scope: String, work: String, version: String, offset: Int): List<NovelChapterAsset>
    @Upsert suspend fun putChapterMemberships(memberships: List<NovelChapterMembership>)
    @Transaction suspend fun putIndexedPublication(value: NovelPublicationEntity, memberships: List<NovelChapterMembership>) {
        putPublication(value)
        putChapterMemberships(memberships)
    }
    @Query("SELECT * FROM novel_reading_states WHERE id = :id") suspend fun state(id: String): NovelReadingStateEntity?
    @Query("SELECT * FROM novel_reading_states WHERE id = :id") fun observe(id: String): Flow<NovelReadingStateEntity?>
    @Query("SELECT * FROM novel_reading_states WHERE scope = :scope AND (localSequence > acknowledgedSequence OR requestJson != '')")
    suspend fun pending(scope: String): List<NovelReadingStateEntity>
    @Upsert suspend fun putState(value: NovelReadingStateEntity)
}
