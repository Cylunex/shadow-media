package top.cylunex.shadowmedia.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        EpgProgramEntity::class,
    ],
    version = 2,
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
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `epg_programs` (
                        `id` TEXT NOT NULL,
                        `sourceId` TEXT NOT NULL,
                        `channelId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT,
                        `category` TEXT,
                        `startEpochMs` INTEGER NOT NULL,
                        `endEpochMs` INTEGER NOT NULL,
                        `iconUrl` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programs_sourceId` ON `epg_programs` (`sourceId`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_epg_programs_channelId_startEpochMs` " +
                        "ON `epg_programs` (`channelId`, `startEpochMs`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programs_endEpochMs` ON `epg_programs` (`endEpochMs`)")
            }
        }
    }
}
