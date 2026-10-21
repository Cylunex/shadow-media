package com.shadow.media

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume

internal data class LanServer(val id: String, val name: String, val address: String, val webdav: Boolean)

/** DNS-SD discovers advertised services. It does not probe every device or guess DAV credentials. */
@Suppress("DEPRECATION")
internal fun discoverLanServers(context: Context) = callbackFlow {
    val nsd = context.getSystemService(NsdManager::class.java)
    val multicast = context.applicationContext.getSystemService(WifiManager::class.java)
        .createMulticastLock("shadow-media-discovery").apply { setReferenceCounted(false); acquire() }
    val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    val queue = Channel<NsdServiceInfo>(64)
    val servers = linkedMapOf<String, LanServer>()
    val seen = mutableSetOf<String>()
    val resolver = launch {
        for (info in queue) {
            val resolved = withTimeoutOrNull(5000) {
                suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
                    try {
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { if (continuation.isActive) continuation.resume(null) }
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) { if (continuation.isActive) continuation.resume(serviceInfo) }
                        })
                    } catch (_: Exception) { if (continuation.isActive) continuation.resume(null) }
                }
            } ?: continue
            val host = resolved.host?.hostAddress ?: continue
            val scheme = if (info.serviceType.contains("webdavs") || info.serviceType.contains("https")) "https" else "http"
            val authority = if (host.contains(':')) "[$host]" else host
            val path = resolved.attributes["path"]?.decodeToString().orEmpty().let { if (it.startsWith('/')) it else "/" }
            val address = runCatching { "$scheme://$authority:${resolved.port}/".toHttpUrlOrNull()?.newBuilder()?.encodedPath(path)?.build()?.toString() }.getOrNull() ?: continue
            val id = "${info.serviceType}:${info.serviceName}"
            if (!synchronized(seen) { id in seen }) continue
            synchronized(servers) { servers[id] = LanServer(id, info.serviceName, address, info.serviceType.contains("webdav")); trySend(servers.values.toList()) }
        }
    }
    try {
        for (type in listOf("_webdav._tcp.", "_webdavs._tcp.", "_http._tcp.", "_https._tcp.")) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { runCatching { nsd.stopServiceDiscovery(this) } }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val id = "${serviceInfo.serviceType}:${serviceInfo.serviceName}"
                    synchronized(seen) { if (seen.add(id)) queue.trySend(serviceInfo) }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val id = "${serviceInfo.serviceType}:${serviceInfo.serviceName}"
                    synchronized(seen) { seen.remove(id) }
                    synchronized(servers) { servers.remove(id); trySend(servers.values.toList()) }
                }
            }
            listeners += listener
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        }
        awaitClose { }
    } finally {
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        resolver.cancel(); queue.close()
        if (multicast.isHeld) multicast.release()
    }
}
