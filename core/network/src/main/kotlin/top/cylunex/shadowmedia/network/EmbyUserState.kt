package top.cylunex.shadowmedia.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.cylunex.shadowmedia.database.LibraryStateDao
import top.cylunex.shadowmedia.database.RemoteUserStateEntity
import top.cylunex.shadowmedia.model.*
import java.util.UUID

/** Local actions survive stale catalog refreshes; acknowledgements match the exact operation. */
class EmbyUserState(private val dao: LibraryStateDao, private val remote: EmbyRepository) {
    private val locks = Array(64) { Mutex() }
    fun observe(session: EmbySession) = dao.userStates(session.providerId).map { values -> values.associate { it.itemId to it.favorite } }
    suspend fun overlay(session: EmbySession, items: List<MediaItem>): List<MediaItem> {
        val states = dao.readUserStates(session.providerId).associateBy { it.itemId }
        return items.map { item -> states[item.id]?.let { item.copy(favorite = it.favorite) } ?: item }
    }
    suspend fun setFavorite(session: EmbySession, item: String, value: Boolean) {
        dao.favorite(RemoteUserStateEntity(session.providerId, item, value, UUID.randomUUID().toString(), true, System.currentTimeMillis()))
    }
    suspend fun flush(session: EmbySession) = locks[(session.providerId.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
        if (NetworkPolicy.offlineOnly) return@withLock
        for (state in dao.readUserStates(session.providerId).filter { it.pending }.sortedBy { it.updatedAt }.take(100)) {
            if (dao.userState(state.providerId, state.itemId)?.operationId != state.operationId) continue
            try {
                remote.setFavorite(session, state.itemId, state.favorite)
                dao.acknowledgeUserState(state.providerId, state.itemId, state.operationId)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { break }
        }
    }
}
