package com.shadow.media.playback

import android.content.Context
import androidx.media3.common.*
import java.lang.reflect.Proxy
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(manifest = Config.NONE, sdk = [28])
class VideoPreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("video_preferences", 0).edit().clear().commit() }
    @Test fun `decoder retries languages and subtitle appearance survive recreation`() {
        val store = VideoPreferenceStore(context)
        store.update { it.copy(decoder = VideoDecoderMode.SOFTWARE, retries = 5, audioLanguage = "ja", subtitleLanguage = "zh", subtitleScale = 1.3f, subtitleBackground = true, autoSwitchLines = false) }
        assertEquals(store.settings.value, VideoPreferenceStore(context).settings.value)
        store.update { it.copy(subtitleScale = Float.NaN, retries = 999) }
        assertEquals(1f, store.settings.value.subtitleScale)
        assertEquals(5, store.settings.value.retries)
    }
    @Test fun `semantic track selection survives changed group id without crossing account scope`() {
        val store = VideoPreferenceStore(context)
        val original = Format.Builder().setId("old-id").setSampleMimeType("audio/aac").setLanguage("ja").setLabel("Main").setChannelCount(2).build()
        store.saveTrack("account-one:movie", C.TRACK_TYPE_AUDIO, original)
        val group = TrackGroup("new-group", original.buildUpon().setId("new-id").build())
        val fake = TrackPlayer(listOf(Tracks.Group(group, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))))
        store.restoreTracks("account-one:movie", fake.player)
        assertEquals(listOf(0), fake.parameters.overrides[group]?.trackIndices)
        val other = TrackPlayer(fake.tracks.groups)
        store.restoreTracks("account-two:movie", other.player)
        assertTrue(other.parameters.overrides.isEmpty())
    }
    @Test fun `ambiguous equal language tracks fall back instead of guessing old indices`() {
        val store = VideoPreferenceStore(context)
        val format = Format.Builder().setSampleMimeType("audio/aac").setLanguage("en").build()
        store.saveTrack("movie", C.TRACK_TYPE_AUDIO, format)
        val group = TrackGroup(format.buildUpon().setId("one").build(), format.buildUpon().setId("two").build())
        val fake = TrackPlayer(listOf(Tracks.Group(group, false, intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(true, false))))
        store.restoreTracks("movie", fake.player)
        assertTrue(fake.parameters.overrides.isEmpty())
    }
    @Test fun `subtitle off preference persists independently for the selected movie`() {
        val store = VideoPreferenceStore(context)
        store.saveTrack("movie-one", C.TRACK_TYPE_TEXT, null)
        val fake = TrackPlayer(emptyList())
        store.restoreTracks("movie-one", fake.player)
        assertTrue(fake.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        val other = TrackPlayer(emptyList())
        store.restoreTracks("movie-two", other.player)
        assertFalse(other.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
    }
    private inner class TrackPlayer(groups: List<Tracks.Group>) {
        val tracks = Tracks(groups)
        var parameters = TrackSelectionParameters.Builder(context).build()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentTracks" -> tracks
                "getTrackSelectionParameters" -> parameters
                "setTrackSelectionParameters" -> { parameters = args!![0] as TrackSelectionParameters; null }
                else -> null
            }
        } as Player
    }
}
