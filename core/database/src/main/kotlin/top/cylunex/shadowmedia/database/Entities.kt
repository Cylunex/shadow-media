package top.cylunex.shadowmedia.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_history",
    indices = [Index("providerId"), Index("lastPlayedAtEpochMs")],
)
data class MediaHistoryEntity(
    @PrimaryKey val stableKey: String,
    val providerId: String,
    val itemId: String,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val completed: Boolean = false,
    val lastPlayedAtEpochMs: Long,
)

@Entity(
    tableName = "media_favorites",
    indices = [Index("providerId"), Index("addedAtEpochMs")],
)
data class MediaFavoriteEntity(
    @PrimaryKey val stableKey: String,
    val providerId: String,
    val itemId: String,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val addedAtEpochMs: Long,
)

@Entity(tableName = "recent_searches", indices = [Index("searchedAtEpochMs")])
data class RecentSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val providerId: String? = null,
    val searchedAtEpochMs: Long,
)

@Entity(
    tableName = "playback_metrics",
    indices = [Index("providerId"), Index("itemId"), Index("startedAtEpochMs")],
)
data class PlaybackMetricEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val itemId: String,
    val playSessionId: String? = null,
    val method: String,
    val requestHost: String? = null,
    val candidateIndex: Int = 0,
    val startedAtEpochMs: Long,
    val firstFrameMs: Long? = null,
    val bufferingCount: Int = 0,
    val bufferingDurationMs: Long = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val completed: Boolean = false,
)

@Entity(tableName = "source_health")
data class SourceHealthEntity(
    @PrimaryKey val sourceKey: String,
    val providerId: String,
    val successes: Int = 0,
    val failures: Int = 0,
    val consecutiveFailures: Int = 0,
    val averageFirstFrameMs: Long? = null,
    val lastError: String? = null,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "media_moments",
    indices = [Index("providerId"), Index("itemId"), Index("createdAtEpochMs")],
)
data class MediaMomentEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val itemId: String,
    val mediaTitle: String,
    val positionMs: Long,
    val note: String = "",
    val tags: String = "",
    val imageUri: String? = null,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "feature_flags")
data class FeatureFlagEntity(
    @PrimaryKey val featureId: String,
    val enabled: Boolean,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "local_profiles", indices = [Index("lastUsedAtEpochMs")])
data class LocalProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorSeed: Long,
    val isChild: Boolean = false,
    val lastUsedAtEpochMs: Long,
)

@Entity(
    tableName = "epg_programs",
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["channelId", "startEpochMs"]),
        Index(value = ["endEpochMs"]),
    ],
)
data class EpgProgramEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val channelId: String,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val iconUrl: String? = null,
)

@Entity(
    tableName = "media_segments",
    indices = [Index(value = ["providerId", "itemId"]), Index("createdAtEpochMs")],
)
data class MediaSegmentEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val itemId: String,
    val type: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
    val source: String,
    val createdAtEpochMs: Long,
)
