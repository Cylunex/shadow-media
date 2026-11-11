package com.shadow.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.json.JSONArray

internal data class MdcTagNode(val id: String, val name: String, val count: Int, val parentId: String?)

internal class MdcTagTree(values: JSONArray?) {
    val nodes = (0 until (values?.length() ?: 0)).map { index ->
        val value = values!!.getJSONObject(index)
        MdcTagNode(value.getString("id"), value.getString("name"), value.optInt("count"),
            value.optString("parent_id").takeUnless { value.isNull("parent_id") || it.isBlank() })
    }
    val byId = nodes.associateBy { it.id }
    private val branches = nodes.groupBy { node -> node.parentId?.takeIf { it != node.id && it in byId } }

    fun children(parentId: String?): List<MdcTagNode> = branches[parentId].orEmpty()

    fun path(id: String): List<MdcTagNode> {
        val result = mutableListOf<MdcTagNode>()
        val seen = mutableSetOf<String>()
        var node = byId[id]
        while (node != null && seen.add(node.id) && result.size < 64) {
            result.add(node)
            node = byId[node.parentId]
        }
        return result.reversed()
    }
}

@Composable
internal fun MdcTagFilters(values: JSONArray?, selected: String, onSelect: (String) -> Unit) {
    val tree = remember(values) { MdcTagTree(values) }
    var browsing by rememberSaveable { mutableStateOf<String?>(null) }
    val parent = tree.byId[browsing]
    val children = tree.children(parent?.id)
    if (tree.nodes.isEmpty()) return
    Text("标签树", style = MaterialTheme.typography.titleSmall)
    if (parent != null) {
        LazyRow {
            item { TextButton(onClick = { browsing = null }) { Text("全部标签") } }
            items(tree.path(parent.id), key = { it.id }) { node ->
                TextButton(onClick = { browsing = node.id }) { Text("› ${node.name}") }
            }
        }
        FilterChip(selected == parent.id, { onSelect(if (selected == parent.id) "" else parent.id) },
            { Text("${parent.name}全部 · ${parent.count}") })
    }
    tree.byId[selected]?.let { node ->
        FilterChip(true, { onSelect("") }, { Text("已选：${tree.path(node.id).joinToString(" › ") { it.name }} · 清除") })
    }
    LazyRow {
        items(children, key = { it.id }) { node ->
            Column {
                FilterChip(selected == node.id, { onSelect(if (selected == node.id) "" else node.id) },
                    { Text("${node.name} · ${node.count}") })
                if (tree.children(node.id).isNotEmpty()) {
                    TextButton(onClick = { browsing = node.id }) { Text("下级标签 ›") }
                }
            }
        }
    }
}
