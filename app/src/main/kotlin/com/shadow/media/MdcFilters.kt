package com.shadow.media

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.shadow.media.model.UnifiedMediaItem
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal data class MdcFilters(
    val genre: String = "",
    val form: String = "",
    val region: String = "",
    val unknownYear: Boolean = false,
    val status: String = "all",
    val actor: String = "",
    val year: String = "",
    val decade: String = "",
    val studio: String = "",
    val series: String = "",
    val label: String = "",
    val platform: String = "",
    val tag: String = "",
    val sort: String = "added",
) : java.io.Serializable {
    fun request(query: String) = JSONObject().apply {
        put("q", query).put("sort", sort).put("unknown_year", unknownYear).put("status", status)
        for ((key, value) in listOf("genres" to genre, "forms" to form, "regions" to region,
            "organizations" to studio, "series" to series, "labels" to label, "platforms" to platform,
            "tags" to tag, "actors" to actor)) {
            if (value.isNotBlank()) put(key, JSONArray().put(value))
        }
        (year.toIntOrNull() ?: decade.toIntOrNull())?.let {
            put("year_from", it).put("year_to", if (year.isNotBlank()) it else it + 9)
        }
    }
}

internal data class MdcWorkFilter(val caption: String, val filters: MdcFilters) {
    companion object {
        fun from(dimension: String, id: String, name: String): MdcWorkFilter? = when (dimension) {
            "actor" -> MdcWorkFilter("演员 · " + name, MdcFilters(actor = id))
            "series" -> MdcWorkFilter("系列 · " + name, MdcFilters(series = id))
            "studio" -> MdcWorkFilter("制作 · " + name, MdcFilters(studio = id))
            "label" -> MdcWorkFilter("厂牌 · " + name, MdcFilters(label = id))
            else -> null
        }
    }
}

@Composable internal fun MdcRelatedFilters(provider: MdcMediaProvider, item: UnifiedMediaItem, select: (MdcFilters) -> Unit) {
    var attempt by remember(item.key) { mutableIntStateOf(0) }
    var links by remember(item.key) { mutableStateOf<List<MdcWorkFilter>?>(null) }
    var failed by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(provider, item.key, attempt) {
        if (attempt > 0) {
            failed = false
            try { links = provider.workFilters(item.key) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
    }
    if (links == null) TextButton(enabled = attempt == 0 || failed, onClick = { attempt++ }) {
        Text(if (failed) "关联筛选暂不可用 · 重试" else "按同演员、系列或厂牌找片")
    }
    if (links?.isEmpty() == true) Text("此作品暂无关联筛选")
    links.orEmpty().forEach { link -> TextButton(onClick = { select(link.filters) }) { Text(link.caption) } }
}
