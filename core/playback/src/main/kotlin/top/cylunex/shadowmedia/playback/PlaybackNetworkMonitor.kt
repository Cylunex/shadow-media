package top.cylunex.shadowmedia.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.io.Closeable

/** Uses the app's routed network; LAN playback does not require Internet validation. */
internal class PlaybackNetworkMonitor(context: Context, private val onAvailable: (Boolean) -> Unit) : Closeable {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var current: Network? = null
    private var closed = false
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { current = network }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!closed && current == network) onAvailable(true)
        }
        override fun onLost(network: Network) {
            if (!closed && current == network) { current = null; onAvailable(false) }
        }
    }

    init {
        try {
            current = manager?.activeNetwork
            manager?.registerDefaultNetworkCallback(callback, handler)
            registered = manager != null
            if (registered) onAvailable(current != null)
        } catch (_: RuntimeException) {
            // Permission/service/callback quota failures leave explicit retry available.
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
    }
}
