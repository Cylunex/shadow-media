package top.cylunex.shadowmedia.model

/** Refuse byte concatenation unless the response proves offset and representation continuity. */
object ResumeValidation {
    fun accepts(position: Long, status: Int, rangeRequested: Boolean, contentRange: String?, contentLength: Long?, expectedValidator: String, actualValidator: String): Boolean {
        if (position < 0 || status !in 200..299) return false
        if (status == 206) {
            if (!rangeRequested) return false
            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(contentRange.orEmpty()) ?: return false
            val (start, end, total) = match.groupValues.drop(1).map { it.toLongOrNull() ?: return false }
            if (start != position || end < start || total <= end) return false
            if (contentLength != null && contentLength >= 0 && end - start + 1 != contentLength) return false
        }
        if (position == 0L) return status == 200 || status == 206
        return status == 206 && expectedValidator.isNotBlank() && expectedValidator == actualValidator
    }
}
