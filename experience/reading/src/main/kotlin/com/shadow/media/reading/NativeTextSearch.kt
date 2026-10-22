package com.shadow.media.reading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal data class NativeSearchEntry(
    val assetId: String,
    val ref: NativeChapterRef,
    val load: suspend () -> NativeTextChapter,
)

/** Results retain a short preview and a character locator, never an entire chapter. */
internal data class NativeSearchHit(val assetId: String, val title: String, val locator: String, val preview: String)
internal data class NativeSearchResult(
    val hits: List<NativeSearchHit> = emptyList(),
    val scanned: Int = 0,
    val total: Int = 0,
    val skipped: Int = 0,
    val complete: Boolean = false,
    val limited: Boolean = false,
)

internal fun searchNativeText(entries: List<NativeSearchEntry>, query: String, limit: Int = 200) = flow {
    require(limit > 0)
    val term = query.trim()
    val hits = mutableListOf<NativeSearchHit>()
    var scanned = 0
    var skipped = 0
    if (term.isNotEmpty()) for (entry in entries) {
        currentCoroutineContext().ensureActive()
        val previousHits = hits.size
        val content = try { entry.load() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { skipped++; null }
        if (content != null) {
            var index = content.text.indexOf(term, ignoreCase = true)
            while (index >= 0) {
                currentCoroutineContext().ensureActive()
                val start = safeTextOffset(content.text, (index - 20).coerceAtLeast(0))
                val end = safeTextOffset(content.text, (start + 120).coerceAtMost(content.text.length))
                hits += NativeSearchHit(entry.assetId, entry.ref.title, content.locator(index).toString(), content.text.substring(start, end))
                if (hits.size == limit) {
                    emit(NativeSearchResult(hits.toList(), scanned + 1, entries.size, skipped, complete = true, limited = true))
                    return@flow
                }
                index = content.text.indexOf(term, index + term.length, ignoreCase = true)
            }
        }
        scanned++
        if (scanned % 10 == 0 || hits.size != previousHits)
            emit(NativeSearchResult(hits.toList(), scanned, entries.size, skipped))
    }
    emit(NativeSearchResult(hits.toList(), scanned, entries.size, skipped, complete = true))
}.flowOn(Dispatchers.Default)

internal const val NATIVE_CONTENTS_PAGE_SIZE = 100
internal fun nativeContentsOffset(ordinal: Int, total: Int): Int =
    ordinal.coerceIn(0, (total - 1).coerceAtLeast(0)) / NATIVE_CONTENTS_PAGE_SIZE * NATIVE_CONTENTS_PAGE_SIZE
