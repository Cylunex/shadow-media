package top.cylunex.shadowmedia

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import top.cylunex.shadowmedia.network.ClientIdentity
import top.cylunex.shadowmedia.network.DefaultEmbyRepository
import top.cylunex.shadowmedia.network.EmbyRepository
import top.cylunex.shadowmedia.network.KeystoreSessionStore
import top.cylunex.shadowmedia.network.SessionStore

class ShadowMediaApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
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

    private fun persistentDeviceId(application: Application): String {
        val preferences = application.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
        return preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit { putString("device_id", it) }
        }
    }
}
