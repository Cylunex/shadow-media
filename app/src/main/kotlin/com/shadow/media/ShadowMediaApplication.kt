package com.shadow.media
import com.shadow.media.network.ResourceScheduler
import com.shadow.media.network.ResourcePriority

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import com.shadow.media.network.ClientIdentity
import com.shadow.media.network.DefaultEmbyRepository
import com.shadow.media.network.EmbyRepository
import com.shadow.media.network.KeystoreSessionStore
import com.shadow.media.network.PersistentPlaybackOutbox
import com.shadow.media.network.PlaybackOutbox
import com.shadow.media.network.SafeExternalSourceRepository
import com.shadow.media.network.DefaultLiveGuideRepository
import com.shadow.media.network.LiveGuideRepository
import com.shadow.media.network.DefaultIntegrationRepository
import com.shadow.media.network.KeystoreIntegrationStore
import com.shadow.media.network.SessionStore
import com.shadow.media.network.SharedPreferencesExternalSourceStore
import com.shadow.media.network.DefaultNetworkStorageRepository
import com.shadow.media.network.KeystoreNetworkStorageStore
import com.shadow.media.database.LocalMediaStateRepository
import com.shadow.media.database.ShadowMediaDatabase
import com.shadow.media.database.migrateAccountScopes
import kotlinx.coroutines.async
import com.shadow.media.provider.InMemoryProviderRegistry
import com.shadow.media.provider.AggregateSearchEngine
import com.shadow.media.database.MediaHistoryEntity
import com.shadow.media.model.EmbySession
import com.shadow.media.model.ExternalMediaEntry
import com.shadow.media.model.MediaItem

open class ShadowMediaApplication : Application(), coil3.SingletonImageLoader.Factory {
    override fun newImageLoader(context: android.content.Context): coil3.ImageLoader = coil3.ImageLoader.Builder(context)
        .components { add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { okhttp3.OkHttpClient.Builder().addInterceptor(com.shadow.media.network.OfflineModeInterceptor()).addInterceptor(com.shadow.media.network.ResourceBudgetInterceptor { com.shadow.media.network.ResourcePriority.VISIBLE }).build() })) }.build()
    val container: AppContainer by lazy { AppContainer(this) }
    override fun onCreate() { super.onCreate(); container.initializeLibraryResources() }
}

class AppContainer(private val application: Application) {
    val library = com.shadow.media.library.LibraryRepository(application)
    val lineHealth = com.shadow.media.library.LiveLineHealthStore(application)
    val videoPreferences = com.shadow.media.playback.VideoPreferenceStore.get(application)
    val playbackNetwork = kotlinx.coroutines.flow.MutableStateFlow(true)
    private val playbackNetworkMonitor = com.shadow.media.playback.PlaybackNetworkMonitor(application) { playbackNetwork.value = it }
    val videoQueue = com.shadow.media.library.VideoQueueStore(application)
    val localVideos = com.shadow.media.library.LocalVideoRepository(application)
    val offline = com.shadow.media.library.OfflineRepository(application, library)
    val music = com.shadow.media.library.MusicRepository(application, library)
    val catalogs = com.shadow.media.library.NativeCatalogRepository(application, library)
    val novel = com.shadow.media.library.NovelRepository(application, library)
    val playlistExports = com.shadow.media.library.PlaylistExportRepository(application, catalogs, library)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = ShadowMediaDatabase.create(application)
    val localMediaState = LocalMediaStateRepository(database.dao())
    val providerRegistry = InMemoryProviderRegistry()
    val aggregateSearchEngine = AggregateSearchEngine()
    val handoffInbox = HandoffInbox()
    val playbackTelemetry = RoomPlaybackTelemetrySink(localMediaState, applicationScope)
    val clientIdentity = ClientIdentity(
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        deviceId = persistentDeviceId(application),
        version = BuildConfig.VERSION_NAME,
    )
    val sessionStore: SessionStore = KeystoreSessionStore(application)
    val accountScopesReady = applicationScope.async { database.migrateAccountScopes(sessionStore.loadAll()); catalogs.migrateOpdsReferences() }
    val embyRepository: EmbyRepository = com.shadow.media.network.CachedEmbyRepository(DefaultEmbyRepository(
        client = OkHttpClient.Builder().addInterceptor(com.shadow.media.network.OfflineModeInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
        clientIdentity = clientIdentity,
    ), com.shadow.media.network.EmbyCatalogCache(database.libraryStateDao()))
    val playbackOutbox: PlaybackOutbox = PersistentPlaybackOutbox(application, embyRepository)
    private val audioReporter = EmbyAudioReporter(applicationScope, playbackOutbox)
    val feedSessionStore = FeedSessionStore(application)
    val externalClient = OkHttpClient.Builder().addInterceptor(com.shadow.media.network.OfflineModeInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    val externalSourceRepository = SafeExternalSourceRepository(externalClient)
    val liveGuideRepository: LiveGuideRepository = DefaultLiveGuideRepository(externalClient)
    val integrationRepository = DefaultIntegrationRepository(externalClient)
    val integrationStore = KeystoreIntegrationStore(application)
    val externalSourceStore = SharedPreferencesExternalSourceStore(application)
    val networkStorageStore = KeystoreNetworkStorageStore(application)
    val networkStorageRepository = DefaultNetworkStorageRepository(application, externalClient) { providerId, itemId ->
        localMediaState.history("$providerId:$itemId")?.let { history ->
            (if (history.completed) 0L else history.positionMs) to history.completed
        }
    }

    val castPlayback by lazy { CastPlaybackController(application, networkStorageRepository) { entry, position, duration ->
        recordExternalHistory(entry, position, duration, fromCast = true)
        entry.queueEntryId?.let { videoQueue.position(it, position) }
    } }

    fun backgroundResourcesAllowed() = com.shadow.media.library.ResourceDevicePolicy.backgroundAllowed(application)

    fun initializeLibraryResources() {
        com.shadow.media.library.LibraryResources.offlineOnly = application.getSharedPreferences("resource_policy", 0).getBoolean("offlineOnly", false)
        com.shadow.media.library.NovelRepository.schedule(application)
        val offlineMedia = com.shadow.media.audio.MediaOfflineStore.get(application)
        offlineMedia.installPlaybackRoute()
        com.shadow.media.library.LibraryResources.mediaOffline = offlineMedia::command
        com.shadow.media.library.LibraryResources.hasOfflineMedia = offlineMedia::available
        com.shadow.media.library.LibraryResources.lyricsResolver = { asset ->
            if (asset.providerId.startsWith("catalog:JELLYFIN:") || asset.providerId.startsWith("catalog:OPENSUBSONIC:")) {
                catalogs.musicProvider(catalogs.connection(asset.providerId)).lyrics(asset.itemId)
            } else null
        }
        if (com.shadow.media.library.LibraryResources.offlineOnly) offlineMedia.setOfflineMode(true)
        com.shadow.media.library.LibraryResources.networkStorage = networkStorageRepository
        com.shadow.media.library.LibraryResources.audioEvent = audioReporter::progress
        com.shadow.media.library.LibraryResources.audioCandidateSelected = audioReporter::selected
        com.shadow.media.library.LibraryResources.pageManifest = catalogs::pages
        com.shadow.media.library.LibraryResources.pageReader = { asset, page, file ->
            catalogs.page(asset, page, file)
        }
        applicationScope.launch {
            while (true) {
                try { catalogs.flush() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { /* Keep persisted operations for reconnect. */ }
                sessionStore.loadAll().forEach { account ->
                    embyRepository.flushUserStates(account)
                    for (operation in library.dao.pending(account.providerId).filter { it.kind == "offline-audio" }) {
                        if (operation.nextAttemptAt > System.currentTimeMillis()) continue
                        try {
                            val asset = library.dao.asset(operation.target)
                            if (asset != null) {
                                val value = org.json.JSONObject(operation.payload)
                                embyRepository.updateAudioPosition(account, asset.itemId, value.optLong("positionMs"), value.optBoolean("completed"))
                            }
                            library.dao.acknowledge(operation.id)
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { library.dao.retry(operation.id, System.currentTimeMillis() + 30_000L * (operation.attempts + 1).coerceAtMost(10)) }
                    }
                }
                kotlinx.coroutines.delay(30_000)
            }
        }
        com.shadow.media.library.LibraryResources.resolver = { requestedAsset ->
            accountScopesReady.await()
            val asset = library.dao.asset(requestedAsset.id) ?: requestedAsset
            val key = com.shadow.media.model.MediaKey(asset.providerId, asset.itemId)
            val candidates = when {
                asset.providerId.startsWith("catalog:") -> listOf(catalogs.resolve(asset))
                asset.providerId.startsWith("storage:") -> {
                    val connection = requireNotNull(networkStorageStore.loadAll().firstOrNull { "storage:${it.id}" == asset.providerId }) { "网络存储连接已移除" }
                    networkStorageRepository.provider(connection).resolve(com.shadow.media.model.UnifiedPlaybackRequest(key))
                }
                asset.providerId.startsWith("emby:") -> {
                    val session = requireNotNull(sessionStore.loadAll().firstOrNull { it.providerId == asset.providerId }) { "Emby 账号已移除" }
                    if (asset.kind in setOf("MUSIC", "AUDIOBOOK", "PODCAST")) embyRepository.audioPlan(session, asset.itemId).candidates else embyRepository.playbackPlan(session, asset.itemId).candidates
                }
                else -> requireNotNull(providerRegistry.provider(asset.providerId)) { "来源不可用，请重新连接" }.resolve(com.shadow.media.model.UnifiedPlaybackRequest(key))
            }
            requireNotNull(candidates.firstOrNull()) { "来源没有返回资源" }
        }
        com.shadow.media.library.LibraryResources.audioResolver = { requestedAsset, entryId ->
            accountScopesReady.await()
            val asset = library.dao.asset(requestedAsset.id) ?: requestedAsset
            if (asset.providerId.startsWith("catalog:JELLYFIN:") || asset.providerId.startsWith("catalog:OPENSUBSONIC:")) {
                catalogs.musicProvider(catalogs.connection(asset.providerId)).resolve(com.shadow.media.model.UnifiedPlaybackRequest(com.shadow.media.model.MediaKey(asset.providerId, asset.itemId)))
            } else if (asset.providerId.startsWith("emby:")) {
                val session = requireNotNull(sessionStore.loadAll().firstOrNull { it.providerId == asset.providerId }) { "Emby 账号已移除" }
                val plan = embyRepository.audioPlan(session, asset.itemId)
                require(plan.candidates.isNotEmpty()) { "来源没有返回资源" }
                audioReporter.resolved(entryId, session, plan)
                plan.candidates
            } else {
                val provider = providerRegistry.provider(asset.providerId)
                if (provider != null) provider.resolve(com.shadow.media.model.UnifiedPlaybackRequest(com.shadow.media.model.MediaKey(asset.providerId, asset.itemId)))
                else listOf(requireNotNull(com.shadow.media.library.LibraryResources.resolver)(asset))
            }
        }

    }

    fun recordEmbyHistory(session: EmbySession, item: MediaItem, positionMs: Long, durationMs: Long?) {
        applicationScope.launch {
            val effectivePosition = castPlayback.returnedPosition(session.providerId, item.id) ?: positionMs
            localMediaState.recordHistory(
                MediaHistoryEntity(
                    stableKey = "${session.providerId}:${item.id}",
                    providerId = session.providerId,
                    itemId = item.id,
                    title = item.name,
                    subtitle = item.seriesName,
                    positionMs = effectivePosition,
                    durationMs = durationMs,
                    completed = durationMs?.let { it > 0 && effectivePosition >= it * 0.92 } ?: false,
                    lastPlayedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }
    }

    fun recordExternalHistory(entry: ExternalMediaEntry, positionMs: Long, durationMs: Long?, fromCast: Boolean = false) {
        applicationScope.launch {
            val providerId = entry.sourceId.takeIf { entry.url.startsWith("shadow-cached:") || it == "local-video" || it.startsWith("catalog:") || it.startsWith("emby:") || it.startsWith("storage:") || it.startsWith("live:") }
                ?: "external:${entry.sourceId}"
            val effectivePosition = (if (fromCast) null else castPlayback.returnedPosition(providerId, entry.id)) ?: positionMs
            localMediaState.recordHistory(
                MediaHistoryEntity(
                    stableKey = "$providerId:${entry.id}",
                    providerId = providerId,
                    itemId = entry.id,
                    title = entry.title,
                    subtitle = entry.group,
                    posterUrl = entry.logoUrl,
                    positionMs = effectivePosition,
                    durationMs = durationMs,
                    completed = durationMs?.let { it > 0 && effectivePosition >= it * 0.92 } ?: false,
                    lastPlayedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun persistentDeviceId(application: Application): String {
        val preferences = application.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
        return preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit { putString("device_id", it) }
        }
    }
}
