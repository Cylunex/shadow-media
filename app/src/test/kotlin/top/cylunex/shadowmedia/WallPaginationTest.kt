package top.cylunex.shadowmedia

import org.junit.Assert.*
import org.junit.Test
import top.cylunex.shadowmedia.model.*

class WallPaginationTest {
    private val query = BrowseRequest("library", setOf("Movie"), searchTerm = "submitted")
    @Test fun `next page retains submitted filters and uses backend cursor`() {
        val pagination = WallPagination(); pagination.reset(query)
        val duplicate = MediaItem("same", "Movie", "Movie", null, null, null, null, 0, false, false)
        pagination.advance(MediaPage(listOf(duplicate, duplicate), 60, 100))
        assertEquals(query.copy(startIndex = 62), pagination.request())
        // A text field edit is not a new submitted search.
        query.copy(searchTerm = "draft")
        assertEquals("submitted", pagination.request()?.searchTerm)
    }
    @Test fun `new sort restarts pagination at zero`() {
        val pagination = WallPagination(); pagination.reset(query)
        pagination.advance(MediaPage(emptyList(), 120, 300))
        pagination.reset(query.copy(sort = MediaSort.NAME, startIndex = 120))
        assertEquals(0, pagination.request()?.startIndex)
        assertEquals(MediaSort.NAME, pagination.request()?.sort)
    }
    @Test fun `uninitialized pagination cannot load arbitrary default library`() {
        assertNull(WallPagination().request())
    }
}
