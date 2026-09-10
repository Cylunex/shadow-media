package top.cylunex.shadowmedia
import top.cylunex.shadowmedia.network.ResourceScheduler
import top.cylunex.shadowmedia.network.ResourcePriority

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
import top.cylunex.shadowmedia.network.ClientIdentity
import top.cylunex.shadowmedia.network.DefaultEmbyRepository
import top.cylunex.shadowmedia.network.EmbyRepository
import top.cylunex.shadowmedia.network.KeystoreSessionStore
import top.cylunex.shadowmedia.network.PersistentPlaybackOutbox
import top.cylunex.shadowmedia.network.PlaybackOutbox
import top.cylunex.shadowmedia.network.SafeExternalSourceRepository
import top.cylunex.shadowmedia.network.DefaultLiveGuideRepository
import top.cylunex.shadowmedia.network.LiveGuideRepository
import top.cylunex.shadowmedia.network.DefaultIntegrationRepository
import top.cylunex.shadowmedia.network.KeystoreIntegrationStore
import top.cylunex.shadowmedia.network.SessionStore
import top.cylunex.shadowmedia.network.SharedPreferencesExternalSourceStore
import top.cylunex.shadowmedia.network.DefaultNetworkStorageRepository
import top.cylunex.shadowmedia.network.KeystoreNetworkStorageStore
import top.cylunex.shadowmedia.database.LocalMediaStateRepository
import top.cylunex.shadowmedia.database.ShadowMediaDatabase
import top.cylunex.shadowmedia.database.migrateAccountScopes
import kotlinx.coroutines.async
import top.cylunex.shadowmedia.provider.InMemoryProviderRegistry
import top.cylunex.shadowmedia.provider.AggregateSearchEngine
import top.cylunex.shadowmedia.database.MediaHistoryEntity
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.ExternalMediaEntry
import top.cylunex.shadowmedia.model.MediaItem

open class ShadowMediaApplication : Application(), coil3.SingletonImageLoader.Factory {
    override fun newImageLoader(context: android.content.Context): coil3.ImageLoader = coil3.ImageLoader.Builder(context)
        .components { add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { okhttp3.OkHttpClient.Builder().addInterceptor(top.cylunex.shadowmedia.network.OfflineModeInterceptor()).addInterceptor(top.cylunex.shadowmedia.network.ResourceBudgetInterceptor { top.cylunex.shadowmedia.network.ResourcePriority.VISIBLE }).build() })) }.build()
    val container: AppContainer by lazy { AppContainer(this) }
    override fun onCreate() { super.onCreate(); container.initializeLibraryResources() }
}

class AppContainer(private val application: Application) {
    val library = top.cylunex.shadowmedia.library.LibraryRepository(application)
    val offline = top.cylunex.shadowmedia.library.OfflineRepository(application, library)
    val music = top.cylunex.shadowmedia.library.MusicRepository(application, library)
    val catalogs = top.cylunex.shadowmedia.library.NativeCatalogRepository(application, library)
    val playlistExports = top.cylunex.shadowmedia.library.PlaylistExportRepository(application, catalogs, library)
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
    val embyRepository: EmbyRepository = top.cylunex.shadowmedia.network.CachedEmbyRepository(DefaultEmbyRepository(
        client = OkHttpClient.Builder().addInterceptor(top.cylunex.shadowmedia.network.OfflineModeInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
        clientIdentity = clientIdentity,
    ), top.cylunex.shadowmedia.network.EmbyCatalogCache(database.libraryStateDao()))
    val playbackOutbox: PlaybackOutbox = PersistentPlaybackOutbox(application, embyRepository)
    private val audioReporter = EmbyAudioReporter(applicationScope, playbackOutbox)
    val feedSessionStore = FeedSessionStore(application)
    val externalClient = OkHttpClient.Builder().addInterceptor(top.cylunex.shadowmedia.network.OfflineModeInterceptor())
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

    fun backgroundResourcesAllowed() = top.cylunex.shadowmedia.library.ResourceDevicePolicy.backgroundAllowed(application)

    fun initializeLibraryResources() {
        top.cylunex.shadowmedia.library.LibraryResources.offlineOnly = application.getSharedPreferences("resource_policy", 0).getBoolean("offlineOnly", false)
        val offlineMedia = top.cylunex.shadowmedia.audio.MediaOfflineStore.get(application)
        offlineMedia.installPlaybackRoute()
        top.cylunex.shadowmedia.library.LibraryResources.mediaOffline = offlineMedia::command
        top.cylunex.shadowmedia.library.LibraryResources.hasOfflineMedia = offlineMedia::available
        top.cylunex.shadowmedia.library.LibraryResources.lyricsResolver = { asset ->
            if (asset.providerId.startsWith("catalog:JELLYFIN:") || asset.providerId.startsWith("catalog:OPENSUBSONIC:")) {
                catalogs.musicProvider(catalogs.connection(asset.providerId)).lyrics(asset.itemId)
            } else null
        }
        if (top.cylunex.shadowmedia.library.LibraryResources.offlineOnly) offlineMedia.setOfflineMode(true)
        top.cylunex.shadowmedia.library.LibraryResources.networkStorage = networkStorageRepository
        top.cylunex.shadowmedia.library.LibraryResources.audioEvent = audioReporter::progress
        top.cylunex.shadowmedia.library.LibraryResources.audioCandidateSelected = audioReporter::selected
        top.cylunex.shadowmedia.library.LibraryResources.pageManifest = catalogs::pages
        top.cylunex.shadowmedia.library.LibraryResources.pageReader = { asset, page, file ->
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
        top.cylunex.shadowmedia.library.LibraryResources.resolver = { requestedAsset ->
            accountScopesReady.await()
            val asset = library.dao.asset(requestedAsset.id) ?: requestedAsset
            val key = top.cylunex.shadowmedia.model.MediaKey(asset.providerId, asset.itemId)
            val candidates = when {
                asset.providerId.startsWith("catalog:") -> listOf(catalogs.resolve(asset))
                asset.providerId.startsWith("storage:") -> {
                    val connection = requireNotNull(networkStorageStore.loadAll().firstOrNull { "storage:${it.id}" == asset.providerId }) { "网络存储连接已移除" }
                    networkStorageRepository.provider(connection).resolve(top.cylunex.shadowmedia.model.UnifiedPlaybackRequest(key))
                }
                asset.providerId.startsWith("emby:") -> {
                    val session = requireNotNull(sessionStore.loadAll().firstOrNull { it.providerId == asset.providerId }) { "Emby 账号已移除" }
                    if (asset.kind in setOf("MUSIC", "AUDIOBOOK", "PODCAST")) embyRepository.audioPlan(session, asset.itemId).candidates else embyRepository.playbackPlan(session, asset.itemId).candidates
                }
                else -> requireNotNull(providerRegistry.provider(asset.providerId)) { "来源不可用，请重新连接" }.resolve(top.cylunex.shadowmedia.model.UnifiedPlaybackRequest(key))
            }
            requireNotNull(candidates.firstOrNull()) { "来源没有返回资源" }
        }
        top.cylunex.shadowmedia.library.LibraryResources.audioResolver = { requestedAsset, entryId ->
            accountScopesReady.await()
            val asset = library.dao.asset(requestedAsset.id) ?: requestedAsset
            if (asset.providerId.startsWith("catalog:JELLYFIN:") || asset.providerId.startsWith("catalog:OPENSUBSONIC:")) {
                catalogs.musicProvider(catalogs.connection(asset.providerId)).resolve(top.cylunex.shadowmedia.model.UnifiedPlaybackRequest(top.cylunex.shadowmedia.model.MediaKey(asset.providerId, asset.itemId)))
            } else if (asset.providerId.startsWith("emby:")) {
                val session = requireNotNull(sessionStore.loadAll().firstOrNull { it.providerId == asset.providerId }) { "Emby 账号已移除" }
                val plan = embyRepository.audioPlan(session, asset.itemId)
                require(plan.candidates.isNotEmpty()) { "来源没有返回资源" }
                audioReporter.resolved(entryId, session, plan)
                plan.candidates
            } else {
                val provider = providerRegistry.provider(asset.providerId)
                if (provider != null) provider.resolve(top.cylunex.shadowmedia.model.UnifiedPlaybackRequest(top.cylunex.shadowmedia.model.MediaKey(asset.providerId, asset.itemId)))
                else listOf(requireNotNull(top.cylunex.shadowmedia.library.LibraryResources.resolver)(asset))
            }
        }

    }

    fun recordEmbyHistory(session: EmbySession, item: MediaItem, positionMs: Long, durationMs: Long?) {
        applicationScope.launch {
            localMediaState.recordHistory(
                MediaHistoryEntity(
                    stableKey = "${session.providerId}:${item.id}",
                    providerId = session.providerId,
                    itemId = item.id,
                    title = item.name,
                    subtitle = item.seriesName,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    completed = durationMs?.let { it > 0 && positionMs >= it * 0.92 } ?: false,
                    lastPlayedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }
    }

    fun recordExternalHistory(entry: ExternalMediaEntry, positionMs: Long, durationMs: Long?) {
        applicationScope.launch {
            val providerId = entry.sourceId.takeIf { entry.url.startsWith("shadow-cached:") || it.startsWith("catalog:") || it.startsWith("emby:") || it.startsWith("storage:") || it.startsWith("live:") }
                ?: "external:${entry.sourceId}"
            localMediaState.recordHistory(
                MediaHistoryEntity(
                    stableKey = "$providerId:${entry.id}",
                    providerId = providerId,
                    itemId = entry.id,
                    title = entry.title,
                    subtitle = entry.group,
                    posterUrl = entry.logoUrl,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    completed = durationMs?.let { it > 0 && positionMs >= it * 0.92 } ?: false,
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
