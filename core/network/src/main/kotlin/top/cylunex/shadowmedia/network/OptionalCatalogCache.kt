package top.cylunex.shadowmedia.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Metadata snapshots are optional; cache failures must not hide a successful source response.
 * Never use this for authoritative user state, credentials or installed resources.
 */
suspend fun <T> withOptionalCatalogCache(block: suspend () -> T): T? = try {
    block().also { currentCoroutineContext().ensureActive() }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    currentCoroutineContext().ensureActive()
    null
}
