package top.cylunex.shadowmedia.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import top.cylunex.shadowmedia.model.*

/** Queue snapshots contain opaque catalog references, never stream addresses or request headers. */
class VideoQueueStore(context: Context) {
    private val preferences = context.getSharedPreferences("video_queue", 0)
    private val mutableQueue = MutableStateFlow(VideoQueue())
    val queue = mutableQueue.asStateFlow()
    var active: Boolean = false
        private set
    private fun reference(key: MediaKey) = MessageDigest.getInstance("SHA-256").digest(key.stableId.toByteArray()).joinToString("") { "%02x".format(it) }
    @Synchronized fun restore(available: List<UnifiedMediaItem>) {
        if (active) return
        val items = available.associateBy { reference(it.key) }
        val restored = runCatching {
            val snapshot = JSONObject(preferences.getString("snapshot", "{}")!!)
            val records = snapshot.optJSONArray("entries") ?: JSONArray()
            val entries = (0 until minOf(records.length(), 2000)).mapNotNull { index ->
                val row = records.getJSONObject(index)
                val item = items[row.getString("reference")] ?: return@mapNotNull null
                VideoQueueEntry(row.getString("id"), item.key, item.title)
            }.distinctBy { it.entryId }
            val id = snapshot.optString("current").takeIf { current -> entries.any { it.entryId == current } }
            VideoQueue(entries, id ?: entries.firstOrNull()?.entryId,
                if (id != null) snapshot.optLong("position").coerceAtLeast(0) else 0, snapshot.optBoolean("repeat"))
        }.getOrDefault(VideoQueue())
        mutableQueue.value = restored
    }
    @Synchronized fun start(items: List<UnifiedMediaItem>, index: Int = 0): VideoQueue {
        require(items.isNotEmpty() && items.size <= 2000 && index in items.indices)
        val entries = items.map { VideoQueueEntry(UUID.randomUUID().toString(), it.key, it.title) }
        active = true
        return update(VideoQueue(entries, entries[index].entryId))
    }
    @Synchronized fun activate(): VideoQueue { active = true; return queue.value }
    @Synchronized fun deactivate() { active = false }
    @Synchronized fun select(id: String): VideoQueue { active = true; return update(queue.value.select(id)) }
    @Synchronized fun move(id: String, delta: Int) = update(queue.value.move(id, delta))
    @Synchronized fun remove(id: String) = update(queue.value.remove(id))
    @Synchronized fun repeat(value: Boolean) = update(queue.value.copy(repeat = value))
    @Synchronized fun append(item: UnifiedMediaItem) = update(queue.value.copy(entries = queue.value.entries + VideoQueueEntry(UUID.randomUUID().toString(), item.key, item.title)))
    @Synchronized fun position(id: String, value: Long) {
        if (queue.value.currentEntryId == id) update(queue.value.copy(positionMs = value.coerceAtLeast(0)))
    }
    @Synchronized fun advance(): VideoQueue? = if (active) queue.value.next()?.let(::update) else null
    private fun update(value: VideoQueue): VideoQueue {
        mutableQueue.value = value
        val records = value.entries.map { JSONObject().put("id", it.entryId).put("reference", reference(it.key)) }
        preferences.edit().putString("snapshot", JSONObject().put("entries", JSONArray(records))
            .put("current", value.currentEntryId).put("position", value.positionMs).put("repeat", value.repeat).toString()).apply()
        return value
    }
}
