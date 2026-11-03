package com.shadow.media

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.shadow.media.library.*
import org.json.JSONObject

/** Identity rules match association, while unknown authors keep their own source identity. */
internal fun novelResultGroups(rows: List<JSONObject>): List<List<JSONObject>> {
    val groups = linkedMapOf<String, MutableList<JSONObject>>()
    for (row in rows) {
        val work = JSONObject().put("title", row.optString("name")).put("author", row.optString("author"))
        fun normalized(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).trim().replace(Regex("[\\s\\p{Z}]+"), " ").lowercase(java.util.Locale.ROOT)
        val key = if (sameNovelIdentity(work, row)) normalized(row.optString("name")) + "\u0000" + normalized(row.optString("author").replace(Regex("^作者\\s*[:：]\\s*"), "")) else novelSourceIdentity(row)
        groups.getOrPut(key) { mutableListOf() }.add(row)
    }
    return groups.values.toList()
}

@Composable internal fun NovelSourceResultGroup(rows: List<JSONObject>, onDetail: (JSONObject) -> Unit,
    status: @Composable (JSONObject) -> Unit) {
    var expanded by remember(novelSourceIdentity(rows.first())) { mutableStateOf(false) }
    Column {
        (if (expanded) rows else rows.take(1)).forEach { row ->
            NovelSourceResultCard(row) { onDetail(row) }
            status(row)
        }
        if (rows.size > 1) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起 ${rows.size} 个来源" else "比较 ${rows.size} 个来源") }
    }
}
