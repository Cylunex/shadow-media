package com.shadow.media.model

data class LiveLineHealth(val successes: Int = 0, val failures: Int = 0, val consecutiveFailures: Int = 0, val lastFailureMs: Long = 0)
fun liveLineKey(entry: ExternalMediaEntry) = "${entry.sourceId}:${entry.id}"
/** A switching session tries each line at most once; recent failures are ranked after healthy lines. */
fun chooseLiveLine(streams: List<ExternalMediaEntry>, attempted: Set<String>, health: (ExternalMediaEntry) -> LiveLineHealth, nowMs: Long): ExternalMediaEntry? = streams
    .filter { liveLineKey(it) !in attempted }
    .sortedWith(compareBy<ExternalMediaEntry> { val h = health(it); if (h.consecutiveFailures > 0 && nowMs - h.lastFailureMs < 60_000) 1 else 0 }
        .thenBy { health(it).consecutiveFailures }.thenBy { val h = health(it); h.failures.toDouble() / (h.successes + h.failures + 1) })
    .firstOrNull()
