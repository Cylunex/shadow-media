package top.cylunex.shadowmedia

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import top.cylunex.shadowmedia.model.FeatureId
import top.cylunex.shadowmedia.playback.FeedPreloadPool
import top.cylunex.shadowmedia.network.ResourceScheduler
import top.cylunex.shadowmedia.network.ResourcePriority

@Composable internal fun rememberFeedPreloading(state: MainUiState, viewModel: MainViewModel, container: AppContainer, scrolling: Boolean): FeedPreloadPool? {
    val flags by viewModel.featureFlags.collectAsStateWithLifecycle()
    val session = state.session
    if (!state.feedMode || flags[FeatureId.FEED_PRELOAD] != true || session == null) return null
    val context = LocalContext.current.applicationContext
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val unmetered by produceState(initialValue = connectivity.hasUnmeteredNetwork(), connectivity) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) { value = connectivity.hasUnmeteredNetwork() }
            override fun onLost(network: android.net.Network) { value = connectivity.hasUnmeteredNetwork() }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        awaitDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    var deviceAllowed by remember { mutableStateOf(container.backgroundResourcesAllowed()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(foreground, container) {
        if (foreground) while (true) { deviceAllowed = container.backgroundResourcesAllowed(); delay(5000) }
    }
    val pool = remember(session, lifecycle, container) {
        FeedPreloadPool(context, session, container.clientIdentity,
            allowed = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && connectivity.hasUnmeteredNetwork() && container.backgroundResourcesAllowed() },
            record = { metrics -> context.getSharedPreferences("feed_preload_metrics", Context.MODE_PRIVATE).edit()
                .putLong("bytes", metrics.bytes).putLong("discardedBytes", metrics.discardedBytes).putInt("hits", metrics.hits)
                .putInt("failures", metrics.failures).putInt("peakPssKb", metrics.peakPssKb).apply() })
    }
    DisposableEffect(pool) {
        viewModel.feedPreloader = pool
        onDispose { if (viewModel.feedPreloader === pool) viewModel.feedPreloader = null; pool.close() }
    }
    val next = state.items.getOrNull(state.currentIndex + 1)
    LaunchedEffect(pool, state.currentIndex, next?.id, next?.playbackPositionTicks, scrolling, foreground, unmetered, deviceAllowed) {
        pool.position(state.currentIndex, !scrolling && foreground && unmetered && deviceAllowed,
            next?.takeIf { it.playbackPositionTicks == 0L }?.id)
        if (scrolling || !foreground || !unmetered || !deviceAllowed) return@LaunchedEffect
        delay(600)
        next ?: return@LaunchedEffect
        if (next.playbackPositionTicks > 0 || !container.backgroundResourcesAllowed() || !connectivity.hasUnmeteredNetwork()) return@LaunchedEffect
        try {
            val start = SystemClock.elapsedRealtime()
            val plan = ResourceScheduler.process.run(ResourcePriority.ADJACENT) { container.embyRepository.playbackPlan(session, next.id) }
            pool.add(plan, state.currentIndex + 1, SystemClock.elapsedRealtime() - start)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Foreground resolution remains independent and can retry. */ }
    }
    return pool
}

private fun ConnectivityManager.hasUnmeteredNetwork(): Boolean =
    getNetworkCapabilities(activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
