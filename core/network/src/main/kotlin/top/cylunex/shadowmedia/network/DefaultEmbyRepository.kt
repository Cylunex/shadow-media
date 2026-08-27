package top.cylunex.shadowmedia.network

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.MediaItem
import top.cylunex.shadowmedia.model.MediaLibrary
import top.cylunex.shadowmedia.model.PlayMethod
import top.cylunex.shadowmedia.model.PlaybackCandidate
import top.cylunex.shadowmedia.model.PlaybackEvent
import top.cylunex.shadowmedia.model.PlaybackPlan

class DefaultEmbyRepository(
    private val client: OkHttpClient,
    private val clientIdentity: ClientIdentity,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : EmbyRepository {
    override suspend fun login(request: LoginRequest): EmbySession {
        val server = ServerAddressPolicy.validate(request.serverUrl, request.allowInsecureHttp)
            .getOrElse { throw IllegalArgumentException(it.message, it) }
        val body = json.encodeToString(AuthenticateRequestDto(request.userName.trim(), request.password))
            .toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(EmbyEndpoints.endpoint(server.toString(), "Users", "AuthenticateByName"))
            .header("X-Emby-Authorization", clientIdentity.authorizationHeader())
            .post(body)
            .build()
        val result: AuthenticationResultDto = executeJson(httpRequest)
        return EmbySession(
            serverUrl = server.toString(),
            serverId = result.serverId.ifBlank { result.user.serverId },
            userId = result.user.id,
            userName = result.user.name,
            accessToken = result.accessToken,
            allowInsecureHttp = request.allowInsecureHttp,
        )
    }

    override suspend fun libraries(session: EmbySession): List<MediaLibrary> {
        val url = EmbyEndpoints.endpoint(session.serverUrl, "Users", session.userId, "Views")
        val result: QueryResultDto = executeJson(authenticatedRequest(session, url).get().build())
        return result.items.map { MediaLibrary(it.id, it.name, it.collectionType) }
    }

    override suspend fun recentVideos(
        session: EmbySession,
        libraryId: String,
        limit: Int,
    ): List<MediaItem> {
        val url = EmbyEndpoints.endpoint(session.serverUrl, "Users", session.userId, "Items")
            .newBuilder()
            .addQueryParameter("ParentId", libraryId)
            .addQueryParameter("Recursive", "true")
            .addQueryParameter("IncludeItemTypes", "Movie,Episode,Video")
            .addQueryParameter("Fields", "Overview,MediaSources,MediaStreams")
            .addQueryParameter("SortBy", "DateCreated")
            .addQueryParameter("SortOrder", "Descending")
            .addQueryParameter("Limit", limit.coerceIn(1, 100).toString())
            .build()
        val result: QueryResultDto = executeJson(authenticatedRequest(session, url).get().build())
        return result.items.map { item ->
            MediaItem(
                id = item.id,
                name = item.name,
                type = item.type,
                seriesName = item.seriesName,
                seasonNumber = item.parentIndexNumber,
                episodeNumber = item.indexNumber,
                runTimeTicks = item.runTimeTicks,
                playbackPositionTicks = item.userData?.playbackPositionTicks ?: 0,
                played = item.userData?.played ?: false,
                favorite = item.userData?.favorite ?: false,
            )
        }
    }

    override suspend fun playbackPlan(session: EmbySession, itemId: String): PlaybackPlan {
        val url = EmbyEndpoints.endpoint(session.serverUrl, "Items", itemId, "PlaybackInfo")
        val requestBody = json.encodeToString(PlaybackInfoRequestDto(userId = session.userId))
            .toRequestBody(JSON_MEDIA_TYPE)
        val response: PlaybackInfoResponseDto = executeJson(
            authenticatedRequest(session, url).post(requestBody).build()
        )
        if (response.errorCode != null) throw EmbyApiException("PlaybackInfo: ${response.errorCode}")
        val source = response.mediaSources.firstOrNull {
            !it.directStreamUrl.isNullOrBlank() || !it.transcodingUrl.isNullOrBlank()
        } ?: throw EmbyApiException("服务端没有返回可播放的媒体源")

        val candidates = buildList {
            source.directStreamUrl?.takeIf(String::isNotBlank)?.let { directUrl ->
                add(
                    PlaybackCandidate(
                        url = EmbyEndpoints.resolvePlaybackUrl(session.serverUrl, directUrl),
                        method = PlayMethod.DIRECT_STREAM,
                        requiredHeaders = source.requiredHttpHeaders,
                    )
                )
            }
            source.transcodingUrl?.takeIf(String::isNotBlank)?.let { transcodingUrl ->
                add(
                    PlaybackCandidate(
                        url = EmbyEndpoints.resolvePlaybackUrl(session.serverUrl, transcodingUrl),
                        method = PlayMethod.TRANSCODE,
                        requiredHeaders = source.requiredHttpHeaders,
                    )
                )
            }
        }
        if (candidates.isEmpty()) throw EmbyApiException("媒体源既没有直连地址，也没有转码地址")

        return PlaybackPlan(
            itemId = itemId,
            mediaSourceId = source.id,
            playSessionId = response.playSessionId,
            candidates = candidates.distinctBy { it.url },
            container = source.container,
            videoCodec = source.mediaStreams.firstOrNull { it.type.equals("Video", true) }?.codec,
            audioCodec = source.mediaStreams.firstOrNull { it.type.equals("Audio", true) && it.isDefault }?.codec
                ?: source.mediaStreams.firstOrNull { it.type.equals("Audio", true) }?.codec,
            runTimeTicks = source.runTimeTicks,
        )
    }

    override suspend fun reportPlayback(session: EmbySession, report: PlaybackReport) {
        val endpoint = when (report.event) {
            PlaybackEvent.STARTED -> arrayOf("Sessions", "Playing")
            PlaybackEvent.STOPPED -> arrayOf("Sessions", "Playing", "Stopped")
            else -> arrayOf("Sessions", "Playing", "Progress")
        }
        val url = EmbyEndpoints.endpoint(session.serverUrl, *endpoint)
        val dto = PlaybackReportDto(
            itemId = report.plan.itemId,
            mediaSourceId = report.plan.mediaSourceId,
            playSessionId = report.plan.playSessionId,
            positionTicks = report.positionTicks,
            isPaused = report.isPaused,
            playMethod = report.playMethod.toWireName(),
            eventName = report.event.wireName,
        )
        val body = json.encodeToString(dto).toRequestBody(JSON_MEDIA_TYPE)
        executeEmpty(authenticatedRequest(session, url).post(body).build())
    }

    override suspend fun logout(session: EmbySession) {
        val url = EmbyEndpoints.endpoint(session.serverUrl, "Sessions", "Logout")
        executeEmpty(authenticatedRequest(session, url).post(EMPTY_BODY).build())
    }

    private fun authenticatedRequest(session: EmbySession, url: HttpUrl): Request.Builder =
        Request.Builder()
            .url(url)
            .header("X-Emby-Authorization", clientIdentity.authorizationHeader(session))
            .header("X-Emby-Token", session.accessToken)

    private suspend inline fun <reified T> executeJson(request: Request): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw response.toApiException()
            val payload = response.body.string()
            if (payload.isBlank()) throw EmbyApiException("服务端返回了空响应")
            json.decodeFromString<T>(payload)
        }
    }

    private suspend fun executeEmpty(request: Request) = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw response.toApiException()
        }
    }

    private fun okhttp3.Response.toApiException(): EmbyApiException {
        val reason = when (code) {
            401 -> "登录已失效或凭据不正确"
            403 -> "当前用户没有访问权限"
            404 -> "Emby 接口不存在，请检查服务地址"
            else -> "Emby 请求失败（HTTP $code）"
        }
        return EmbyApiException(reason)
    }

    private fun PlayMethod.toWireName(): String = when (this) {
        PlayMethod.DIRECT_PLAY -> "DirectPlay"
        PlayMethod.DIRECT_STREAM -> "DirectStream"
        PlayMethod.TRANSCODE -> "Transcode"
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}

class EmbyApiException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class ClientIdentity(
    val deviceName: String,
    val deviceId: String,
    val version: String,
    val clientName: String = "Shadow Media",
) {
    fun authorizationHeader(session: EmbySession? = null): String = buildString {
        append("Emby Client=\"").append(clientName.sanitize()).append("\"")
        append(", Device=\"").append(deviceName.sanitize()).append("\"")
        append(", DeviceId=\"").append(deviceId.sanitize()).append("\"")
        append(", Version=\"").append(version.sanitize()).append("\"")
        session?.let {
            append(", UserId=\"").append(it.userId.sanitize()).append("\"")
        }
    }

    private fun String.sanitize(): String = replace("\"", "").replace("\\", "")
}
