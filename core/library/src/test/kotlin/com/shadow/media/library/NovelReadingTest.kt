package com.shadow.media.library

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovelReadingTest {
    private fun mapping(version: String = "v1") = NovelPublicationMap(JSONObject("""{
        "schema":"shadow.novel.publication/v1","work_id":"w","version_id":"$version","complete":false,"version_complete":true,
        "chapters":[{"id":"c1","title":"第一章","ordinal":1,"href":"chapters/c1.xhtml","body_hash":"h1"},{"id":"c2","title":"第二章","ordinal":2,"href":"chapters/c2.xhtml","body_hash":"h2"}]
    }"""))
    @Test fun chapterProgressionIsIndependentOfWindowProgressionAndFragments() {
        val locator = JSONObject("""{"href":"EPUB/chapters/c2.xhtml#p-4","locations":{"progression":0.42,"totalProgression":0.9,"position":37}}""")
        val position = mapping().position(locator)!!
        assertEquals("c2", position.getString("chapter_id")); assertEquals(0.42, position.getDouble("ratio"), 0.00001)
        val restored = mapping().locator(position)!!
        assertEquals("chapters/c2.xhtml", restored.getString("href")); assertFalse(restored.getJSONObject("locations").has("totalProgression")); assertFalse(restored.getJSONObject("locations").has("position"))
        assertEquals("chapters/c2.xhtml", position.getJSONObject("anchor").getJSONObject("media_locator").getString("href"))
        assertEquals("p-4", restored.getJSONObject("locations").getJSONArray("fragments").getString(0))
        assertFalse(mapping().complete)
    }
    @Test fun versionAndChapterMustMatchExactly() {
        assertNull(mapping().position(JSONObject("""{"href":"chapters/c20.xhtml","locations":{"progression":0.1}}""")))
        assertNull(mapping().locator(JSONObject("""{"version_id":"v2","chapter_id":"c1","ratio":0.2}""")))
        assertNull(mapping().position(JSONObject("""{"href":"chapters/c1.xhtml","locations":{"totalProgression":0.9}}""")))
        assertNull(mapping().position(JSONObject("""{"href":"chapters/c1.xhtml","locations":{"progression":1.5}}""")))
    }
    @Test fun openingAtZeroAndNavigatorReEmissionDoNotProduceProgressMutations() {
        val tracker = NovelLocatorTracker(mapping())
        fun locator(ratio: Double) = JSONObject().put("href", "chapters/c1.xhtml").put("locations", JSONObject().put("progression", ratio))
        assertFalse(tracker.moved(locator(0.0)))
        assertFalse(tracker.moved(locator(0.0)))
        assertTrue(tracker.moved(locator(0.2)))
        assertFalse(tracker.moved(locator(0.2)))
        assertTrue(tracker.moved(locator(0.0)))
    }
    @Test fun locatorAnchorStaysWithinServerLimit() {
        val locator = JSONObject().put("href", "chapters/c1.xhtml").put("locations", JSONObject().put("progression", 0.1)).put("text", JSONObject().put("highlight", "文".repeat(4000)))
        val position = mapping().position(locator)!!
        assertTrue(position.getJSONObject("anchor").toString().toByteArray().size < 8192)
        assertEquals(0.1, position.getDouble("ratio"), 0.0001)
    }
    @Test(expected = IllegalArgumentException::class) fun ambiguousMappingIsRejected() {
        val json = JSONObject(mapping().json.toString()); json.getJSONArray("chapters").put(json.getJSONArray("chapters").getJSONObject(0))
        NovelPublicationMap(json)
    }
    @Test fun nativeCharacterAnchorSurvivesWindowChangeButNotChapterOrVersionChange() {
        val map = mapping()
        val native = JSONObject().put("schema", 1).put("chapter_id", "c1").put("version_id", "v1")
            .put("text_hash", "a".repeat(64)).put("offset", 321)
        val locator = JSONObject().put("href", "chapters/c1.xhtml").put("locations", JSONObject().put("progression", .4)).put("native_text", native)
        val remote = map.position(locator)!!
        val restored = map.locator(remote)!!
        assertEquals(321, restored.getJSONObject("native_text").getInt("offset"))
        assertFalse(restored.getJSONObject("locations").has("totalProgression"))
        native.put("chapter_id", "c2")
        assertFalse(map.locator(map.position(locator)!!)!!.has("native_text"))
        native.put("chapter_id", "c1").put("version_id", "v2")
        assertFalse(map.locator(map.position(locator)!!)!!.has("native_text"))
    }
}
