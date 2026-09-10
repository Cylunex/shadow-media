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
        UserCollectionEntity::class, WorkEntity::class, RenditionEntity::class, PlaylistExportEntity::class, CatalogPageEntity::class,
        ResourceTaskEntity::class, CatalogScopeEntity::class, CatalogEntryEntity::class,
        AudioChapterEntity::class, MusicTrackEntity::class, MusicListeningEntity::class, MusicPlaylistEntity::class, MusicPlaylistEntryEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class ShadowMediaDatabase : RoomDatabase() {
    abstract fun dao(): ShadowMediaDao
    abstract fun libraryDao(): LibraryDao
    abstract fun libraryStateDao(): LibraryStateDao
    abstract fun resourceTaskDao(): ResourceTaskDao
    abstract fun catalogSnapshotDao(): CatalogSnapshotDao
    abstract fun chapterDao(): ChapterDao
    abstract fun musicDao(): MusicDao
    abstract fun audioQueueDao(): AudioQueueDao

    companion object {
        @Volatile private var instance: ShadowMediaDatabase? = null

        fun create(context: Context): ShadowMediaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ShadowMediaDatabase::class.java,
                "shadow-media.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { instance = it }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS user_collection (profileId TEXT NOT NULL, assetId TEXT NOT NULL, collected INTEGER NOT NULL, favorite INTEGER NOT NULL, addedAt INTEGER NOT NULL, PRIMARY KEY(profileId, assetId), FOREIGN KEY(assetId) REFERENCES library_assets(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_collection_assetId ON user_collection(assetId)")
                db.execSQL("INSERT OR IGNORE INTO user_collection SELECT 'default', a.id, NOT EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id) OR a.favorite = 1 OR EXISTS(SELECT 1 FROM music_playlist_entries e WHERE e.assetId = a.id), a.favorite, a.addedAt FROM library_assets a")
                db.execSQL("CREATE TABLE IF NOT EXISTS works (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS renditions (assetId TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, FOREIGN KEY(workId) REFERENCES works(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(assetId) REFERENCES library_assets(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_renditions_workId ON renditions(workId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS playlist_exports (providerId TEXT NOT NULL, playlistId TEXT NOT NULL, operationId TEXT NOT NULL, title TEXT NOT NULL, itemIdsJson TEXT NOT NULL, remoteId TEXT NOT NULL, phase TEXT NOT NULL, message TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(providerId, playlistId), FOREIGN KEY(playlistId) REFERENCES music_playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_exports_playlistId ON playlist_exports(playlistId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS catalog_pages (id TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS resource_tasks (assetId TEXT NOT NULL PRIMARY KEY, operationId TEXT NOT NULL, revision TEXT NOT NULL, state TEXT NOT NULL, transport TEXT NOT NULL, bytes INTEGER NOT NULL, totalBytes INTEGER NOT NULL, validator TEXT NOT NULL, unmetered INTEGER NOT NULL, charging INTEGER NOT NULL, message TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_resource_tasks_state ON resource_tasks(state)")
                db.execSQL("CREATE TABLE IF NOT EXISTS catalog_scopes (id TEXT NOT NULL PRIMARY KEY, generation TEXT NOT NULL, completed INTEGER NOT NULL, refreshedAt INTEGER NOT NULL, message TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS catalog_entries (scopeId TEXT NOT NULL, assetId TEXT NOT NULL, generation TEXT NOT NULL, present INTEGER NOT NULL, PRIMARY KEY(scopeId, assetId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_entries_assetId ON catalog_entries(assetId)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS audio_chapters (assetId TEXT NOT NULL, chapterId TEXT NOT NULL, resourceRevision TEXT NOT NULL, trackId TEXT NOT NULL, title TEXT NOT NULL, startMs INTEGER NOT NULL, endMs INTEGER, PRIMARY KEY(assetId, chapterId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS music_tracks (assetId TEXT NOT NULL PRIMARY KEY, album TEXT NOT NULL, albumKey TEXT NOT NULL, artist TEXT NOT NULL, artistKey TEXT NOT NULL, albumArtist TEXT NOT NULL, disc INTEGER NOT NULL, track INTEGER NOT NULL, durationMs INTEGER NOT NULL, folder TEXT NOT NULL, lyrics TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_music_tracks_albumKey ON music_tracks(albumKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_music_tracks_artistKey ON music_tracks(artistKey)")
                db.execSQL("CREATE TABLE IF NOT EXISTS music_listening (assetId TEXT NOT NULL PRIMARY KEY, heardMs INTEGER NOT NULL, playCount INTEGER NOT NULL, lastPlayedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS music_playlists (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS music_playlist_entries (entryId TEXT NOT NULL PRIMARY KEY, playlistId TEXT NOT NULL, assetId TEXT NOT NULL, ordinal INTEGER NOT NULL, FOREIGN KEY(playlistId) REFERENCES music_playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_music_playlist_entries_playlistId ON music_playlist_entries(playlistId)")
            }
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

        val MIGRATION_1_2 = object : Migration(1, 2) {
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
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
