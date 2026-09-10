package top.cylunex.shadowmedia.library

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager

/** Foreground playback is unaffected; speculative and offline work obey device conservation. */
object ResourceDevicePolicy {
    @Suppress("DEPRECATION")
    fun backgroundAllowed(context: Context): Boolean {
        if (LibraryResources.offlineOnly || context.filesDir.usableSpace < 64L * 1024 * 1024) return false
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity.activeNetworkInfo?.isRoaming == true) return false
        if (connectivity.isActiveNetworkMetered && connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) return false
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return plugged || level < 0 || scale <= 0 || level.toDouble() / scale > 0.15
    }
}
