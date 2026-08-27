package top.cylunex.shadowmedia.network

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.PlayMethod

class DefaultEmbyRepositoryTest {
    @Test
    fun `builds static stream when playback info omits derived urls`() = runBlocking {
        val responseJson = """
            {
              "PlaySessionId": "play-1",
              "MediaSources": [{
                "Id": "source-1",
                "Container": "mkv",
                "SupportsDirectPlay": true,
                "SupportsDirectStream": true,
                "SupportsTranscoding": true,
                "RequiredHttpHeaders": {},
                "MediaStreams": [
                  {"Type": "Video", "Codec": "hevc", "IsDefault": true},
                  {"Type": "Audio", "Codec": "aac", "IsDefault": true}
                ]
              }]
            }
        """.trimIndent()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseJson.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val repository = DefaultEmbyRepository(
            client = client,
            clientIdentity = ClientIdentity("Test Device", "device-1", "test"),
        )

        val plan = repository.playbackPlan(SESSION, "item-1")

        assertEquals(PlayMethod.DIRECT_STREAM, plan.primary.method)
        assertEquals(1, plan.sourceCount)
        assertTrue(plan.supportsDirectPlay)
        assertEquals(
            "https://media.example.com/emby/Videos/item-1/stream.mkv?" +
                "MediaSourceId=source-1&Static=true&PlaySessionId=play-1",
            plan.primary.url,
        )
    }

    private companion object {
        val SESSION = EmbySession(
            serverUrl = "https://media.example.com/",
            serverId = "server-1",
            userId = "user-1",
            userName = "tester",
            accessToken = "redacted-test-token",
            allowInsecureHttp = false,
        )
    }
}
