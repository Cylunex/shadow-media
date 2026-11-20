package com.shadow.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.shadow.media.model.BrowseRequest
import com.shadow.media.model.MediaFilter
import com.shadow.media.model.MediaSort
import com.shadow.media.network.EmbyRepository
import com.shadow.media.model.EmbySession
import com.shadow.media.model.MediaItem

data class FeedSessionSnapshot(
    val libraryId: String,
    val orderedItemIds: List<String>,
    val currentItemId: String?,
    val currentIndex: Int,
    val updatedAtEpochMs: Long,
)

internal fun FeedSessionSnapshot.removing(itemId: String, nowMs: Long): FeedSessionSnapshot {
    val ordered = orderedItemIds.filterNot { it == itemId }
    val retained = ordered.indexOf(currentItemId)
    val index = if (retained >= 0) retained else currentIndex.coerceIn(0, ordered.lastIndex.coerceAtLeast(0))
    return copy(orderedItemIds = ordered, currentItemId = ordered.getOrNull(index), currentIndex = index, updatedAtEpochMs = nowMs)
}

enum class FeedOrder(val label: String) { RECENT("最近添加"), RANDOM("随机"), UNWATCHED("未看"), FAVORITES("收藏") }
data class FeedFilter(val order: FeedOrder = FeedOrder.RECENT, val hideSeen: Boolean = false)

class FeedSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun filter(session: EmbySession, library: String): FeedFilter {
        val key = feedKey(session, library)
        return FeedFilter(runCatching { FeedOrder.valueOf(preferences.getString("order:" + key, "RECENT")!!) }.getOrDefault(FeedOrder.RECENT),
            preferences.getBoolean("hide:" + key, false))
    }
    fun setFilter(session: EmbySession, library: String, filter: FeedFilter) {
        val key = feedKey(session, library)
        preferences.edit().putString("order:" + key, filter.order.name).putBoolean("hide:" + key, filter.hideSeen).apply()
    }
    fun seen(session: EmbySession, library: String): Set<String> = preferences.getStringSet("seen:" + feedKey(session, library), emptySet()).orEmpty()
    private fun queueKey(session: EmbySession, library: String): String {
        val filter = filter(session, library)
        val base = feedKey(session, library)
        return if (filter == FeedFilter()) base else "$base:${filter.order}:${filter.hideSeen}"
    }
    internal suspend fun candidates(repository: EmbyRepository, session: EmbySession, library: String, filter: FeedFilter): List<MediaItem> {
        if (filter == FeedFilter()) return repository.recentVideos(session, library)
        val type = when (filter.order) {
            FeedOrder.UNWATCHED -> MediaFilter.UNPLAYED
            FeedOrder.FAVORITES -> MediaFilter.FAVORITES
            else -> MediaFilter.ALL
        }
        val seen = if (filter.hideSeen) seen(session, library) else emptySet()
        val result = linkedMapOf<String, MediaItem>()
        var offset = 0
        // Random ordering is safe for a single sample. Ordered paging can scan past a whole page of seen items.
        val random = filter.order == FeedOrder.RANDOM && !filter.hideSeen
        do {
            val page = repository.browse(session, BrowseRequest(parentId = library, includeItemTypes = setOf("Movie", "Episode", "Video"),
                filter = type, sort = if (random) MediaSort.RANDOM else MediaSort.DATE_ADDED, startIndex = offset, limit = 120))
            val previousCount = result.size
            page.items.forEach { result[it.id] = it }
            offset += page.items.size
        } while (!random && page.hasMore && result.size > previousCount && result.keys.count { it !in seen } < 120)
        return result.values.toList()
    }

    @Synchronized
    fun reconcile(
        session: EmbySession,
        libraryId: String,
        items: List<MediaItem>,
    ): FeedSessionSnapshot {
        val previous = read(session, libraryId)
        val filter = filter(session, libraryId)
        val seen = if (filter.hideSeen) seen(session, libraryId) else emptySet()
        val eligible = items.filter { it.id !in seen && when (filter.order) {
            FeedOrder.UNWATCHED -> !it.played
            FeedOrder.FAVORITES -> it.favorite
            else -> true
        } }
        val available = eligible.mapTo(linkedSetOf(), MediaItem::id)
        val incoming = if (filter.order == FeedOrder.RANDOM) available.shuffled() else available
        val ordered = (previous?.orderedItemIds.orEmpty().filterTo(linkedSetOf()) { it in available } + incoming).toList()
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
        val seenKey = "seen:" + feedKey(session, libraryId)
        preferences.edit().putStringSet(seenKey, seen(session, libraryId) + itemId).apply()
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
        write(session, previous.removing(itemId, System.currentTimeMillis()))
    }

    @Synchronized
    fun lastFor(session: EmbySession): FeedSessionSnapshot? {
        val libraryId = preferences.getString(lastKey(session), null) ?: return null
        return read(session, libraryId)
    }

    private fun read(session: EmbySession, libraryId: String): FeedSessionSnapshot? = runCatching {
        val raw = preferences.getString(queueKey(session, libraryId), null) ?: return null
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
                .putString(queueKey(session, snapshot.libraryId), objectValue.toString())
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
