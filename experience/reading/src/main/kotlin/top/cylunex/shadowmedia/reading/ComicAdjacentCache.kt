package top.cylunex.shadowmedia.reading

import java.io.Closeable
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.cylunex.shadowmedia.network.ResourcePriority

/** Own only an unclaimed adjacent page. The visible view owns and disposes the file it takes. */
internal class ComicAdjacentCache(private val create: suspend (Int, ResourcePriority) -> File) : Closeable {
    private val locks = Array(8) { Mutex() }
    private var cached: Pair<Int, File>? = null
    @Volatile private var closed = false
    suspend fun take(index: Int): File = locks[index % locks.size].withLock {
        synchronized(this) { cached?.takeIf { it.first == index && it.second.isFile }?.also { cached = null }?.second }
            ?: create(index, ResourcePriority.FOREGROUND)
    }
    suspend fun warm(index: Int) = locks[index % locks.size].withLock {
        if (closed || synchronized(this) { cached?.first == index }) return@withLock
        val file = create(index, ResourcePriority.ADJACENT)
        synchronized(this) {
            if (closed) file.delete() else { cached?.second?.delete(); cached = index to file }
        }
    }
    override fun close() = synchronized(this) { closed = true; cached?.second?.delete(); cached = null }
}
