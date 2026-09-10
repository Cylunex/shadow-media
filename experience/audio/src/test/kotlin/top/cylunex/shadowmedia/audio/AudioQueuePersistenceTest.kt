package top.cylunex.shadowmedia.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.cylunex.shadowmedia.database.*

class AudioQueuePersistenceTest {
    private fun snapshot() = AudioQueueSnapshot(AudioQueueEntity("AUDIOBOOK", "second", 12345, 1.75f, 2, true), listOf(
        AudioQueueEntryEntity("first", "AUDIOBOOK", "same-asset", "v1", 0, 2),
        AudioQueueEntryEntity("second", "AUDIOBOOK", "same-asset", "v1", 1, 0),
        AudioQueueEntryEntity("third", "AUDIOBOOK", "other-asset", "v2", 2, 1),
    ))

    @Test fun duplicateTracksRestoreTheSelectedOccurrenceAndActualShuffleOrder() {
        val saved = snapshot()
        assertEquals(saved, saved.retaining(setOf("first", "second", "third")))
        assertEquals(listOf("second", "third", "first"), saved.entries.sortedBy { it.shuffleOrdinal }.map { it.entryId })
    }
    @Test fun removingTheCurrentOccurrenceSelectsItsSuccessorWithoutReusingPosition() {
        val restored = snapshot().retaining(setOf("first", "third"))
        assertEquals("third", restored.queue.currentEntryId)
        assertEquals(0L, restored.queue.positionMs)
        assertEquals(listOf(1, 0), restored.entries.map { it.shuffleOrdinal })
    }
    @Test fun missingPredecessorDoesNotResetCurrentTrackPosition() {
        val restored = snapshot().retaining(setOf("second", "third"))
        assertEquals("second", restored.queue.currentEntryId)
        assertEquals(12345L, restored.queue.positionMs)
        assertEquals(listOf(0, 1), restored.entries.map { it.ordinal })
    }
    @Test fun allMissingAssetsRestoreAnEmptyQueue() {
        val restored = snapshot().retaining(emptySet())
        assertNull(restored.queue.currentEntryId)
        assertEquals(0L, restored.queue.positionMs)
        assertTrue(restored.entries.isEmpty())
    }
    @Test fun invalidLegacyParametersAreNormalized() {
        val saved = snapshot().let { it.copy(queue = it.queue.copy(speed = Float.NaN, positionMs = -1, repeatMode = 9)) }
        val restored = saved.retaining(saved.entries.map { it.entryId }.toSet())
        assertEquals(1f, restored.queue.speed)
        assertEquals(0L, restored.queue.positionMs)
        assertEquals(0, restored.queue.repeatMode)
    }
    @Test fun positionTicksDoNotReplaceEntriesAndEditsRemainIndependent() = runBlocking {
        val dao = MemoryQueueDao()
        val saved = snapshot()
        dao.save(saved)
        dao.save(saved.copy(queue = saved.queue.copy(positionMs = 90000)))
        assertEquals(1, dao.entryWrites)
        assertEquals(90000L, dao.load("AUDIOBOOK")!!.queue.positionMs)
        dao.save(saved.retaining(setOf("first", "third")))
        assertEquals(2, dao.entryWrites)
        assertEquals(listOf("first", "third"), dao.load("AUDIOBOOK")!!.entries.map { it.entryId })
    }
    @Test fun pendingSavesSurviveWriterOwnershipChangesAndFlushBeforeRestore() = runBlocking {
        val dao = MemoryQueueDao()
        val saved = snapshot()
        AudioQueuePersistence.save(dao, saved)
        AudioQueuePersistence.save(dao, saved.copy(queue = saved.queue.copy(currentEntryId = "third", positionMs = 45678)))
        AudioQueuePersistence.flush()
        assertEquals("third", dao.load("AUDIOBOOK")!!.queue.currentEntryId)
        assertEquals(45678L, dao.load("AUDIOBOOK")!!.queue.positionMs)
    }

    private class MemoryQueueDao : AudioQueueDao {
        var queue: AudioQueueEntity? = null
        var entries = emptyList<AudioQueueEntryEntity>()
        var entryWrites = 0
        override suspend fun load(id: String) = queue?.let { AudioQueueSnapshot(it, entries) }
        override suspend fun putQueue(queue: AudioQueueEntity) { this.queue = queue }
        override suspend fun putEntries(entries: List<AudioQueueEntryEntity>) { this.entries = entries; entryWrites++ }
        override suspend fun removeEntries(id: String) { entries = emptyList() }
    }
}
