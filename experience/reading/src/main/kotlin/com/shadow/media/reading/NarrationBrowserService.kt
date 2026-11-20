package com.shadow.media.reading

import android.content.*
import android.content.pm.PackageManager
import android.media.MediaDescription
import android.media.browse.MediaBrowser.MediaItem
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.service.media.MediaBrowserService
import com.shadow.media.database.LibraryAssetEntity
import com.shadow.media.library.LibraryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal fun LibraryAssetEntity.narratable() = kind == "BOOK" && format.lowercase() in setOf("epub", "txt")

/** Android Auto gets the same transport, locator and sentence controls as the reader. */
class NarrationBrowserService : MediaBrowserService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val library by lazy { LibraryRepository(this) }
    private val binding = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (sessionToken == null) sessionToken = (service as ReaderNarrationService.NarrationBinder).token
        }
        override fun onServiceDisconnected(name: ComponentName) { stopSelf() }
    }
    private var bound = false

    override fun onCreate() {
        super.onCreate()
        bound = bindService(Intent(this, ReaderNarrationService::class.java), binding, BIND_AUTO_CREATE)
    }
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        if (clientUid == Process.myUid() || clientUid == Process.SYSTEM_UID) return BrowserRoot("reading", null)
        if (clientPackageName !in packageManager.getPackagesForUid(clientUid).orEmpty()) return null
        val trusted = packageManager.checkPermission("android.permission.MEDIA_CONTENT_CONTROL", clientPackageName) ==
            PackageManager.PERMISSION_GRANTED || (clientPackageName == "com.google.android.projection.gearhead" &&
            packageManager.checkSignatures(clientPackageName, "com.google.android.gms") == PackageManager.SIGNATURE_MATCH)
        return if (trusted) BrowserRoot("reading", null) else null
    }
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        if (parentId != "reading") { result.sendResult(mutableListOf()); return }
        result.detach()
        scope.launch {
            try {
                val current = library.dao.continuing(listOf("BOOK")).first()?.asset
                val recent = library.dao.searchPage("BOOK", "%", 0, 50)
                val rows = (listOfNotNull(current) + recent).distinctBy { it.id }.filter { it.narratable() }
                result.sendResult(rows.mapTo(mutableListOf()) {
                    MediaItem(MediaDescription.Builder().setMediaId(it.id).setTitle(it.title)
                        .setSubtitle(if (it.id == current?.id) "继续阅读" else it.author).build(), MediaItem.FLAG_PLAYABLE)
                })
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { result.sendResult(mutableListOf()) }
        }
    }
    override fun onDestroy() {
        if (bound) unbindService(binding)
        scope.cancel()
        super.onDestroy()
    }
}
