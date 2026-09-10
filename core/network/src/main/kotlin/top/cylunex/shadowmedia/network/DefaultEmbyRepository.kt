package top.cylunex.shadowmedia.network

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import top.cylunex.shadowmedia.model.BrowseRequest
import top.cylunex.shadowmedia.model.MediaItem
import top.cylunex.shadowmedia.model.MediaFilter
import top.cylunex.shadowmedia.model.MediaLibrary
import top.cylunex.shadowmedia.model.MediaPage
import top.cylunex.shadowmedia.model.MediaSection
import top.cylunex.shadowmedia.model.MediaSectionKind
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
    ): List<MediaItem> {
        val allItems = mutableListOf<BaseItemDto>()
        var startIndex = 0
        var totalRecordCount: Int
        var pageItemCount: Int
        do {
            val url = EmbyEndpoints.endpoint(session.serverUrl, "Users", session.userId, "Items")
                .newBuilder()
                .addQueryParameter("ParentId", libraryId)
                .addQueryParameter("Recursive", "true")
                .addQueryParameter("IncludeItemTypes", "Movie,Episode,Video")
                .addQueryParameter("SortBy", "DateCreated")
                .addQueryParameter("SortOrder", "Descending")
                .addQueryParameter("StartIndex", startIndex.toString())
                .addQueryParameter("Limit", ITEMS_PAGE_SIZE.toString())
                .build()
            val page: QueryResultDto = executeJson(authenticatedRequest(session, url).get().build())
            allItems += page.items
            pageItemCount = page.items.size
            startIndex += pageItemCount
            totalRecordCount = page.totalRecordCount
        } while (pageItemCount > 0 && startIndex < totalRecordCount)

        return allItems.distinctBy(BaseItemDto::id).map { it.toModel() }
    }

    override suspend fun home(
        session: EmbySession,
        libraryIds: List<String>,
    ): List<MediaSection> = coroutineScope {
        val continueWatching = async {
            browse(
                session,
                BrowseRequest(
                    parentId = "",
                    includeItemTypes = PLAYABLE_ITEM_TYPES,
                    filter = MediaFilter.RESUMABLE,
                    limit = HOME_SECTION_LIMIT,
                )
            ).items
        }
        val favorites = async {
            browse(
                session,
                BrowseRequest(
                    parentId = "",
                    includeItemTypes = PLAYABLE_ITEM_TYPES,
                    filter = MediaFilter.FAVORITES,
                    limit = HOME_SECTION_LIMIT,
                )
            ).items
        }
        val latest = libraryIds.map { libraryId ->
            async {
                browse(
                    session,
                    BrowseRequest(
                        parentId = libraryId,
                        includeItemTypes = PLAYABLE_ITEM_TYPES,
                        limit = HOME_ITEMS_PER_LIBRARY,
                    )
                ).items
            }
        }.flatMap { it.await() }.distinctBy(MediaItem::id).take(HOME_SECTION_LIMIT)

        listOf(
            MediaSection(
                id = "continue",
                title = "继续观看",
                kind = MediaSectionKind.CONTINUE_WATCHING,
                items = continueWatching.await(),
            ),
            MediaSection(
                id = "latest",
                title = "最近新增",
                kind = MediaSectionKind.RECENTLY_ADDED,
                items = latest,
            ),
            MediaSection(
                id = "favorites",
                title = "我的收藏",
                kind = MediaSectionKind.FAVORITES,
                items = favorites.await(),
            ),
        ).filter { it.items.isNotEmpty() }
    }

    override suspend fun browse(session: EmbySession, request: BrowseRequest): MediaPage {
        require(request.startIndex >= 0) { "StartIndex 不能小于 0" }
        require(request.limit in 1..MAX_BROWSE_PAGE_SIZE) { "Limit 必须在 1..$MAX_BROWSE_PAGE_SIZE" }
        require(request.includeItemTypes.isNotEmpty()) { "至少选择一种媒体类型" }
        val url = EmbyEndpoints.endpoint(session.serverUrl, "Users", session.userId, "Items")
            .newBuilder()
            .apply {
                request.parentId.takeIf(String::isNotBlank)?.let { addQueryParameter("ParentId", it) }
                addQueryParameter("Recursive", "true")
                addQueryParameter("IncludeItemTypes", request.includeItemTypes.sorted().joinToString(","))
                addQueryParameter("Fields", CATALOG_FIELDS)
                addQueryParameter("EnableImages", "true")
                addQueryParameter("EnableUserData", "true")
                addQueryParameter("SortBy", request.sort.wireName)
                addQueryParameter("SortOrder", if (request.descending) "Descending" else "Ascending")
                addQueryParameter("StartIndex", request.startIndex.toString())
                addQueryParameter("Limit", request.limit.toString())
                request.searchTerm.trim().takeIf(String::isNotBlank)?.let {
                    addQueryParameter("SearchTerm", it)
                }
                when (request.filter) {
                    MediaFilter.ALL -> Unit
                    MediaFilter.UNPLAYED -> addQueryParameter("IsUnplayed", "true")
                    MediaFilter.RESUMABLE -> addQueryParameter("IsResumable", "true")
                    MediaFilter.FAVORITES -> addQueryParameter("IsFavorite", "true")
                    MediaFilter.PLAYED -> addQueryParameter("IsPlayed", "true")
                }
            }
            .build()
        val page: QueryResultDto = executeJson(authenticatedRequest(session, url).get().build())
        return MediaPage(
            items = page.items.distinctBy(BaseItemDto::id).map { it.toModel() },
            startIndex = request.startIndex,
            totalRecordCount = page.totalRecordCount,
        )
    }

    override suspend fun children(session: EmbySession, parentId: String): List<MediaItem> {
        val url = EmbyEndpoints.endpoint(session.serverUrl, "Users", session.userId, "Items")
            .newBuilder()
            .addQueryParameter("ParentId", parentId)
            .addQueryParameter("Recursive", "true")
            .addQueryParameter("IncludeItemTypes", "Episode,Movie,Video")
            .addQueryParameter("Fields", CATALOG_FIELDS)
            .addQueryParameter("EnableImages", "true")
            .addQueryParameter("EnableUserData", "true")
            .addQueryParameter("SortBy", "ParentIndexNumber,IndexNumber,SortName")
            .addQueryParameter("SortOrder", "Ascending")
            .addQueryParameter("Limit", MAX_BROWSE_PAGE_SIZE.toString())
            .build()
        val result: QueryResultDto = executeJson(authenticatedRequest(session, url).get().build())
        return result.items.distinctBy(BaseItemDto::id).map { it.toModel() }
    }

    override suspend fun setFavorite(session: EmbySession, itemId: String, favorite: Boolean) {
        val url = EmbyEndpoints.endpoint(
            session.serverUrl,
            "Users",
            session.userId,
            "FavoriteItems",
            itemId,
        )
        val builder = authenticatedRequest(session, url)
        executeEmpty(if (favorite) builder.post(EMPTY_BODY).build() else builder.delete().build())
    }

    override suspend fun item(session: EmbySession, itemId: String): MediaItem {
        val dto: BaseItemDto = executeJson(authenticatedRequest(session,
            EmbyEndpoints.endpoint(session.serverUrl, "Users", session.userId, "Items", itemId)).get().build())
        return dto.toModel()
    }

    override suspend fun audioPlan(session: EmbySession, itemId: String): PlaybackPlan {
        val profile = DeviceProfileDto(
            directPlayProfiles = listOf(DirectPlayProfileDto(container = "mp3,m4a,m4b,aac,flac,ogg,opus,wav,webm", type = "Audio", videoCodec = "", audioCodec = "aac,mp3,opus,vorbis,flac,pcm_s16le,pcm_s24le")),
            transcodingProfiles = listOf(TranscodingProfileDto(container = "mp3", type = "Audio", protocol = "http", videoCodec = "", audioCodec = "mp3")))
        val response: PlaybackInfoResponseDto = executeJson(authenticatedRequest(session,
            EmbyEndpoints.endpoint(session.serverUrl, "Items", itemId, "PlaybackInfo"))
            .post(json.encodeToString(PlaybackInfoRequestDto(userId = session.userId, deviceProfile = profile)).toRequestBody(JSON_MEDIA_TYPE)).build())
        if (response.errorCode != null) throw EmbyApiException("音频 PlaybackInfo: ${response.errorCode}")
        val source = response.mediaSources.firstOrNull() ?: throw EmbyApiException("音频没有可播放的媒体源")
        val candidates = response.mediaSources.flatMap { media ->
            val headers = media.requiredHttpHeaders.filterKeys { !it.equals("Range", true) } + ("X-Emby-Token" to session.accessToken)
            val canonical = EmbyEndpoints.endpoint(session.serverUrl, "Audio", itemId, "stream").newBuilder()
                .addQueryParameter("Static", "true").addQueryParameter("MediaSourceId", media.id)
                .addQueryParameter("PlaySessionId", response.playSessionId).build().toString()
            listOfNotNull(
                media.directStreamUrl?.takeIf(String::isNotBlank)?.let { PlaybackCandidate(EmbyEndpoints.resolvePlaybackUrl(session.serverUrl, it), PlayMethod.DIRECT_STREAM, headers, credentialOrigin = session.serverUrl, mediaSourceId = media.id) },
                PlaybackCandidate(canonical, PlayMethod.DIRECT_STREAM, headers, credentialOrigin = session.serverUrl, mediaSourceId = media.id),
                media.transcodingUrl?.takeIf(String::isNotBlank)?.let { PlaybackCandidate(EmbyEndpoints.resolvePlaybackUrl(session.serverUrl, it), PlayMethod.TRANSCODE, headers, credentialOrigin = session.serverUrl, mediaSourceId = media.id) })
        }.distinctBy { it.url }
        return PlaybackPlan(itemId, source.id, response.playSessionId, candidates,
            container = source.container, videoType = null, videoCodec = null,
            audioCodec = source.mediaStreams.firstOrNull { it.type.equals("Audio", true) }?.codec,
            runTimeTicks = source.runTimeTicks, supportsDirectStream = true)
    }

    override suspend fun updateAudioPosition(session: EmbySession, itemId: String, positionMs: Long, completed: Boolean) {
        require(positionMs >= 0)
        val body = kotlinx.serialization.json.buildJsonObject {
            put("PlaybackPositionTicks", kotlinx.serialization.json.JsonPrimitive(positionMs.coerceAtMost(Long.MAX_VALUE / 10_000) * 10_000))
            put("Played", kotlinx.serialization.json.JsonPrimitive(completed))
        }
        executeEmpty(authenticatedRequest(session, EmbyEndpoints.endpoint(session.serverUrl, "Users", session.userId, "Items", itemId, "UserData"))
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build())
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
            it.supportsDirectPlay ||
                it.supportsDirectStream ||
                it.supportsTranscoding ||
                !it.directStreamUrl.isNullOrBlank() ||
                !it.transcodingUrl.isNullOrBlank()
        } ?: response.mediaSources.firstOrNull()
            ?: throw EmbyApiException(
                "PlaybackInfo 返回 0 个 MediaSources；请检查账号播放权限、STRM 条目和服务端日志"
            )
        val orderedSources = listOf(source) + response.mediaSources.filterNot { it === source }
        fun MediaSourceDto.isDiscImage(): Boolean = videoType.equals("Iso", ignoreCase = true) ||
            container?.split(',')?.any { it.equals("iso", ignoreCase = true) } == true
        fun MediaSourceDto.canonicalCandidate(): PlaybackCandidate? {
            val discImage = isDiscImage()
            if (!discImage && !supportsDirectPlay && !supportsDirectStream && directStreamUrl.isNullOrBlank()) {
                return null
            }
            // This is a compatibility fallback for servers that omit or return an unusable
            // DirectStreamUrl. Prefer the server-advertised route because MediaWarp may rewrite
            // it with mode-specific parameters that cannot be reconstructed client-side.
            return PlaybackCandidate(
                url = EmbyEndpoints.directPlayUrl(
                    serverUrl = session.serverUrl,
                    itemId = itemId,
                    mediaSourceId = id,
                    container = container,
                    playSessionId = response.playSessionId,
                ),
                method = PlayMethod.DIRECT_STREAM,
                requiredHeaders = requiredHttpHeaders,
                isDiscImage = discImage,
                mediaSourceId = id,
            )
        }
        fun MediaSourceDto.advertisedDirectCandidate(): PlaybackCandidate? {
            if (isDiscImage()) return null
            val directUrl = directStreamUrl?.takeIf(String::isNotBlank) ?: return null
            return PlaybackCandidate(
                url = EmbyEndpoints.resolvePlaybackUrl(session.serverUrl, directUrl),
                method = if (supportsDirectPlay) PlayMethod.DIRECT_PLAY else PlayMethod.DIRECT_STREAM,
                requiredHeaders = requiredHttpHeaders,
                mediaSourceId = id,
            )
        }
        fun MediaSourceDto.transcodingCandidates(): List<PlaybackCandidate> {
            if (isDiscImage()) return emptyList()
            val url = transcodingUrl?.takeIf(String::isNotBlank)?.let {
                EmbyEndpoints.resolvePlaybackUrl(session.serverUrl, it)
            } ?: if (supportsTranscoding) {
                EmbyEndpoints.hlsTranscodingUrl(
                    serverUrl = session.serverUrl,
                    itemId = itemId,
                    mediaSourceId = id,
                    playSessionId = response.playSessionId,
                    deviceId = clientIdentity.deviceId,
                )
            } else null
            return url?.let {
                listOf(
                    PlaybackCandidate(
                        url = it,
                        method = PlayMethod.TRANSCODE,
                        requiredHeaders = requiredHttpHeaders,
                        mediaSourceId = id,
                    )
                )
            }.orEmpty()
        }

        val candidates = (
            orderedSources.mapNotNull(MediaSourceDto::advertisedDirectCandidate) +
                orderedSources.mapNotNull(MediaSourceDto::canonicalCandidate) +
                orderedSources.flatMap(MediaSourceDto::transcodingCandidates)
            ).ifEmpty {
                // Some Emby versions return a MediaSource but omit all capabilities and URLs.
                listOf(
                    PlaybackCandidate(
                        url = EmbyEndpoints.directPlayUrl(
                            serverUrl = session.serverUrl,
                            itemId = itemId,
                            mediaSourceId = source.id,
                            container = source.container,
                            playSessionId = response.playSessionId,
                        ),
                        method = PlayMethod.DIRECT_STREAM,
                        requiredHeaders = source.requiredHttpHeaders,
                        isDiscImage = source.isDiscImage(),
                        mediaSourceId = source.id,
                    )
                )
            }

        return PlaybackPlan(
            itemId = itemId,
            mediaSourceId = source.id,
            playSessionId = response.playSessionId,
            candidates = candidates.distinctBy { it.url },
            container = source.container,
            videoType = source.videoType,
            videoCodec = source.mediaStreams.firstOrNull { it.type.equals("Video", true) }?.codec,
            audioCodec = source.mediaStreams.firstOrNull { it.type.equals("Audio", true) && it.isDefault }?.codec
                ?: source.mediaStreams.firstOrNull { it.type.equals("Audio", true) }?.codec,
            runTimeTicks = source.runTimeTicks,
            sourceCount = response.mediaSources.size,
            supportsDirectPlay = orderedSources.any(MediaSourceDto::supportsDirectPlay),
            supportsDirectStream = orderedSources.any(MediaSourceDto::supportsDirectStream),
            supportsTranscoding = orderedSources.any(MediaSourceDto::supportsTranscoding),
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
            itemId = report.itemId,
            mediaSourceId = report.mediaSourceId,
            playSessionId = report.playSessionId,
            positionTicks = report.positionTicks,
            canSeek = report.canSeek,
            isPaused = report.isPaused,
            playMethod = report.playMethod.toWireName(),
            eventName = report.event.wireName,
        )
        val body = json.encodeToString(dto).toRequestBody(JSON_MEDIA_TYPE)
        executeEmpty(authenticatedRequest(session, url).post(body).build())
        if (report.event == PlaybackEvent.STOPPED && report.playMethod == PlayMethod.TRANSCODE) {
            val cleanupUrl = EmbyEndpoints.endpoint(session.serverUrl, "Videos", "ActiveEncodings")
                .newBuilder()
                .addQueryParameter("DeviceId", clientIdentity.deviceId)
                .addQueryParameter("PlaySessionId", report.playSessionId)
                .build()
            runCatching { executeEmpty(authenticatedRequest(session, cleanupUrl).delete().build()) }
        }
    }

    override suspend fun deleteItem(session: EmbySession, itemId: String) {
        val url = EmbyEndpoints.endpoint(session.serverUrl, "Items")
            .newBuilder()
            .addQueryParameter("Ids", itemId)
            .build()
        executeEmpty(authenticatedRequest(session, url).delete().build())
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
        private const val ITEMS_PAGE_SIZE = 200
        private const val MAX_BROWSE_PAGE_SIZE = 200
        private const val HOME_SECTION_LIMIT = 24
        private const val HOME_ITEMS_PER_LIBRARY = 10
        private const val CATALOG_FIELDS =
            "Overview,ProductionYear,CommunityRating,SeriesId,ImageTags,BackdropImageTags,ProviderIds,Album,AlbumId,Artists,AlbumArtist"
        private val PLAYABLE_ITEM_TYPES = setOf("Movie", "Episode", "Video")
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

internal fun BaseItemDto.toModel(): MediaItem = MediaItem(
        id = id,
        name = name,
        type = type,
        seriesName = seriesName,
        seasonNumber = parentIndexNumber,
        episodeNumber = indexNumber,
        runTimeTicks = runTimeTicks,
        playbackPositionTicks = userData?.playbackPositionTicks ?: 0,
        played = userData?.played ?: false,
        favorite = userData?.favorite ?: false,
        overview = overview,
        productionYear = productionYear,
        communityRating = communityRating,
        seriesId = seriesId,
        imageTag = imageTags["Primary"],
        backdropImageTag = backdropImageTags.firstOrNull(),
        externalIds = providerIds,
        music = if (type.equals("Audio", true) || type.equals("MusicAlbum", true)) top.cylunex.shadowmedia.model.MusicMetadata(
            album, albumId, artists.joinToString(" / "), albumArtist, parentIndexNumber ?: 0, indexNumber ?: 0) else null,
    )
