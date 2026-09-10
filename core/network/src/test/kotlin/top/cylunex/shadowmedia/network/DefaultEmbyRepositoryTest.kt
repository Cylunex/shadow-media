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
import top.cylunex.shadowmedia.model.BrowseRequest
import top.cylunex.shadowmedia.model.MediaFilter
import top.cylunex.shadowmedia.model.MediaSort
import top.cylunex.shadowmedia.model.PlayMethod

class DefaultEmbyRepositoryTest {
    @Test fun `audio preserves advertised route canonical fallback and transcode across sources`() = runBlocking {
        val plan = repositoryReturning("""{"PlaySessionId":"p","MediaSources":[{"Id":"one","DirectStreamUrl":"/emby/Audio/one/original","TranscodingUrl":"/emby/Audio/one/transcode.mp3"},{"Id":"two"}]}""").audioPlan(SESSION, "song")
        assertEquals(4, plan.candidates.size)
        assertTrue(plan.candidates[0].url.endsWith("/emby/Audio/one/original"))
        assertEquals(PlayMethod.TRANSCODE, plan.candidates[2].method)
        assertEquals("two", plan.candidates.last().mediaSourceId)
        assertTrue(plan.candidates.all { it.credentialOrigin == SESSION.serverUrl && it.requiredHeaders["X-Emby-Token"] == SESSION.accessToken })
    }

    @Test fun `audio uses authenticated audio route through proxy without token in url`() = runBlocking {
        val plan = repositoryReturning("""{"PlaySessionId":"p1","MediaSources":[{"Id":"audio1","Container":"m4b"}]}""")
            .audioPlan(SESSION, "book1")
        assertTrue(plan.primary.url.startsWith("https://media.example.com/emby/Audio/book1/stream?"))
        assertTrue(plan.primary.url.contains("MediaSourceId=audio1"))
        assertTrue(plan.primary.url.contains("Static=true"))
        assertFalse(plan.primary.url.contains(SESSION.accessToken))
        assertEquals(SESSION.accessToken, plan.primary.requiredHeaders["X-Emby-Token"])
        assertEquals(SESSION.serverUrl, plan.primary.credentialOrigin)
    }
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
    fun `prefers advertised mediawarp routes before synthesized and transcode fallbacks`() = runBlocking {
        val responseJson = """
            {
              "PlaySessionId": "play-multi",
              "MediaSources": [
                {
                  "Id": "source-4k",
                  "Container": "mkv",
                  "SupportsDirectPlay": true,
                  "SupportsTranscoding": true,
                  "DirectStreamUrl": "http://emby.internal:8096/emby/Videos/item-1/stream.mkv?MediaSourceId=source-4k",
                  "TranscodingUrl": "/emby/Videos/item-1/master.m3u8?MediaSourceId=source-4k"
                },
                {
                  "Id": "source-1080p",
                  "Container": "mp4",
                  "SupportsDirectPlay": true,
                  "DirectStreamUrl": "https://temporary.example.net/movie.mp4?sign=short"
                }
              ]
            }
        """.trimIndent()

        val plan = repositoryReturning(responseJson).playbackPlan(SESSION, "item-1")

        assertEquals(2, plan.sourceCount)
        assertEquals(listOf("source-4k", "source-1080p"), plan.candidates.take(2).map { it.mediaSourceId })
        assertEquals(
            "https://media.example.com/emby/Videos/item-1/stream.mkv?MediaSourceId=source-4k",
            plan.candidates[0].url,
        )
        assertEquals("https://temporary.example.net/movie.mp4?sign=short", plan.candidates[1].url)
        assertTrue(plan.candidates[2].url.contains("Static=true"))
        assertEquals(PlayMethod.TRANSCODE, plan.candidates.last().method)
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

    @Test
    fun `browse delegates paging search sort and favorite filter to emby`() = runBlocking {
        var capturedUrl: okhttp3.HttpUrl? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            capturedUrl = chain.request().url
            jsonResponse(chain, """{"Items":[],"TotalRecordCount":75}""")
        }.build()

        val page = repositoryWith(client).browse(
            SESSION,
            BrowseRequest(
                parentId = "library-1",
                includeItemTypes = setOf("Movie", "Series"),
                searchTerm = "影子",
                sort = MediaSort.RATING,
                descending = true,
                filter = MediaFilter.FAVORITES,
                startIndex = 60,
                limit = 15,
            ),
        )

        assertEquals(75, page.totalRecordCount)
        assertEquals("library-1", capturedUrl?.queryParameter("ParentId"))
        assertEquals("Movie,Series", capturedUrl?.queryParameter("IncludeItemTypes"))
        assertEquals("影子", capturedUrl?.queryParameter("SearchTerm"))
        assertEquals("CommunityRating", capturedUrl?.queryParameter("SortBy"))
        assertEquals("true", capturedUrl?.queryParameter("IsFavorite"))
        assertEquals("60", capturedUrl?.queryParameter("StartIndex"))
    }

    @Test
    fun `favorite mutation uses user scoped endpoint and expected method`() = runBlocking {
        val methods = mutableListOf<String>()
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            methods += chain.request().method
            paths += chain.request().url.encodedPath
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .body(ByteArray(0).toResponseBody(null))
                .build()
        }.build()
        val repository = repositoryWith(client)

        repository.setFavorite(SESSION, "movie-1", true)
        repository.setFavorite(SESSION, "movie-1", false)

        assertEquals(listOf("POST", "DELETE"), methods)
        assertTrue(paths.all { it.endsWith("/Users/user-1/FavoriteItems/movie-1") })
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
