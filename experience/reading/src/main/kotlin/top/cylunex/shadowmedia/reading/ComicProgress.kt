package top.cylunex.shadowmedia.reading

/** Reaching the final visible spread completes a paged comic. Long images require its bottom. */
internal fun comicCompleted(page: Int, count: Int, mode: String, offset: Float): Boolean {
    if (count <= 0 || page !in 0 until count) return false
    val spread = if (mode == "双页") 2 else 1
    val lastVisible = page / spread == (count - 1) / spread
    return lastVisible && (mode != "长图" || offset.isFinite() && offset >= .99f)
}
