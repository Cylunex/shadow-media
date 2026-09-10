package top.cylunex.shadowmedia.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.cylunex.shadowmedia.database.*

/** Process-owned writer survives service disposal and orders saves before the next service load. */
internal object AudioQueuePersistence {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val failure = MutableStateFlow<String?>(null)
    val error = failure.asStateFlow()
    init {
        scope.launch {
            for (write in writes) {
                try { write() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { failure.value = "播放队列保存失败，请检查设备剩余空间" }
            }
        }
    }
    fun save(dao: AudioQueueDao, snapshot: AudioQueueSnapshot) {
        check(writes.trySend { dao.save(snapshot); failure.value = null }.isSuccess)
    }
    suspend fun flush() {
        val barrier = CompletableDeferred<Unit>()
        writes.send { barrier.complete(Unit) }
        barrier.await()
    }
}

/** Removed/replaced entries do not move the saved position onto a different track. */
internal fun AudioQueueSnapshot.retaining(validEntryIds: Set<String>): AudioQueueSnapshot {
    val ordered = entries.sortedBy { it.ordinal }
    val retained = ordered.filter { it.entryId in validEntryIds }
    val original = ordered.indexOfFirst { it.entryId == queue.currentEntryId }
    val current = retained.firstOrNull { it.entryId == queue.currentEntryId }
        ?: ordered.drop(original.coerceAtLeast(0)).firstOrNull { it.entryId in validEntryIds }
        ?: retained.lastOrNull()
    val shuffle = retained.sortedBy { it.shuffleOrdinal }.mapIndexed { i, entry -> entry.entryId to i }.toMap()
    return AudioQueueSnapshot(queue.copy(currentEntryId = current?.entryId,
        positionMs = if (current?.entryId == queue.currentEntryId) queue.positionMs.coerceAtLeast(0) else 0,
        speed = queue.speed.takeIf { it.isFinite() && it in 0.5f..3f } ?: 1f,
        repeatMode = queue.repeatMode.takeIf { it in 0..2 } ?: 0),
        retained.mapIndexed { i, entry -> entry.copy(ordinal = i, shuffleOrdinal = shuffle.getValue(entry.entryId)) })
}

internal const val AUDIOBOOK_QUEUE = "AUDIOBOOK"
