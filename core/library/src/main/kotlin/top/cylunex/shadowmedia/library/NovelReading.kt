package top.cylunex.shadowmedia.library

import org.json.JSONObject
import java.net.URI

/** Immutable package coordinates. Only an exact, unique href maps to a chapter. */
class NovelPublicationMap(val json: JSONObject) {
    val workId = json.getString("work_id")
    val versionId = json.getString("version_id")
    val chapters = json.novelObjects("chapters")
    val complete = json.getBoolean("complete")
    val versionComplete = json.getBoolean("version_complete")
    init {
        require(json.getString("schema") == "shadow.novel.publication/v1") { "阅读包映射格式不受支持，请更新 Novel 服务" }
        require(chapters.isNotEmpty() && chapters.map { it.getString("id") }.distinct().size == chapters.size) { "阅读包章节为空或标识重复" }
        require(chapters.map { it.getString("href") }.distinct().size == chapters.size) { "阅读包存在重复的章节路径" }
        chapters.forEach { require(it.getString("href") == "chapters/${it.getString("id")}.xhtml") { "阅读包章节路径与章节标识不匹配" } }
    }
    fun chapter(id: String) = chapters.singleOrNull { it.getString("id") == id }
    fun chapterForHref(href: String): JSONObject? {
        val path = runCatching { URI(href).path }.getOrNull() ?: return null
        return chapters.singleOrNull { chapter ->
            val target = chapter.getString("href")
            path == target || path.endsWith("/$target")
        }
    }
    fun position(locator: JSONObject): JSONObject? {
        val chapter = chapterForHref(locator.optString("href")) ?: return null
        val ratio = locator.optJSONObject("locations")?.optDouble("progression", Double.NaN) ?: Double.NaN
        if (!ratio.isFinite() || ratio !in 0.0..1.0) return null
        val remoteLocator = JSONObject(locator.toString()).put("href", chapter.getString("href"))
        val fragment = runCatching { URI(locator.optString("href")).fragment }.getOrNull()
        if (!fragment.isNullOrBlank() && fragment.length <= 2048 && remoteLocator.getJSONObject("locations").optJSONArray("fragments") == null)
            remoteLocator.getJSONObject("locations").put("fragments", org.json.JSONArray().put(fragment))
        var anchor = JSONObject().put("media_locator", remoteLocator)
        if (anchor.toString().toByteArray(Charsets.UTF_8).size > 8192) anchor = JSONObject().put("media_locator", JSONObject()
            .put("href", chapter.getString("href")).put("type", "application/xhtml+xml")
            .put("locations", JSONObject().put("progression", ratio)))
        return JSONObject().put("version_id", versionId).put("chapter_id", chapter.getString("id"))
            .put("ratio", ratio).put("anchor", anchor)
    }
    fun locator(position: JSONObject): JSONObject? {
        if (position.optString("version_id") != versionId) return null
        val chapter = chapter(position.optString("chapter_id")) ?: return null
        val ratio = position.optDouble("ratio", 0.0).takeIf { it.isFinite() && it in 0.0..1.0 } ?: return null
        val prior = position.optJSONObject("anchor")?.optJSONObject("media_locator")
        // Package totalProgression and position indexes belong to a specific window. Never restore them across packages.
        val result = JSONObject().put("href", chapter.getString("href")).put("type", "application/xhtml+xml")
            .put("title", chapter.optString("title")).put("locations", JSONObject().put("progression", ratio))
        if (prior != null && chapterForHref(prior.optString("href"))?.optString("id") == chapter.getString("id")) {
            prior.optJSONObject("text")?.let { result.put("text", it) }
            // Character anchors are independent of the downloaded EPUB window and page geometry.
            prior.optJSONObject("native_text")?.takeIf {
                it.optInt("schema") == 1 && it.optString("chapter_id") == chapter.getString("id") &&
                    it.optString("version_id") == versionId && it.optString("text_hash").matches(Regex("[a-f0-9]{64}")) &&
                    it.optInt("offset", -1) >= 0
            }?.let { result.put("native_text", JSONObject(it.toString())) }
            prior.optJSONObject("locations")?.optJSONArray("fragments")?.let { result.getJSONObject("locations").put("fragments", it) }
        }
        return result
    }
}

/** Navigator emits a position while opening/reflowing. Only a subsequent movement becomes a mutation. */
class NovelLocatorTracker(private val mapping: NovelPublicationMap) {
    private var initialized = false
    private var previous: JSONObject? = null
    fun moved(locator: JSONObject): Boolean {
        val position = mapping.position(locator) ?: return false
        val changed = previous?.optString("chapter_id") != position.getString("chapter_id") || previous?.optDouble("ratio") != position.getDouble("ratio")
        previous = position
        val accept = initialized && changed
        initialized = true
        return accept
    }
}
