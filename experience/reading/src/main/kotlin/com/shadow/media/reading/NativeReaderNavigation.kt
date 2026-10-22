package com.shadow.media.reading

import org.json.JSONArray
import org.json.JSONObject

internal data class NativeReaderLocation(val assetId: String, val locator: String) {
    fun json(): JSONObject = JSONObject().put("asset", assetId).put("locator", JSONObject(locator))
}

/** Explicit jumps are reversible; normal page turns and automatic chapter boundaries are not jumps. */
internal data class NativeJumpHistory(val locations: List<NativeReaderLocation> = emptyList()) {
    fun remember(origin: NativeReaderLocation?, destination: NativeReaderLocation?): NativeJumpHistory =
        if (origin == null || origin == destination || locations.lastOrNull() == origin) this
        else NativeJumpHistory((locations + origin).takeLast(20))

    fun toJson(): String = JSONArray().also { array -> locations.forEach { array.put(it.json()) } }.toString()
    companion object {
        fun restore(json: String?): NativeJumpHistory = runCatching {
            val array = JSONArray(json ?: "[]")
            NativeJumpHistory(((array.length() - 20).coerceAtLeast(0) until array.length()).map { index ->
                val item = array.getJSONObject(index)
                NativeReaderLocation(item.getString("asset"), item.getJSONObject("locator").toString())
            })
        }.getOrDefault(NativeJumpHistory())
    }
}
