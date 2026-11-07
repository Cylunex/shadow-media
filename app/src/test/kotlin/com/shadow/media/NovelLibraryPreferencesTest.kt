package com.shadow.media

import com.shadow.media.library.NovelQuery
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = android.app.Application::class)
class NovelLibraryPreferencesTest {
    private fun preferences() = RuntimeEnvironment.getApplication().getSharedPreferences("sort-test", 0)
    @Test fun selectionSurvivesNewScreenAndIsScopedToConnectionViewAndGroup() {
        val store = NovelLibraryPreferences(preferences())
        store.save("one", "shelf", NovelQuery(sort = "last_read"))
        store.save("one", "shelf", NovelQuery(group = "追更", sort = "title"))
        store.save("one", "shelf", NovelQuery(ungrouped = true, sort = "popularity"))
        val reopened = NovelLibraryPreferences(preferences())
        assertEquals("last_read", reopened.restore("one", "shelf", NovelQuery(q = "山海")).sort)
        assertEquals("title", reopened.restore("one", "shelf", NovelQuery(group = "追更")).sort)
        assertEquals("popularity", reopened.restore("one", "shelf", NovelQuery(ungrouped = true)).sort)
        assertEquals("recent", reopened.restore("two", "shelf", NovelQuery()).sort)
        assertEquals("recent", reopened.restore("one", "city", NovelQuery()).sort)
        assertEquals("山海", reopened.restore("one", "shelf", NovelQuery(q = "山海")).q)
    }
    @Test fun oldChoiceIsRestoredAndInvalidSortFallsBackWithoutAffectingOtherFilters() {
        preferences().edit().putString("sort:shelf:", "last_read").commit()
        val store = NovelLibraryPreferences(preferences())
        assertEquals("last_read", store.restore("one", "shelf", NovelQuery(hasUpdates = true)).sort)
        assertTrue(store.restore("one", "shelf", NovelQuery(hasUpdates = true)).hasUpdates)
        preferences().edit().putString("sort:shelf:", "removed").commit()
        assertEquals("recent", store.restore("one", "shelf", NovelQuery()).sort)
    }
}
