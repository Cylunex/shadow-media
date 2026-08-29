package top.cylunex.shadowmedia.database

import androidx.paging.Pager
import androidx.paging.PagingConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.cylunex.shadowmedia.model.FeatureId
import top.cylunex.shadowmedia.model.LiveProgram
import top.cylunex.shadowmedia.model.MediaSegment
import top.cylunex.shadowmedia.model.SegmentSource
import top.cylunex.shadowmedia.model.SegmentType

class LocalMediaStateRepository(private val dao: ShadowMediaDao) {
    val history = Pager(PagingConfig(pageSize = 40, prefetchDistance = 12)) {
        dao.historyPagingSource()
    }.flow

    val favorites = Pager(PagingConfig(pageSize = 40, prefetchDistance = 12)) {
        dao.favoritesPagingSource()
    }.flow

    val moments = Pager(PagingConfig(pageSize = 40, prefetchDistance = 12)) {
        dao.momentsPagingSource()
    }.flow

    fun recentHistory(limit: Int = 30): Flow<List<MediaHistoryEntity>> = dao.observeRecentHistory(limit)

    fun recentSearches(limit: Int = 12): Flow<List<RecentSearchEntity>> = dao.observeRecentSearches(limit)

    fun featureFlags(): Flow<Map<FeatureId, Boolean>> = dao.observeFeatureFlags().map { stored ->
        val overrides = stored.mapNotNull { entity ->
            runCatching { FeatureId.valueOf(entity.featureId) }.getOrNull()?.let { it to entity.enabled }
        }.toMap()
        FeatureId.entries.associateWith { overrides[it] ?: it.defaultEnabled }
    }

    suspend fun setFeatureEnabled(feature: FeatureId, enabled: Boolean) {
        dao.upsertFeatureFlag(
            FeatureFlagEntity(feature.name, enabled, System.currentTimeMillis())
        )
    }

    suspend fun recordHistory(item: MediaHistoryEntity) = dao.upsertHistory(item)

    suspend fun history(stableKey: String): MediaHistoryEntity? = dao.history(stableKey)

    suspend fun favoriteKeys(providerId: String): Set<String> = dao.favoriteKeys(providerId).toSet()

    suspend fun addFavorite(item: MediaFavoriteEntity) = dao.upsertFavorite(item)

    suspend fun removeFavorite(stableKey: String) = dao.removeFavorite(stableKey)

    suspend fun addSearch(query: String, providerId: String? = null) {
        val normalized = query.trim()
        if (normalized.isNotEmpty()) {
            dao.addRecentSearch(
                RecentSearchEntity(
                    query = normalized,
                    providerId = providerId,
                    searchedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun addMoment(moment: MediaMomentEntity) = dao.upsertMoment(moment)

    suspend fun removeMoment(id: String) = dao.removeMoment(id)

    fun recentMoments(limit: Int = 100): Flow<List<MediaMomentEntity>> = dao.observeRecentMoments(limit)

    fun playbackMetrics(limit: Int = 200): Flow<List<PlaybackMetricEntity>> = dao.observePlaybackMetrics(limit)

    fun sourceHealth(): Flow<List<SourceHealthEntity>> = dao.observeSourceHealth()

    fun segments(providerId: String, itemId: String): Flow<List<MediaSegment>> =
        dao.observeSegments(providerId, itemId).map { rows ->
            rows.mapNotNull { row ->
                val type = runCatching { SegmentType.valueOf(row.type) }.getOrNull() ?: return@mapNotNull null
                val source = runCatching { SegmentSource.valueOf(row.source) }.getOrNull() ?: return@mapNotNull null
                MediaSegment(row.id, row.providerId, row.itemId, type, row.startMs, row.endMs, row.confidence, source)
            }
        }

    suspend fun addSegment(segment: MediaSegment) = dao.upsertSegment(
        MediaSegmentEntity(
            segment.id,
            segment.providerId,
            segment.itemId,
            segment.type.name,
            segment.startMs,
            segment.endMs,
            segment.confidence,
            segment.source.name,
            System.currentTimeMillis(),
        )
    )

    suspend fun removeSegment(id: String) = dao.removeSegment(id)

    suspend fun upsertPlaybackMetric(metric: PlaybackMetricEntity) = dao.upsertPlaybackMetric(metric)

    suspend fun finishPlaybackMetric(
        id: String,
        firstFrameMs: Long?,
        bufferingCount: Int,
        bufferingDurationMs: Long,
        errorCode: String?,
        errorMessage: String?,
        completed: Boolean,
    ) = dao.finishPlaybackMetric(
        id,
        firstFrameMs,
        bufferingCount,
        bufferingDurationMs,
        errorCode,
        errorMessage,
        completed,
    )

    suspend fun sourceHealth(sourceKey: String): SourceHealthEntity? = dao.sourceHealth(sourceKey)

    suspend fun upsertSourceHealth(health: SourceHealthEntity) = dao.upsertSourceHealth(health)

    fun epg(sourceId: String, fromEpochMs: Long, toEpochMs: Long): Flow<List<LiveProgram>> =
        dao.observeEpg(sourceId, fromEpochMs, toEpochMs).map { rows ->
            rows.map { row ->
                LiveProgram(
                    sourceId = row.sourceId,
                    channelId = row.channelId,
                    title = row.title,
                    description = row.description,
                    category = row.category,
                    startEpochMs = row.startEpochMs,
                    endEpochMs = row.endEpochMs,
                    iconUrl = row.iconUrl,
                )
            }
        }

    suspend fun replaceEpg(sourceId: String, programs: List<LiveProgram>) {
        dao.replaceEpg(
            sourceId,
            programs.map { program ->
                EpgProgramEntity(
                    id = "${program.sourceId}:${program.channelId}:${program.startEpochMs}",
                    sourceId = program.sourceId,
                    channelId = program.channelId,
                    title = program.title,
                    description = program.description,
                    category = program.category,
                    startEpochMs = program.startEpochMs,
                    endEpochMs = program.endEpochMs,
                    iconUrl = program.iconUrl,
                )
            },
        )
        dao.pruneEpg(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1_000)
    }
}
