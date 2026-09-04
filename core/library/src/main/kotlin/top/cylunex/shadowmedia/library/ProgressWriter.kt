package top.cylunex.shadowmedia.library

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Process-owned, ordered disk writer. Activity/service disposal never blocks the main thread. */
object ProgressWriter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val failure = MutableStateFlow<String?>(null)
    val error = failure.asStateFlow()
    init {
        scope.launch {
            for (write in writes) {
                try { write(); failure.value = null }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { failure.value = "进度保存失败，请检查设备剩余空间" }
            }
        }
    }
    fun save(library: LibraryRepository, id: String, type: String, locator: JSONObject, fraction: Double?, completed: Boolean = false) {
        val snapshot = locator.toString()
        check(writes.trySend { library.saveProgress(id, type, JSONObject(snapshot), fraction, completed) }.isSuccess)
    }
}
