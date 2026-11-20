package com.shadow.media

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
