package top.cylunex.shadowmedia.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MediaHistoryEntity::class,
        MediaFavoriteEntity::class,
        RecentSearchEntity::class,
        PlaybackMetricEntity::class,
        SourceHealthEntity::class,
        MediaMomentEntity::class,
        FeatureFlagEntity::class,
        LocalProfileEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ShadowMediaDatabase : RoomDatabase() {
    abstract fun dao(): ShadowMediaDao

    companion object {
        @Volatile private var instance: ShadowMediaDatabase? = null

        fun create(context: Context): ShadowMediaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ShadowMediaDatabase::class.java,
                "shadow-media.db",
            ).build().also { instance = it }
        }
    }
}
