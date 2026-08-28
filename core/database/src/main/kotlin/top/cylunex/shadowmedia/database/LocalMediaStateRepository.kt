package top.cylunex.shadowmedia.database

import androidx.paging.Pager
import androidx.paging.PagingConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.cylunex.shadowmedia.model.FeatureId

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
}
