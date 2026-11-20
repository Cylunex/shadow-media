package com.shadow.media

import android.app.Application
import android.content.Context
import com.shadow.media.model.ExternalCatalogSite
import com.shadow.media.model.MediaKey
import com.shadow.media.model.UnifiedPlaybackRequest
import com.shadow.media.network.SharedPreferencesExternalSourceStore
import com.shadow.media.network.TvBoxHttpMediaProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ImportedSourceRecoveryTest {
    @Test fun unreadableSubscriptionsSurviveReadsAndWrites() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("external_source_subscriptions", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("sources_v1", "AA==").commit())
        assertTrue(prefs.contains("sources_v1"))
        val store = SharedPreferencesExternalSourceStore(context)
        val source = com.shadow.media.network.SafeExternalSourceRepository(OkHttpClient())
            .importPayload("https://example.com/test.m3u", "#EXTM3U\n#EXTINF:-1,Test\nhttps://example.com/stream")
        assertThrows(IllegalStateException::class.java) { store.loadAll() }
        assertThrows(IllegalStateException::class.java) { store.save(source) }
        assertThrows(IllegalStateException::class.java) { store.remove(source.summary.id) }
        assertEquals("AA==", prefs.getString("sources_v1", null))
    }

    @Test fun coldResumeResolvesTheSameEpisodeFromItsParent() {
        val requested = mutableListOf<String?>()
        val film = """{"list":[{"vod_id":"movie7","vod_name":"Sample","vod_play_from":"Line","vod_play_url":"One\u0024https://example.com/one.mp4#Two\u0024https://example.com/two.mp4"}]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val id = chain.request().url.queryParameter("ids")
            requested += id
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (id == "movie7") film else """{"list":[]}""").toResponseBody()).build()
        }.build()
        val site = ExternalCatalogSite("fixture", "sub", "Fixture", "https://example.com/catalog", false)
        val first = TvBoxHttpMediaProvider(site, client)
        val fresh = TvBoxHttpMediaProvider(site, client)
        runBlocking {
            val episode = first.detail(MediaKey(first.descriptor.id, "movie7")).children[1].key
            assertEquals("https://example.com/two.mp4", first.resolve(UnifiedPlaybackRequest(episode)).single().url)
            assertEquals("https://example.com/two.mp4", fresh.resolve(UnifiedPlaybackRequest(episode)).single().url)
            assertEquals("Two", fresh.detail(episode).item.title)
            assertEquals(listOf("movie7", "movie7", "movie7"), requested)
        }
    }
}
