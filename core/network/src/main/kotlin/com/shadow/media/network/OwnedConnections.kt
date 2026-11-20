package com.shadow.media.network

import okhttp3.OkHttpClient

/** Releases a dedicated client. Derived clients sharing its dispatcher must already be idle. */
fun OkHttpClient.closeOwnedConnections() {
    val cleanup = dispatcher.executorService
    // Evicting a pooled TLS socket can send close_notify. Compose disposes the player on main.
    cleanup.execute {
        dispatcher.cancelAll()
        connectionPool.evictAll()
    }
    cleanup.shutdown()
}
