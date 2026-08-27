package top.cylunex.shadowmedia.network

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `keeps iso as static stream for an external disc capable player`() = runBlocking {
        val responseJson = """
            {
              "PlaySessionId": "play-iso",
              "MediaSources": [{
                "Id": "source-iso",
                "Container": "iso",
                "VideoType": "Iso",
                "SupportsDirectPlay": true,
                "SupportsDirectStream": true,
                "SupportsTranscoding": true,
                "DirectStreamUrl": "https://cdn.example.net/file.iso?signature=temporary",
                "RequiredHttpHeaders": {},
                "MediaStreams": [{"Type": "Video", "Codec": "mpeg2video"}]
              }]
            }
        """.trimIndent()
        val repository = repositoryReturning(responseJson)

        val plan = repository.playbackPlan(SESSION, "iso-1")

        assertEquals(1, plan.candidates.size)
        assertEquals(PlayMethod.DIRECT_STREAM, plan.primary.method)
        assertEquals(
            "https://media.example.com/emby/Videos/iso-1/stream.iso?" +
                "MediaSourceId=source-iso&Static=true&PlaySessionId=play-iso",
            plan.primary.url,
        )
        assertFalse(plan.primary.url.contains("api_key", ignoreCase = true))
    }

    @Test
    fun `loads every item page until total record count`() = runBlocking {
        val starts = mutableListOf<Int>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val start = chain.request().url.queryParameter("StartIndex")?.toInt() ?: 0
            starts += start
            val items = if (start == 0) {
                """[{"Id":"1","Name":"One"},{"Id":"2","Name":"Two"}]"""
            } else {
                """[{"Id":"3","Name":"Three"}]"""
            }
            jsonResponse(chain, """{"Items":$items,"TotalRecordCount":3}""")
        }.build()
        val repository = repositoryWith(client)

        val items = repository.recentVideos(SESSION, "library-1")

        assertEquals(listOf("1", "2", "3"), items.map { it.id })
        assertEquals(listOf(0, 2), starts)
    }

    @Test
    fun `deletes item through authenticated library endpoint`() = runBlocking {
        var method = ""
        var ids: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            method = chain.request().method
            ids = chain.request().url.queryParameter("Ids")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .body(ByteArray(0).toResponseBody(null))
                .build()
        }.build()

        repositoryWith(client).deleteItem(SESSION, "item-to-delete")

        assertEquals("DELETE", method)
        assertEquals("item-to-delete", ids)
    }

    private fun repositoryReturning(json: String): DefaultEmbyRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain -> jsonResponse(chain, json) }.build()
        return repositoryWith(client)
    }

    private fun repositoryWith(client: OkHttpClient) = DefaultEmbyRepository(
        client = client,
        clientIdentity = ClientIdentity("Test Device", "device-1", "test"),
    )

    private fun jsonResponse(chain: okhttp3.Interceptor.Chain, json: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()

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
