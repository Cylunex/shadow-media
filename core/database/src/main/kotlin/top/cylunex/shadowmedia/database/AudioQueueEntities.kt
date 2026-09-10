package top.cylunex.shadowmedia.database

import androidx.room.*

/** Only stable asset/entry identifiers are stored here; never playback URLs or headers. */
@Entity(tableName = "audio_queues")
data class AudioQueueEntity(
    @PrimaryKey val id: String,
    val currentEntryId: String?,
    val positionMs: Long,
    val speed: Float,
    val repeatMode: Int,
    val shuffleEnabled: Boolean,
)

@Entity(tableName = "audio_queue_entries", indices = [Index("queueId")], foreignKeys = [
    ForeignKey(entity = AudioQueueEntity::class, parentColumns = ["id"], childColumns = ["queueId"], onDelete = ForeignKey.CASCADE),
])
data class AudioQueueEntryEntity(
    @PrimaryKey val entryId: String,
    val queueId: String,
    val assetId: String,
    val resourceRevision: String,
    val ordinal: Int,
    val shuffleOrdinal: Int,
)

data class AudioQueueSnapshot(
    @Embedded val queue: AudioQueueEntity,
    @Relation(parentColumn = "id", entityColumn = "queueId") val entries: List<AudioQueueEntryEntity>,
)

@Dao
interface AudioQueueDao {
    @Transaction @Query("SELECT * FROM audio_queues WHERE id = :id")
    suspend fun load(id: String): AudioQueueSnapshot?
    @Upsert suspend fun putQueue(queue: AudioQueueEntity)
    @Insert suspend fun putEntries(entries: List<AudioQueueEntryEntity>)
    @Query("DELETE FROM audio_queue_entries WHERE queueId = :id")
    suspend fun removeEntries(id: String)

    @Transaction suspend fun save(snapshot: AudioQueueSnapshot) {
        val previous = load(snapshot.queue.id)
        putQueue(snapshot.queue)
        // Position ticks only update the header, not thousands of unchanged entries.
        if (previous?.entries?.sortedBy { it.ordinal } != snapshot.entries.sortedBy { it.ordinal }) {
            removeEntries(snapshot.queue.id)
            putEntries(snapshot.entries)
        }
    }
}
