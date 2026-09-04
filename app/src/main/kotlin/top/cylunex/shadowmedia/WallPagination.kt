package top.cylunex.shadowmedia

import top.cylunex.shadowmedia.model.BrowseRequest
import top.cylunex.shadowmedia.model.MediaPage

/** Pagination belongs to the submitted query, not the editable field or deduplicated wall size. */
internal class WallPagination {
    private var submitted: BrowseRequest? = null
    private var nextIndex = 0
    fun reset(request: BrowseRequest) { submitted = request.copy(startIndex = 0); nextIndex = 0 }
    fun request(): BrowseRequest? = submitted?.copy(startIndex = nextIndex)
    fun advance(page: MediaPage) { nextIndex = page.startIndex + page.items.size }
}
