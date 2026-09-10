package top.cylunex.shadowmedia.database

import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Tags are replaceable catalog data; playlists and listening statistics are user data. */
@Entity(tableName = "music_tracks", indices = [Index("albumKey"), Index("artistKey")])
data class MusicTrackEntity(
    @PrimaryKey val assetId: String,
    val album: String = "", val albumKey: String = "", val artist: String = "", val artistKey: String = "",
    val albumArtist: String = "", val disc: Int = 0, val track: Int = 0,
    val durationMs: Long = 0, val folder: String = "", val lyrics: String = "",
)
@Entity(tableName = "music_listening")
data class MusicListeningEntity(@PrimaryKey val assetId: String, val heardMs: Long = 0, val playCount: Long = 0, val lastPlayedAt: Long = 0)
@Entity(tableName = "music_playlists")
data class MusicPlaylistEntity(@PrimaryKey val id: String, val title: String, val createdAt: Long)
@Entity(tableName = "music_playlist_entries", indices = [Index("playlistId")], foreignKeys = [ForeignKey(
    entity = MusicPlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE)])
data class MusicPlaylistEntryEntity(@PrimaryKey val entryId: String, val playlistId: String, val assetId: String, val ordinal: Int)

data class MusicRow(@Embedded val asset: LibraryAssetEntity,
    val album: String, val albumKey: String, val artist: String, val artistKey: String,
    val albumArtist: String, val disc: Int, val track: Int, val durationMs: Long, val folder: String, val lyrics: String)
data class MusicGroup(val key: String, val title: String, val count: Int)

@Dao
interface MusicDao {
    @Query("SELECT COUNT(*) FROM library_assets a JOIN music_tracks t ON t.assetId = a.id WHERE a.kind = 'MUSIC' AND (NOT EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id) OR EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id AND c.present = 1) OR a.favorite = 1 OR EXISTS(SELECT 1 FROM music_playlist_entries e WHERE e.assetId = a.id)) AND (a.title LIKE :query ESCAPE '\\' OR t.artist LIKE :query ESCAPE '\\' OR t.album LIKE :query ESCAPE '\\')")
    suspend fun searchCount(query: String): Int
    @Query("SELECT * FROM music_playlists WHERE id = :id") suspend fun playlist(id: String): MusicPlaylistEntity?
    @Upsert suspend fun putTrack(track: MusicTrackEntity)
    @Query("SELECT * FROM music_tracks WHERE assetId = :id") suspend fun track(id: String): MusicTrackEntity?
    @Query("UPDATE music_tracks SET lyrics = :text WHERE assetId = :id AND lyrics = ''") suspend fun fillLyrics(id: String, text: String)
    @Query("SELECT a.*, t.album, t.albumKey, t.artist, t.artistKey, t.albumArtist, t.disc, t.track, t.durationMs, t.folder, t.lyrics FROM library_assets a JOIN music_tracks t ON t.assetId = a.id LEFT JOIN music_listening s ON s.assetId = a.id WHERE a.kind = 'MUSIC' AND (NOT EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id) OR EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id AND c.present = 1) OR a.favorite = 1 OR EXISTS(SELECT 1 FROM music_playlist_entries e WHERE e.assetId = a.id)) AND (a.title LIKE :query ESCAPE '\\' OR t.artist LIKE :query ESCAPE '\\' OR t.album LIKE :query ESCAPE '\\') AND (:album = '' OR t.albumKey = :album) AND (:artist = '' OR t.artistKey = :artist) AND (:folder = '' OR t.folder = :folder) AND (:favorite = 0 OR a.favorite = 1) AND (:recent = 0 OR s.lastPlayedAt > 0) AND (:playlist = '' OR EXISTS(SELECT 1 FROM music_playlist_entries e WHERE e.playlistId = :playlist AND e.assetId = a.id)) ORDER BY CASE WHEN :playlist != '' THEN (SELECT MIN(ordinal) FROM music_playlist_entries e WHERE e.playlistId = :playlist AND e.assetId = a.id) END, CASE WHEN :recent THEN s.lastPlayedAt END DESC, CASE WHEN :album != '' THEN t.disc END, CASE WHEN :album != '' THEN t.track END, a.title COLLATE NOCASE, a.id")
    fun page(query: String, album: String, artist: String, folder: String, favorite: Boolean, recent: Boolean, playlist: String): PagingSource<Int, MusicRow>
    @Query("SELECT albumKey AS `key`, album AS title, COUNT(*) AS count FROM music_tracks t JOIN library_assets a ON a.id = t.assetId WHERE a.kind = 'MUSIC' AND (NOT EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id) OR EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id AND c.present = 1) OR a.favorite = 1 OR EXISTS(SELECT 1 FROM music_playlist_entries e WHERE e.assetId = a.id)) GROUP BY albumKey ORDER BY album COLLATE NOCASE")
    fun albums(): PagingSource<Int, MusicGroup>
    @Query("SELECT albumKey AS `key`, album AS title, COUNT(*) AS count FROM music_tracks t JOIN library_assets a ON a.id = t.assetId WHERE a.kind = 'MUSIC' AND (NOT EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id) OR EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id AND c.present = 1) OR a.favorite = 1 OR EXISTS(SELECT 1 FROM music_playlist_entries e WHERE e.assetId = a.id)) AND albumKey = :key GROUP BY albumKey ORDER BY album COLLATE NOCASE")
    suspend fun album(key: String): MusicGroup?
    @Query("SELECT artistKey AS `key`, artist AS title, COUNT(*) AS count FROM music_tracks t JOIN library_assets a ON a.id = t.assetId WHERE a.kind = 'MUSIC' AND (NOT EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id) OR EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id AND c.present = 1) OR a.favorite = 1 OR EXISTS(SELECT 1 FROM music_playlist_entries e WHERE e.assetId = a.id)) GROUP BY artistKey ORDER BY artist COLLATE NOCASE")
    fun artists(): PagingSource<Int, MusicGroup>
    @Query("SELECT artistKey AS `key`, artist AS title, COUNT(*) AS count FROM music_tracks t JOIN library_assets a ON a.id = t.assetId WHERE a.kind = 'MUSIC' AND (NOT EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id) OR EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id AND c.present = 1) OR a.favorite = 1 OR EXISTS(SELECT 1 FROM music_playlist_entries e WHERE e.assetId = a.id)) AND artistKey = :key GROUP BY artistKey ORDER BY artist COLLATE NOCASE")
    suspend fun artist(key: String): MusicGroup?
    @Query("SELECT folder AS `key`, folder AS title, COUNT(*) AS count FROM music_tracks t JOIN library_assets a ON a.id = t.assetId WHERE a.kind = 'MUSIC' AND (NOT EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id) OR EXISTS(SELECT 1 FROM catalog_entries c WHERE c.assetId = a.id AND c.present = 1) OR a.favorite = 1 OR EXISTS(SELECT 1 FROM music_playlist_entries e WHERE e.assetId = a.id)) AND folder != '' GROUP BY folder ORDER BY folder")
    fun folders(): PagingSource<Int, MusicGroup>
    @Query("SELECT * FROM music_playlists ORDER BY createdAt DESC") fun playlists(): Flow<List<MusicPlaylistEntity>>
    @Upsert suspend fun putPlaylist(playlist: MusicPlaylistEntity)
    @Query("DELETE FROM music_playlists WHERE id = :id") suspend fun deletePlaylist(id: String)
    @Query("SELECT * FROM music_playlist_entries WHERE playlistId = :id ORDER BY ordinal, entryId") suspend fun entries(id: String): List<MusicPlaylistEntryEntity>
    @Insert suspend fun insertEntry(entry: MusicPlaylistEntryEntity)
    @Query("DELETE FROM music_playlist_entries WHERE entryId = :id") suspend fun deleteEntry(id: String)
    @Query("UPDATE music_playlist_entries SET ordinal = :ordinal WHERE entryId = :id") suspend fun moveEntry(id: String, ordinal: Int)
    @Transaction suspend fun append(playlist: String, assets: List<String>) {
        val offset = (entries(playlist).maxOfOrNull { it.ordinal } ?: -1) + 1
        assets.forEachIndexed { index, id -> insertEntry(MusicPlaylistEntryEntity(java.util.UUID.randomUUID().toString(), playlist, id, offset + index)) }
    }
    @Transaction suspend fun reorder(playlist: String, ids: List<String>) {
        require(ids.toSet() == entries(playlist).map { it.entryId }.toSet() && ids.size == ids.toSet().size)
        ids.forEachIndexed { index, id -> moveEntry(id, index) }
    }
    @Query("SELECT * FROM music_listening WHERE assetId = :id") suspend fun listening(id: String): MusicListeningEntity?
    @Upsert suspend fun putListening(value: MusicListeningEntity)
    @Transaction suspend fun recordListening(id: String, elapsedMs: Long, counted: Boolean, now: Long) {
        val old = listening(id) ?: MusicListeningEntity(id)
        putListening(old.copy(heardMs = old.heardMs + elapsedMs.coerceIn(0, 60000), playCount = old.playCount + if (counted) 1 else 0, lastPlayedAt = now))
    }
}
