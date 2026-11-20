package com.shadow.media.library

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import com.shadow.media.model.*

class LiveLineHealthStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("live_line_health", 0)
    private val siteTimes = context.applicationContext.getSharedPreferences("tvbox_resolution", 0)
    private fun key(entry: ExternalMediaEntry) = MessageDigest.getInstance("SHA-256").digest(liveLineKey(entry).toByteArray()).joinToString("") { "%02x".format(it) }
    fun resolveMs(source: String, site: String): Long? =
        siteTimes.getLong("resolve:$source:$site", -1).takeIf { it >= 0 }
    @Synchronized fun recordResolve(source: String, site: String, elapsed: Long) {
        val old = resolveMs(source, site) ?: elapsed
        siteTimes.edit().putLong("resolve:$source:$site", (old * 3 + elapsed) / 4).apply()
    }
    @Synchronized fun health(entry: ExternalMediaEntry): LiveLineHealth = runCatching {
        val value = JSONObject(preferences.getString(key(entry), "{}")!!)
        LiveLineHealth(value.optInt("successes"), value.optInt("failures"), value.optInt("consecutive"), value.optLong("failedAt"))
    }.getOrDefault(LiveLineHealth())
    @Synchronized fun record(entry: ExternalMediaEntry, success: Boolean) {
        val previous = health(entry)
        val value = JSONObject().put("successes", previous.successes + if (success) 1 else 0).put("failures", previous.failures + if (success) 0 else 1)
            .put("consecutive", if (success) 0 else previous.consecutiveFailures + 1).put("failedAt", if (success) previous.lastFailureMs else System.currentTimeMillis()).put("updated", System.currentTimeMillis())
        val editor = preferences.edit().putString(key(entry), value.toString())
        if (preferences.all.size > 512) preferences.all.entries.sortedBy { runCatching { JSONObject(it.value as String).optLong("updated") }.getOrDefault(0) }.take(64).forEach { editor.remove(it.key) }
        editor.apply()
    }
}
