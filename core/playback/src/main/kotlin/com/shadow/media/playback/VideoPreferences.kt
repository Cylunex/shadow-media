package com.shadow.media.playback

import android.content.Context
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.MessageDigest
import com.shadow.media.model.ExternalSubtitle

/** Applies only to ordinary video. A software mode needs a software codec installed on the device. */
enum class VideoDecoderMode { AUTO, HARDWARE, SOFTWARE }
data class VideoSettings(
    val decoder: VideoDecoderMode = VideoDecoderMode.AUTO,
    val retries: Int = 2,
    val audioLanguage: String = "",
    val subtitleLanguage: String = "",
    val subtitleScale: Float = 1f,
    val subtitleBackground: Boolean = false,
    val subtitleBottomPadding: Float = .08f,
    val autoSwitchLines: Boolean = true,
)

class VideoPreferenceStore internal constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("video_preferences", 0)
    private val mutableSettings = MutableStateFlow(read())
    val settings = mutableSettings.asStateFlow()
    private fun read() = VideoSettings(
        runCatching { VideoDecoderMode.valueOf(preferences.getString("decoder", "AUTO")!!) }.getOrDefault(VideoDecoderMode.AUTO),
        preferences.getInt("retries", 2).coerceIn(0, 5), preferences.getString("audio", "").orEmpty(), preferences.getString("text", "").orEmpty(),
        preferences.getFloat("scale", 1f).takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f,
        preferences.getBoolean("background", false), preferences.getFloat("bottom", .08f).takeIf { it.isFinite() }?.coerceIn(0f, .4f) ?: .08f, preferences.getBoolean("autoSwitchLines", true))
    @Synchronized fun update(change: (VideoSettings) -> VideoSettings) {
        val next = change(settings.value).let { it.copy(retries = it.retries.coerceIn(0, 5), subtitleScale = it.subtitleScale.takeIf { value -> value.isFinite() }?.coerceIn(.5f, 2f) ?: 1f, subtitleBottomPadding = it.subtitleBottomPadding.takeIf { value -> value.isFinite() }?.coerceIn(0f, .4f) ?: .08f) }
        preferences.edit().putString("decoder", next.decoder.name).putInt("retries", next.retries).putString("audio", next.audioLanguage)
            .putString("text", next.subtitleLanguage).putFloat("scale", next.subtitleScale).putBoolean("background", next.subtitleBackground)
            .putFloat("bottom", next.subtitleBottomPadding).putBoolean("autoSwitchLines", next.autoSwitchLines).apply()
        mutableSettings.value = next
    }
    private fun scoped(key: String, kind: String) = "$kind:" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    fun saveTrack(key: String, type: Int, format: Format?) {
        val value = JSONObject().put("disabled", format == null).put("language", format?.language.orEmpty())
            .put("label", format?.label.orEmpty()).put("mime", format?.sampleMimeType.orEmpty()).put("roles", format?.roleFlags ?: 0)
            .put("channels", format?.channelCount ?: -1)
        preferences.edit().putString(scoped(key, "track:$type"), value.toString()).apply()
    }
    fun applyLanguages(player: Player) {
        val settings = settings.value
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(settings.audioLanguage.ifBlank { null })
            .setPreferredTextLanguage(settings.subtitleLanguage.ifBlank { null }).build()
    }
    fun restoreTracks(key: String, player: Player) {
        var builder = player.trackSelectionParameters.buildUpon()
        for (type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
            val payload = preferences.getString(scoped(key, "track:$type"), null) ?: continue
            val saved = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            if (saved.optBoolean("disabled")) { if (type == C.TRACK_TYPE_TEXT) builder = builder.clearOverridesOfType(type).setTrackTypeDisabled(type, true); continue }
            val matches = player.currentTracks.groups.filter { it.type == type }.flatMap { group ->
                (0 until group.length).filter { index ->
                    val format = group.getTrackFormat(index)
                    group.isTrackSupported(index) && format.language.orEmpty() == saved.optString("language") && format.label.orEmpty() == saved.optString("label") &&
                        format.sampleMimeType.orEmpty() == saved.optString("mime") && format.roleFlags == saved.optInt("roles") && format.channelCount == saved.optInt("channels", -1)
                }.map { group to it }
            }
            // Ambiguous tracks fall back to language/default selection instead of guessing an index.
            if (matches.size == 1) matches.single().let { (group, index) -> builder = builder.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))) }
        }
        val parameters = builder.build()
        if (parameters != player.trackSelectionParameters) player.trackSelectionParameters = parameters
    }
    fun saveSubtitle(key: String, subtitle: ExternalSubtitle) {
        require(subtitle.uri.startsWith("content:"))
        preferences.edit().putString(scoped(key, "subtitle"), JSONObject().put("uri", subtitle.uri).put("mime", subtitle.mimeType).put("label", subtitle.label).toString())
            .remove(scoped(key, "track:${C.TRACK_TYPE_TEXT}")).apply()
    }
    fun subtitle(key: String): ExternalSubtitle? = runCatching {
        val value = JSONObject(preferences.getString(scoped(key, "subtitle"), null) ?: return null)
        ExternalSubtitle(value.getString("uri"), value.getString("mime"), value.getString("label"))
    }.getOrNull()
    companion object {
        @Volatile private var instance: VideoPreferenceStore? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: VideoPreferenceStore(context).also { instance = it } }
    }
}

internal fun videoRenderers(context: Context, mode: VideoDecoderMode): DefaultRenderersFactory = DefaultRenderersFactory(context)
    .setEnableDecoderFallback(mode == VideoDecoderMode.AUTO).setMediaCodecSelector(MediaCodecSelector { mime, secure, tunneling ->
        val codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
        when (mode) {
            VideoDecoderMode.AUTO -> codecs
            VideoDecoderMode.HARDWARE -> codecs.filter { it.hardwareAccelerated }
            VideoDecoderMode.SOFTWARE -> codecs.filter { it.softwareOnly }
        }
    })
