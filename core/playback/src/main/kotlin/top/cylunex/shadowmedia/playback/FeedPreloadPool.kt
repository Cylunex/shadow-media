package top.cylunex.shadowmedia.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.datasource.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.*
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import top.cylunex.shadowmedia.model.*
import top.cylunex.shadowmedia.network.*

data class FeedPreloadMetrics(val bytes: Long = 0, val discardedBytes: Long = 0, val hits: Int = 0, val failures: Int = 0, val peakPssKb: Int = 0)

/** Session-owned, in-memory only. Builder supplies both the preload manager and every feed player. */
class FeedPreloadPool(context: Context, private val session: EmbySession, identity: ClientIdentity,
    private val allowed: () -> Boolean, private val record: (FeedPreloadMetrics) -> Unit = {}) : Closeable {
    private data class Entry(val plan: PlaybackPlan, val item: MediaItem, val index: Int, val createdAt: Long,
        val bytes: AtomicLong = AtomicLong(), var used: Boolean = false, var failed: Boolean = false)
    private val entries = linkedMapOf<String, Entry>()
    @Volatile private var current = 0
    private var settled = false
    private val totalBytes = AtomicLong()
    private val metricsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var memorySample: Job? = null
    private var closed = false
    private val mutableMetrics = MutableStateFlow(FeedPreloadMetrics())
    val metrics = mutableMetrics.asStateFlow()
    private val origin = session.serverUrl.toHttpUrl()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(OfflineModeInterceptor())
        .addInterceptor(ResilientPlaybackHttpInterceptor(origin) {})
        .addNetworkInterceptor(ScopedPlaybackHeadersInterceptor(PlaybackHeaderPolicy(origin, session, identity))).build()
    private val builder = DefaultPreloadManager.Builder(context, TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> { index ->
        when (FeedPreloadPolicy.target(index, current, settled, true, !closed && allowed())) {
            FeedPreloadTarget.NEXT_THREE_SECONDS -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3000)
            FeedPreloadTarget.TRACKS -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
            FeedPreloadTarget.NONE -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }
    })
    private val manager = builder.build().apply { addListener(object : PreloadManagerListener {
        override fun onError(error: PreloadException) {
            entries[error.mediaItem.mediaId]?.failed = true
            mutableMetrics.value = mutableMetrics.value.copy(failures = mutableMetrics.value.failures + 1)
        }
    }) }
    fun buildPlayer(): ExoPlayer = builder.buildExoPlayer()
    fun position(index: Int, stoppedScrolling: Boolean, nextItemId: String?) {
        if (closed) return
        current = index; settled = stoppedScrolling
        val keep = if (stoppedScrolling && allowed()) (index - 1)..(index + 1) else index..index
        entries.values.filter { it.index !in keep || (it.index == index + 1 && it.plan.itemId != nextItemId) }.toList().forEach(::remove)
        manager.setCurrentPlayingIndex(index); manager.invalidate()
        publish()
    }
    private fun remove(entry: Entry) {
        manager.remove(entry.item); entries.remove(entry.plan.itemId)
        if (!entry.used) mutableMetrics.value = mutableMetrics.value.copy(discardedBytes = mutableMetrics.value.discardedBytes + entry.bytes.get())
    }
    fun add(plan: PlaybackPlan, index: Int, resolutionMs: Long) {
        if (closed || !settled || !allowed() || index != current + 1 || !FeedPreloadPolicy.eligible(plan, resolutionMs)) return
        val url = plan.primary.url.toHttpUrl()
        if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port) return
        entries[plan.itemId]?.let { if (it.plan == plan && it.index == index) return else remove(it) }
        entries.values.filter { it.index == index }.toList().forEach(::remove)
        val item = MediaItem.Builder().setMediaId(plan.itemId).setUri(plan.primary.url).build()
        val entry = Entry(plan, item, index, SystemClock.elapsedRealtime())
        entries[plan.itemId] = entry
        val entryClient = client.newBuilder().addInterceptor(ResourceBudgetInterceptor { if (entry.index == current) ResourcePriority.FOREGROUND else ResourcePriority.ADJACENT }).build()
        val factory = DataSource.Factory {
            OkHttpDataSource.Factory(entryClient).setDefaultRequestProperties(plan.primary.requiredHeaders).createDataSource().apply {
                addTransferListener(object : TransferListener {
                    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                        if (entry.index != current) { entry.bytes.addAndGet(bytesTransferred.toLong()); totalBytes.addAndGet(bytesTransferred.toLong()) }
                    }
                })
            }
        }
        manager.add(DefaultMediaSourceFactory(factory).createMediaSource(item), index); manager.invalidate()
    }
    fun preparedPlan(account: EmbySession, itemId: String): PlaybackPlan? {
        if (closed || account.providerId != session.providerId || NetworkPolicy.offlineOnly) return null
        val entry = entries[itemId] ?: return null
        if (entry.failed || SystemClock.elapsedRealtime() - entry.createdAt > 60_000) { remove(entry); return null }
        return entry.plan
    }
    internal fun source(plan: PlaybackPlan, candidate: PlaybackCandidate): androidx.media3.exoplayer.source.MediaSource? {
        if (closed || candidate != plan.primary) return null
        val entry = entries[plan.itemId]?.takeIf { it.plan == plan && !it.failed } ?: return null
        val source = manager.getMediaSource(entry.item) ?: return null
        if (!entry.used) { entry.used = true; mutableMetrics.value = mutableMetrics.value.copy(hits = mutableMetrics.value.hits + 1) }
        publish()
        return source
    }
    private fun publish() {
        val value = mutableMetrics.value
        mutableMetrics.value = value.copy(bytes = totalBytes.get())
        record(mutableMetrics.value)
        if (!closed && memorySample?.isActive != true) memorySample = metricsScope.launch {
            val pss = android.os.Debug.getPss().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            mutableMetrics.update { it.copy(peakPssKb = maxOf(it.peakPssKb, pss)) }
            record(mutableMetrics.value)
        }
    }
    override fun close() {
        if (closed) return
        settled = false; closed = true
        entries.values.toList().forEach(::remove); publish(); manager.release(); client.dispatcher.cancelAll(); metricsScope.cancel()
    }
}
