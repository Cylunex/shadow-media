package com.shadow.media.reading

import android.util.LruCache
import kotlinx.coroutines.*

/** Lookups stay on Main; LruCache publishes completed background layouts safely to both callers. */
internal class NativeTextLayoutCache(private val scope: CoroutineScope, private val capacity: Int = 3) {
    private val completed = LruCache<String, NativeTextLayout>(capacity)
    private val pending = linkedMapOf<String, Deferred<NativeTextLayout>>()

    suspend fun get(key: String, create: suspend () -> NativeTextLayout): NativeTextLayout {
        completed.get(key)?.let { return it }
        val request = pending[key]?.takeUnless { it.isCancelled } ?: scope.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            create().also { ensureActive(); completed.put(key, it) }
        }.also {
            pending[key] = it
            while (pending.size > capacity) {
                val oldest = pending.entries.first()
                pending.remove(oldest.key)?.cancel()
            }
            it.start()
        }
        try {
            return request.await()
        } finally {
            if (request.isCompleted && pending[key] === request) pending.remove(key)
        }
    }

    fun clear() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        completed.evictAll()
    }
}
