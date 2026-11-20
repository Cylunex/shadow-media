package com.shadow.media.reading

internal fun comicSpreadCompleted(page: Int, count: Int, mode: String): Boolean =
    page in 0 until count && comicSpreadIndex(page, mode) == comicSpreadIndex(count - 1, mode)

internal fun comicStripCompleted(lastVisible: Int, count: Int, canScrollForward: Boolean): Boolean =
    count > 0 && lastVisible == count - 1 && !canScrollForward

internal fun comicSpreadIndex(page: Int, mode: String): Int = if (mode == "双页") (page + 1) / 2 else page
internal fun comicSpreadStart(spread: Int, mode: String): Int = if (mode == "双页") (spread * 2 - 1).coerceAtLeast(0) else spread
