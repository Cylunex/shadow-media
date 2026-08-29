package top.cylunex.shadowmedia

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
import top.cylunex.shadowmedia.provider.InMemoryProviderRegistry
import top.cylunex.shadowmedia.provider.AggregateSearchEngine
import top.cylunex.shadowmedia.database.MediaHistoryEntity
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.ExternalMediaEntry
import top.cylunex.shadowmedia.model.MediaItem

class ShadowMediaApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
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
    val embyRepository: EmbyRepository = DefaultEmbyRepository(
        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
        clientIdentity = clientIdentity,
    )
    val playbackOutbox: PlaybackOutbox = PersistentPlaybackOutbox(application, embyRepository)
    val feedSessionStore = FeedSessionStore(application)
    val externalClient = OkHttpClient.Builder()
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

    fun recordEmbyHistory(session: EmbySession, item: MediaItem, positionMs: Long, durationMs: Long?) {
        applicationScope.launch {
            localMediaState.recordHistory(
                MediaHistoryEntity(
                    stableKey = "emby:${session.serverId}:${session.userId}:${item.id}",
                    providerId = "emby:${session.serverId}:${session.userId}",
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
            val providerId = entry.sourceId.takeIf { it.startsWith("storage:") || it.startsWith("live:") }
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
