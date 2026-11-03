package com.shadow.media.reading

/** Reaching the final visible spread completes a paged comic. Long images require its bottom. */
internal fun comicCompleted(page: Int, count: Int, mode: String, offset: Float): Boolean {
    if (count <= 0 || page !in 0 until count) return false
    val lastVisible = comicSpreadIndex(page, mode) == comicSpreadIndex(count - 1, mode)
    return lastVisible && (mode != "长图" || offset.isFinite() && offset >= .99f)
}

internal fun comicSpreadIndex(page: Int, mode: String): Int = if (mode == "双页") (page + 1) / 2 else page
internal fun comicSpreadStart(spread: Int, mode: String): Int = if (mode == "双页") (spread * 2 - 1).coerceAtLeast(0) else spread
