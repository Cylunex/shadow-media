package top.cylunex.shadowmedia

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.MediaItem

data class FeedSessionSnapshot(
    val libraryId: String,
    val orderedItemIds: List<String>,
    val currentItemId: String?,
    val currentIndex: Int,
    val updatedAtEpochMs: Long,
)

class FeedSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun reconcile(
        session: EmbySession,
        libraryId: String,
        items: List<MediaItem>,
    ): FeedSessionSnapshot {
        val previous = read(session, libraryId)
        val available = items.map(MediaItem::id).toSet()
        val ordered = buildList {
            previous?.orderedItemIds?.filterTo(this) { it in available }
            items.map(MediaItem::id).filterTo(this) { it !in this }
        }
        val currentId = previous?.currentItemId?.takeIf { it in available }
        val currentIndex = currentId?.let(ordered::indexOf)?.coerceAtLeast(0) ?: 0
        return FeedSessionSnapshot(
            libraryId = libraryId,
            orderedItemIds = ordered,
            currentItemId = currentId ?: ordered.firstOrNull(),
            currentIndex = currentIndex,
            updatedAtEpochMs = System.currentTimeMillis(),
        ).also { write(session, it) }
    }

    @Synchronized
    fun markCurrent(session: EmbySession, libraryId: String, itemId: String, index: Int) {
        val previous = read(session, libraryId) ?: return
        write(
            session,
            previous.copy(
                currentItemId = itemId,
                currentIndex = index.coerceIn(0, (previous.orderedItemIds.size - 1).coerceAtLeast(0)),
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    @Synchronized
    fun removeItem(session: EmbySession, libraryId: String, itemId: String) {
        val previous = read(session, libraryId) ?: return
        val ordered = previous.orderedItemIds.filterNot { it == itemId }
        val index = previous.currentIndex.coerceIn(0, (ordered.size - 1).coerceAtLeast(0))
        write(
            session,
            previous.copy(
                orderedItemIds = ordered,
                currentItemId = ordered.getOrNull(index),
                currentIndex = index,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    @Synchronized
    fun lastFor(session: EmbySession): FeedSessionSnapshot? {
        val libraryId = preferences.getString(lastKey(session), null) ?: return null
        return read(session, libraryId)
    }

    private fun read(session: EmbySession, libraryId: String): FeedSessionSnapshot? = runCatching {
        val raw = preferences.getString(feedKey(session, libraryId), null) ?: return null
        val objectValue = JSONObject(raw)
        val ids = objectValue.getJSONArray("orderedItemIds")
        FeedSessionSnapshot(
            libraryId = libraryId,
            orderedItemIds = List(ids.length()) { ids.getString(it) },
            currentItemId = objectValue.optString("currentItemId").ifBlank { null },
            currentIndex = objectValue.optInt("currentIndex", 0),
            updatedAtEpochMs = objectValue.optLong("updatedAtEpochMs", 0L),
        )
    }.getOrNull()

    private fun write(session: EmbySession, snapshot: FeedSessionSnapshot) {
        val objectValue = JSONObject()
            .put("orderedItemIds", JSONArray(snapshot.orderedItemIds))
            .put("currentItemId", snapshot.currentItemId ?: "")
            .put("currentIndex", snapshot.currentIndex)
            .put("updatedAtEpochMs", snapshot.updatedAtEpochMs)
        check(
            preferences.edit()
                .putString(feedKey(session, snapshot.libraryId), objectValue.toString())
                .putString(lastKey(session), snapshot.libraryId)
                .commit()
        ) { "无法保存刷片位置" }
    }

    private fun feedKey(session: EmbySession, libraryId: String) =
        "feed:${session.serverUrl}:${session.serverId}:${session.userId}:$libraryId"

    private fun lastKey(session: EmbySession) =
        "last:${session.serverUrl}:${session.serverId}:${session.userId}"

    companion object {
        private const val PREFERENCES_NAME = "feed_sessions"
    }
}
