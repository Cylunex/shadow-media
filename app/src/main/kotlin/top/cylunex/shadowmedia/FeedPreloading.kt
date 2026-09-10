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
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val flags by viewModel.featureFlags.collectAsStateWithLifecycle()
    val enabled = state.feedMode && flags[FeatureId.FEED_PRELOAD] == true
    val session = state.session
    val pool = remember(session, enabled) {
        session?.takeIf { enabled }?.let { FeedPreloadPool(context, it, container.clientIdentity,
            allowed = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered && container.backgroundResourcesAllowed() },
            record = { metrics -> context.getSharedPreferences("feed_preload_metrics", Context.MODE_PRIVATE).edit()
                .putLong("bytes", metrics.bytes).putLong("discardedBytes", metrics.discardedBytes).putInt("hits", metrics.hits)
                .putInt("failures", metrics.failures).putInt("peakPssKb", metrics.peakPssKb).apply() }) }
    }
    DisposableEffect(pool) {
        viewModel.feedPreloader = pool
        onDispose { if (viewModel.feedPreloader === pool) viewModel.feedPreloader = null; pool?.close() }
    }
    LaunchedEffect(pool, state.currentIndex, scrolling, foreground) {
        pool ?: return@LaunchedEffect
        pool.position(state.currentIndex, !scrolling && foreground)
        if (scrolling || !foreground) return@LaunchedEffect
        delay(600)
        val next = state.items.getOrNull(state.currentIndex + 1) ?: return@LaunchedEffect
        if (next.playbackPositionTicks > 0 || !container.backgroundResourcesAllowed() || context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered) return@LaunchedEffect
        try {
            val start = SystemClock.elapsedRealtime()
            val plan = ResourceScheduler.process.run(ResourcePriority.ADJACENT) { container.embyRepository.playbackPlan(requireNotNull(session), next.id) }
            pool.add(plan, state.currentIndex + 1, SystemClock.elapsedRealtime() - start)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Foreground resolution remains independent and can retry. */ }
    }
    return pool
}
