package com.shadow.media

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@org.robolectric.annotation.Config(sdk = [28], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class MdcTagTreeTest {
    @Test fun `higher tags and concrete tags browse as the same tree`() {
        val tree = MdcTagTree(JSONArray("""[
            {"id":"tag:black","name":"黑丝","count":2,"parent_id":"tag:stockings"},
            {"id":"tag:clothing","name":"服饰","count":5,"parent_id":null},
            {"id":"tag:stockings","name":"丝袜","count":5,"parent_id":"tag:clothing"},
            {"id":"tag:white","name":"白丝","count":3,"parent_id":"tag:stockings"}
        ]"""))
        assertEquals(listOf("服饰"), tree.children(null).map { it.name })
        assertEquals(listOf("丝袜"), tree.children("tag:clothing").map { it.name })
        assertEquals(listOf("黑丝", "白丝"), tree.children("tag:stockings").map { it.name })
        assertEquals(listOf("服饰", "丝袜", "黑丝"), tree.path("tag:black").map { it.name })
        assertEquals(5, tree.byId["tag:stockings"]!!.count)
    }

    @Test fun `older facets and branches with an absent parent remain browsable`() {
        val tree = MdcTagTree(JSONArray("""[
            {"id":"tag:first","name":"标签一","count":2},
            {"id":"tag:second","name":"标签二","count":1,"parent_id":"tag:missing"}
        ]"""))
        assertEquals(listOf("标签一", "标签二"), tree.children(null).map { it.name })
        assertEquals(listOf("标签二"), tree.path("tag:second").map { it.name })
        assertEquals(emptyList<MdcTagNode>(), MdcTagTree(null).children(null))
    }
}
