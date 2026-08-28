package top.cylunex.shadowmedia.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class AuthenticateRequestDto(
    @SerialName("Username") val userName: String,
    @SerialName("Pw") val password: String,
)

@Serializable
internal data class AuthenticationResultDto(
    @SerialName("User") val user: UserDto,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("ServerId") val serverId: String = "",
)

@Serializable
internal data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("ServerId") val serverId: String = "",
)

@Serializable
internal data class QueryResultDto(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = items.size,
)

@Serializable
internal data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("Type") val type: String = "Video",
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ProviderIds") val providerIds: Map<String, String> = emptyMap(),
    @SerialName("UserData") val userData: UserDataDto? = null,
)

@Serializable
internal data class UserDataDto(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("IsFavorite") val favorite: Boolean = false,
)

@Serializable
internal data class PlaybackInfoRequestDto(
    @SerialName("UserId") val userId: String,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Long = 120_000_000,
    @SerialName("DeviceProfile") val deviceProfile: DeviceProfileDto = DeviceProfileDto(),
)

@Serializable
internal data class DeviceProfileDto(
    @SerialName("Name") val name: String = "Shadow Media Android",
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Long = 120_000_000,
    @SerialName("MaxStaticBitrate") val maxStaticBitrate: Long = 120_000_000,
    @SerialName("DirectPlayProfiles") val directPlayProfiles: List<DirectPlayProfileDto> =
        listOf(DirectPlayProfileDto()),
    @SerialName("TranscodingProfiles") val transcodingProfiles: List<TranscodingProfileDto> =
        listOf(TranscodingProfileDto()),
)

@Serializable
internal data class DirectPlayProfileDto(
    @SerialName("Container") val container: String = "mp4,mkv,m4v,mov,webm,ts,mpegts",
    @SerialName("Type") val type: String = "Video",
    @SerialName("VideoCodec") val videoCodec: String = "h264,hevc,av1,vp9,mpeg4,mpeg2video",
    @SerialName("AudioCodec") val audioCodec: String = "aac,mp3,opus,vorbis,flac,ac3,eac3",
)

@Serializable
internal data class TranscodingProfileDto(
    @SerialName("Container") val container: String = "ts",
    @SerialName("Type") val type: String = "Video",
    @SerialName("Protocol") val protocol: String = "hls",
    @SerialName("Context") val context: String = "Streaming",
    @SerialName("VideoCodec") val videoCodec: String = "h264,hevc",
    @SerialName("AudioCodec") val audioCodec: String = "aac,ac3,eac3",
)

@Serializable
internal data class PlaybackInfoResponseDto(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceDto> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String = "",
    @SerialName("ErrorCode") val errorCode: String? = null,
)

@Serializable
internal data class MediaSourceDto(
    @SerialName("Id") val id: String,
    @SerialName("Container") val container: String? = null,
    @SerialName("VideoType") val videoType: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @SerialName("DirectStreamUrl") val directStreamUrl: String? = null,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("RequiredHttpHeaders") val requiredHttpHeaders: Map<String, String> = emptyMap(),
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto> = emptyList(),
)

@Serializable
internal data class MediaStreamDto(
    @SerialName("Type") val type: String,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean = false,
)

@Serializable
internal data class PlaybackReportDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String,
    @SerialName("PlaySessionId") val playSessionId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("CanSeek") val canSeek: Boolean,
    @SerialName("IsPaused") val isPaused: Boolean,
    @SerialName("IsMuted") val isMuted: Boolean = false,
    @SerialName("PlayMethod") val playMethod: String,
    @SerialName("EventName") val eventName: String? = null,
    @SerialName("PlaybackRate") val playbackRate: Double = 1.0,
)

@Serializable
internal data class StoredSessionDto(
    val serverUrl: String,
    val serverId: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
    val allowInsecureHttp: Boolean,
)

@Serializable
internal data class StoredSessionsDto(
    val activeSessionKey: String? = null,
    val sessions: List<StoredSessionDto> = emptyList(),
)

@Serializable
internal data class PlaybackOutboxRecordDto(
    val serverUrl: String,
    val serverId: String,
    val userId: String,
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String,
    val positionTicks: Long,
    val isPaused: Boolean,
    val canSeek: Boolean,
    val event: String,
    val playMethod: String,
    val createdAtEpochMs: Long,
    val retryCount: Int = 0,
)
