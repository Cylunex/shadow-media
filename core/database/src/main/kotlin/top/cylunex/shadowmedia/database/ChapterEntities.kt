package top.cylunex.shadowmedia.database

import androidx.room.*

@Entity(tableName = "audio_chapters", primaryKeys = ["assetId", "chapterId"])
data class AudioChapterEntity(val assetId: String, val chapterId: String, val resourceRevision: String,
    val trackId: String, val title: String, val startMs: Long, val endMs: Long?)
@Dao interface ChapterDao {
    @Query("SELECT * FROM audio_chapters WHERE assetId = :id AND resourceRevision = :revision ORDER BY startMs")
    suspend fun chapters(id: String, revision: String): List<AudioChapterEntity>
    @Query("DELETE FROM audio_chapters WHERE assetId = :id") suspend fun delete(id: String)
    @Insert suspend fun insert(chapters: List<AudioChapterEntity>)
    @Transaction suspend fun replace(id: String, chapters: List<AudioChapterEntity>) { delete(id); insert(chapters) }
}
