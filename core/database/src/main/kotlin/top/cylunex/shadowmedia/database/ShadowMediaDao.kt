package top.cylunex.shadowmedia.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ShadowMediaDao {
    @Upsert
    suspend fun upsertHistory(item: MediaHistoryEntity)

    @Query("SELECT * FROM media_history ORDER BY lastPlayedAtEpochMs DESC")
    fun historyPagingSource(): PagingSource<Int, MediaHistoryEntity>

    @Query("SELECT * FROM media_history ORDER BY lastPlayedAtEpochMs DESC LIMIT :limit")
    fun observeRecentHistory(limit: Int): Flow<List<MediaHistoryEntity>>

    @Query("SELECT * FROM media_history WHERE stableKey = :stableKey LIMIT 1")
    suspend fun history(stableKey: String): MediaHistoryEntity?

    @Query("DELETE FROM media_history WHERE stableKey = :stableKey")
    suspend fun removeHistory(stableKey: String)

    @Upsert
    suspend fun upsertFavorite(item: MediaFavoriteEntity)

    @Query("DELETE FROM media_favorites WHERE stableKey = :stableKey")
    suspend fun removeFavorite(stableKey: String)

    @Query("SELECT EXISTS(SELECT 1 FROM media_favorites WHERE stableKey = :stableKey)")
    fun observeFavorite(stableKey: String): Flow<Boolean>

    @Query("SELECT * FROM media_favorites ORDER BY addedAtEpochMs DESC")
    fun favoritesPagingSource(): PagingSource<Int, MediaFavoriteEntity>

    @Query("SELECT stableKey FROM media_favorites WHERE providerId = :providerId")
    suspend fun favoriteKeys(providerId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addRecentSearch(search: RecentSearchEntity)

    @Query("SELECT * FROM recent_searches ORDER BY searchedAtEpochMs DESC LIMIT :limit")
    fun observeRecentSearches(limit: Int): Flow<List<RecentSearchEntity>>

    @Query("DELETE FROM recent_searches")
    suspend fun clearRecentSearches()

    @Upsert
    suspend fun upsertPlaybackMetric(metric: PlaybackMetricEntity)

    @Query("SELECT * FROM playback_metrics ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observePlaybackMetrics(limit: Int): Flow<List<PlaybackMetricEntity>>

    @Query("SELECT * FROM playback_metrics WHERE id = :id")
    suspend fun playbackMetric(id: String): PlaybackMetricEntity?

    @Query(
        """
        UPDATE playback_metrics SET
            firstFrameMs = :firstFrameMs,
            bufferingCount = :bufferingCount,
            bufferingDurationMs = :bufferingDurationMs,
            errorCode = :errorCode,
            errorMessage = :errorMessage,
            completed = :completed
        WHERE id = :id
        """
    )
    suspend fun finishPlaybackMetric(
        id: String,
        firstFrameMs: Long?,
        bufferingCount: Int,
        bufferingDurationMs: Long,
        errorCode: String?,
        errorMessage: String?,
        completed: Boolean,
    )

    @Upsert
    suspend fun upsertSourceHealth(health: SourceHealthEntity)

    @Query("SELECT * FROM source_health WHERE sourceKey = :sourceKey")
    suspend fun sourceHealth(sourceKey: String): SourceHealthEntity?

    @Query("SELECT * FROM source_health ORDER BY consecutiveFailures DESC, updatedAtEpochMs DESC")
    fun observeSourceHealth(): Flow<List<SourceHealthEntity>>

    @Upsert
    suspend fun upsertMoment(moment: MediaMomentEntity)

    @Query("DELETE FROM media_moments WHERE id = :id")
    suspend fun removeMoment(id: String)

    @Query("SELECT * FROM media_moments ORDER BY createdAtEpochMs DESC")
    fun momentsPagingSource(): PagingSource<Int, MediaMomentEntity>

    @Query("SELECT * FROM media_moments ORDER BY createdAtEpochMs DESC LIMIT :limit")
    fun observeRecentMoments(limit: Int): Flow<List<MediaMomentEntity>>

    @Upsert
    suspend fun upsertSegment(segment: MediaSegmentEntity)

    @Query("DELETE FROM media_segments WHERE id = :id")
    suspend fun removeSegment(id: String)

    @Query(
        "SELECT * FROM media_segments WHERE providerId = :providerId AND itemId = :itemId ORDER BY startMs"
    )
    fun observeSegments(providerId: String, itemId: String): Flow<List<MediaSegmentEntity>>

    @Upsert
    suspend fun upsertFeatureFlag(flag: FeatureFlagEntity)

    @Query("SELECT * FROM feature_flags")
    fun observeFeatureFlags(): Flow<List<FeatureFlagEntity>>

    @Upsert
    suspend fun upsertProfile(profile: LocalProfileEntity)

    @Query("SELECT * FROM local_profiles ORDER BY lastUsedAtEpochMs DESC")
    fun observeProfiles(): Flow<List<LocalProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpgPrograms(programs: List<EpgProgramEntity>)

    @Query("DELETE FROM epg_programs WHERE sourceId = :sourceId")
    suspend fun deleteEpgSource(sourceId: String)

    @Query("DELETE FROM epg_programs WHERE endEpochMs < :cutoffEpochMs")
    suspend fun pruneEpg(cutoffEpochMs: Long)

    @Query(
        """
        SELECT * FROM epg_programs
        WHERE sourceId = :sourceId AND endEpochMs >= :fromEpochMs AND startEpochMs <= :toEpochMs
        ORDER BY channelId, startEpochMs
        """
    )
    fun observeEpg(
        sourceId: String,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): Flow<List<EpgProgramEntity>>

    @Transaction
    suspend fun replaceEpg(sourceId: String, programs: List<EpgProgramEntity>) {
        deleteEpgSource(sourceId)
        programs.chunked(1_000).forEach { insertEpgPrograms(it) }
    }
}
