package top.cylunex.shadowmedia.audio

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import top.cylunex.shadowmedia.database.MusicDao
import top.cylunex.shadowmedia.model.AudioMode

/** Count audible elapsed time, never seek distance or buffered time. Writes survive service disposal. */
internal class ListeningTracker(private val dao: MusicDao) {
    private var entry: String? = null
    private var asset: String? = null
    private var last = 0L
    private var playing = false
    private var heard = 0L
    private var pending = 0L
    private var counted = false
    private var threshold = 30000L
    fun sample(player: Player, transition: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (playing && last > 0) {
            val elapsed = (now - last).coerceIn(0, 5000)
            heard += elapsed; pending += elapsed
        }
        val current = player.currentMediaItem
        val changed = transition || entry != current?.entryId()
        val count = !counted && heard >= threshold
        if (asset != null && (pending >= 5000 || changed || !player.isPlaying || count)) {
            val id = asset!!; val elapsed = pending; pending = 0; counted = counted || count
            writes.trySend { dao.recordListening(id, elapsed, count, System.currentTimeMillis()) }
        }
        if (changed) { entry = current?.entryId(); asset = current?.takeIf { it.audioMode() == AudioMode.MUSIC }?.mediaId; heard = 0; pending = 0; counted = false }
        threshold = minOf(30_000L, (player.duration.takeIf { it > 0 } ?: 60_000L) / 2).coerceAtLeast(1000)
        playing = player.isPlaying && asset != null
        last = now
    }
    companion object {
        private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        init { CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { for (write in writes) try { write() } catch (e: CancellationException) { throw e } catch (_: Exception) { AudioQueueStatus.message.value = "收听统计暂时无法保存" } } }
    }
}
