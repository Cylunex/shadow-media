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
        MediaSegmentEntity::class,
        LibraryAssetEntity::class,
        ContentProgressEntity::class,
        ContentAnnotationEntity::class,
        SyncOperationEntity::class,
        MigrationImportEntity::class,
        ProgressSessionEntity::class,
        AudioQueueEntity::class,
        AudioQueueEntryEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class ShadowMediaDatabase : RoomDatabase() {
    abstract fun dao(): ShadowMediaDao
    abstract fun libraryDao(): LibraryDao
    abstract fun audioQueueDao(): AudioQueueDao

    companion object {
        @Volatile private var instance: ShadowMediaDatabase? = null

        fun create(context: Context): ShadowMediaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ShadowMediaDatabase::class.java,
                "shadow-media.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS progress_sessions (assetId TEXT NOT NULL PRIMARY KEY, resourceRevision TEXT NOT NULL, sessionId TEXT NOT NULL, sequence INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS audio_queues (id TEXT NOT NULL PRIMARY KEY, currentEntryId TEXT, positionMs INTEGER NOT NULL, speed REAL NOT NULL, repeatMode INTEGER NOT NULL, shuffleEnabled INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS audio_queue_entries (entryId TEXT NOT NULL PRIMARY KEY, queueId TEXT NOT NULL, assetId TEXT NOT NULL, resourceRevision TEXT NOT NULL, ordinal INTEGER NOT NULL, shuffleOrdinal INTEGER NOT NULL, FOREIGN KEY(queueId) REFERENCES audio_queues(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audio_queue_entries_queueId ON audio_queue_entries(queueId)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS library_assets (id TEXT NOT NULL PRIMARY KEY, providerId TEXT NOT NULL, itemId TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, kind TEXT NOT NULL, format TEXT NOT NULL, localUri TEXT NOT NULL, coverPath TEXT NOT NULL, revision TEXT NOT NULL, addedAt INTEGER NOT NULL, favorite INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_library_assets_kind ON library_assets(kind)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_library_assets_providerId ON library_assets(providerId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS progress_records (assetId TEXT NOT NULL PRIMARY KEY, schemaVersion INTEGER NOT NULL, locatorType TEXT NOT NULL, locatorJson TEXT NOT NULL, progression REAL, completed INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_progress_records_updatedAt ON progress_records(updatedAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS content_annotations (id TEXT NOT NULL PRIMARY KEY, assetId TEXT NOT NULL, locatorJson TEXT NOT NULL, note TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_content_annotations_assetId ON content_annotations(assetId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_operations (id TEXT NOT NULL PRIMARY KEY, scope TEXT NOT NULL, target TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, createdAt INTEGER NOT NULL, attempts INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_operations_scope ON sync_operations(scope)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_operations_createdAt ON sync_operations(createdAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS migration_imports (id TEXT NOT NULL PRIMARY KEY)")
            }
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_segments` (
                        `id` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `itemId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `startMs` INTEGER NOT NULL,
                        `endMs` INTEGER NOT NULL,
                        `confidence` REAL NOT NULL,
                        `source` TEXT NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_segments_providerId_itemId` " +
                        "ON `media_segments` (`providerId`, `itemId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_segments_createdAtEpochMs` " +
                        "ON `media_segments` (`createdAtEpochMs`)"
                )
            }
        }
    }
}
