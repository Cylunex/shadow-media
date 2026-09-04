package top.cylunex.shadowmedia.library

import org.json.JSONObject
import top.cylunex.shadowmedia.database.SyncOperationEntity

internal object ProgressPolicy {
    fun timeSnapshot(incoming: JSONObject, previous: JSONObject?): JSONObject = JSONObject(incoming.toString()).apply {
        if (optLong("durationMs", 0) <= 0) {
            previous?.optLong("durationMs", 0)?.takeIf { it > 0 }?.let { put("durationMs", it) }
        }
        put("positionMs", optLong("positionMs", 0).coerceAtLeast(0))
    }

    /** A book has one remote position even when the local shelf has several track records. */
    fun absBookId(itemId: String): String = itemId.substringBefore("::")

    /** Includes deferred retries: an old due operation must not overtake a newer deferred one. */
    fun latestOperations(operations: List<SyncOperationEntity>, targets: Map<String, String>): Set<String> =
        operations.groupBy { it.kind to (targets[it.id] ?: it.target) }.values.map { entries ->
            entries.maxWith(compareBy<SyncOperationEntity> { it.createdAt }.thenBy { it.id }).id
        }.toSet()

    fun absPosition(trackOffset: Double, trackDuration: Double, bookDuration: Double, positionMs: Long): Double {
        require(trackOffset.isFinite() && trackOffset >= 0 && trackDuration.isFinite() && trackDuration > 0 && bookDuration.isFinite() && bookDuration > 0)
        return (trackOffset + (positionMs.coerceAtLeast(0) / 1000.0).coerceAtMost(trackDuration)).coerceAtMost(bookDuration)
    }
}
