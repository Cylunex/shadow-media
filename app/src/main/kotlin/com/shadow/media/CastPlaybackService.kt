package com.shadow.media

import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

class CastPlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var generation = 0L
    private lateinit var mediaSession: android.media.session.MediaSession
    private lateinit var wakeLock: android.os.PowerManager.WakeLock
    private val cast get() = (application as ShadowMediaApplication).container.castPlayback
    override fun onCreate() {
        super.onCreate()
        mediaSession = android.media.session.MediaSession(this, "shadow-cast").apply {
            setPlaybackToRemote(object : android.media.VolumeProvider(android.media.VolumeProvider.VOLUME_CONTROL_FIXED, 100, 100) {})
            setCallback(object : android.media.session.MediaSession.Callback() {
                override fun onPlay() = cast.play()
                override fun onPause() = cast.pause()
                override fun onStop() = cast.stop()
                override fun onSeekTo(pos: Long) = cast.seek(pos)
            })
        }
        wakeLock = getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "shadowmedia:cast").apply { setReferenceCounted(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("cast_playback", "远端播放", NotificationManager.IMPORTANCE_LOW))
        startForeground(4097, notification(CastPlaybackState(connecting = true)))
        scope.launch { cast.state.collect { state ->
            if (state.playing || state.connecting) wakeLock.acquire(10 * 60_000L) else if (wakeLock.isHeld) wakeLock.release()
            mediaSession.isActive = state.connected || state.connecting
            mediaSession.setMetadata(android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, state.deviceName).putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, state.durationMs).build())
            val status = when { state.connecting -> android.media.session.PlaybackState.STATE_CONNECTING; state.playing -> android.media.session.PlaybackState.STATE_PLAYING; state.connected -> android.media.session.PlaybackState.STATE_PAUSED; else -> android.media.session.PlaybackState.STATE_STOPPED }
            mediaSession.setPlaybackState(android.media.session.PlaybackState.Builder().setState(status, state.positionMs, if (state.playing) 1f else 0f)
                .setActions(android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or android.media.session.PlaybackState.ACTION_STOP or android.media.session.PlaybackState.ACTION_SEEK_TO or android.media.session.PlaybackState.ACTION_PLAY_PAUSE).build())
            startForeground(4097, notification(state))
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra("generation") == true) generation = intent.getLongExtra("generation", 0)
        when (intent?.action) { "play" -> cast.play(); "pause" -> cast.pause(); "stop" -> cast.stop() }
        return START_NOT_STICKY
    }
    private fun notification(state: CastPlaybackState): Notification {
        fun action(name: String) = PendingIntent.getService(this, name.hashCode(), Intent(this, CastPlaybackService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "cast_playback").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.title.ifBlank { "远端播放" }).setContentText(state.error ?: if (state.connecting) "正在连接播放设备" else state.deviceName.orEmpty())
            .setContentIntent(PendingIntent.getActivity(this, 4097, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(state.playing || state.connecting).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1))
            .addAction(Notification.Action.Builder(null, if (state.playing) "暂停" else "播放", action(if (state.playing) "pause" else "play")).build())
            .addAction(Notification.Action.Builder(null, "停止", action("stop")).build()).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTaskRemoved(rootIntent: Intent?) { cast.stop(); stopSelf() }
    override fun onDestroy() { scope.cancel(); if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release(); if (::mediaSession.isInitialized) mediaSession.release(); cast.serviceDestroyed(generation); super.onDestroy() }
}
