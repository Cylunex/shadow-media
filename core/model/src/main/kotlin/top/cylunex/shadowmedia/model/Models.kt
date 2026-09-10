package top.cylunex.shadowmedia.model

data class EmbySession(
    val serverUrl: String,
    val serverId: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
    val allowInsecureHttp: Boolean,
) {
    val providerId: String get() = accountScope("emby", serverUrl, serverId, userId)
    val legacyProviderId: String get() = "emby:$serverId:$userId"
    override fun toString(): String =
        "EmbySession(serverUrl=$serverUrl, serverId=$serverId, userId=$userId, " +
            "userName=$userName, accessToken=<redacted>, allowInsecureHttp=$allowInsecureHttp)"
}

data class MediaLibrary(
    val id: String,
    val name: String,
    val collectionType: String?,
)

data class MediaItem(
    val id: String,
    val name: String,
    val type: String,
    val seriesName: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val runTimeTicks: Long?,
    val playbackPositionTicks: Long,
    val played: Boolean,
    val favorite: Boolean,
    val overview: String? = null,
    val productionYear: Int? = null,
    val communityRating: Double? = null,
    val seriesId: String? = null,
    val imageTag: String? = null,
    val backdropImageTag: String? = null,
    val externalIds: Map<String, String> = emptyMap(),
    val music: MusicMetadata? = null,
)

enum class MediaSort(val wireName: String) {
    DATE_ADDED("DateCreated"),
    NAME("SortName"),
    PREMIERE_DATE("PremiereDate"),
    RATING("CommunityRating"),
    RANDOM("Random"),
}

enum class MediaFilter {
    ALL,
    UNPLAYED,
    RESUMABLE,
    FAVORITES,
    PLAYED,
}

data class BrowseRequest(
    val parentId: String,
    val includeItemTypes: Set<String>,
    val searchTerm: String = "",
    val sort: MediaSort = MediaSort.DATE_ADDED,
    val descending: Boolean = true,
    val filter: MediaFilter = MediaFilter.ALL,
    val startIndex: Int = 0,
    val limit: Int = 60,
)

data class MediaPage(
    val items: List<MediaItem>,
    val startIndex: Int,
    val totalRecordCount: Int,
    val cached: Boolean = false,
) {
    val hasMore: Boolean get() = startIndex + items.size < totalRecordCount
}

enum class MediaSectionKind {
    CONTINUE_WATCHING,
    RECENTLY_ADDED,
    FAVORITES,
    RECOMMENDED,
}

data class MediaSection(
    val id: String,
    val title: String,
    val kind: MediaSectionKind,
    val items: List<MediaItem>,
)

enum class ExternalSourceKind {
    TVBOX_CONFIG,
    LIVE_PLAYLIST,
    DECLARATIVE,
}

data class ExternalSourceSummary(
    val id: String,
    val name: String,
    val url: String,
    val kind: ExternalSourceKind,
    val siteCount: Int = 0,
    val liveCount: Int = 0,
    val safeSiteCount: Int = 0,
    val runtimeRequiredCount: Int = 0,
    val inspectedAtEpochMs: Long,
    val allowInsecureHttp: Boolean = false,
)

data class ExternalSourceImport(
    val summary: ExternalSourceSummary,
    val payload: String,
    val resolvedEntries: List<ExternalMediaEntry> = emptyList(),
    val resolvedCatalogSites: List<ExternalCatalogSite> = emptyList(),
)

data class ExternalCatalogSite(
    val id: String,
    val sourceId: String,
    val name: String,
    val apiUrl: String,
    val allowInsecureHttp: Boolean,
)

data class ExternalMediaEntry(
    val id: String,
    val sourceId: String,
    val title: String,
    val url: String,
    val group: String? = null,
    val logoUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val epgId: String? = null,
    val epgUrl: String? = null,
    val catchupSource: String? = null,
    val catchupDays: Int? = null,
    /** Sensitive request headers are only valid for this exact HTTP origin. */
    val credentialOrigin: String? = null,
    val startPositionMs: Long = 0,
    val isDiscImage: Boolean = false,
)

data class LiveChannel(
    val id: String,
    val title: String,
    val group: String? = null,
    val logoUrl: String? = null,
    val epgId: String? = null,
    val streams: List<ExternalMediaEntry>,
)

data class LiveProgram(
    val sourceId: String,
    val channelId: String,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val iconUrl: String? = null,
)

fun List<ExternalMediaEntry>.toLiveChannels(): List<LiveChannel> = groupBy { entry ->
    entry.epgId?.takeIf(String::isNotBlank)
        ?: "${entry.group.orEmpty()}:${entry.title.trim().lowercase()}"
}.map { (key, streams) ->
    val first = streams.first()
    LiveChannel(
        id = "${first.sourceId}:$key",
        title = first.title,
        group = first.group,
        logoUrl = streams.firstNotNullOfOrNull(ExternalMediaEntry::logoUrl),
        epgId = streams.firstNotNullOfOrNull(ExternalMediaEntry::epgId),
        streams = streams.distinctBy(ExternalMediaEntry::url),
    )
}

enum class PlayMethod {
    DIRECT_PLAY,
    DIRECT_STREAM,
    TRANSCODE,
}

enum class ProviderKind {
    LOCAL_LIBRARY,
    EMBY,
    OPENLIST,
    JELLYFIN,
    OPENSUBSONIC,
    PLEX,
    LIVE_PLAYLIST,
    XTREAM,
    STALKER,
    STREMIO,
    DECLARATIVE_HTTP,
    CATVOD_BRIDGE,
    WEBDAV,
    SMB,
    VIRTUAL_CHANNEL,
}

enum class NetworkStorageKind {
    OPENLIST,
    WEBDAV,
    SMB,
}

data class NetworkStorageConnection(
    val id: String,
    val name: String,
    val kind: NetworkStorageKind,
    /** OpenList/WebDAV URL or SMB host name/IP. */
    val address: String,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val share: String = "",
    val rootPath: String = "/",
    val allowInsecureHttp: Boolean = false,
    val readNfo: Boolean = true,
    val resolveStrm: Boolean = true,
) {
    override fun toString(): String =
        "NetworkStorageConnection(id=$id, name=$name, kind=$kind, address=$address, " +
            "username=$username, password=<redacted>, domain=$domain, share=$share, " +
            "rootPath=$rootPath, allowInsecureHttp=$allowInsecureHttp, " +
            "readNfo=$readNfo, resolveStrm=$resolveStrm)"
}

enum class NetworkStorageHealth {
    ONLINE,
    AUTH_REQUIRED,
    OFFLINE,
}

data class NetworkStorageStatus(
    val connectionId: String,
    val health: NetworkStorageHealth,
    val message: String,
    val latencyMs: Long? = null,
    val checkedAtEpochMs: Long = System.currentTimeMillis(),
)

enum class ProviderCapability {
    HOME,
    BROWSE,
    SEARCH,
    DETAIL,
    PLAYBACK,
    LIVE,
    EPG,
    CATCH_UP,
    DOWNLOAD,
    FAVORITE_SYNC,
    PROGRESS_SYNC,
    SUBTITLES,
}

data class ProviderDescriptor(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val capabilities: Set<ProviderCapability>,
    val enabled: Boolean = true,
    val facets: ProviderFacets = ProviderFacets.from(kind, capabilities),
)

data class MediaKey(
    val providerId: String,
    val itemId: String,
) {
    val stableId: String get() = "$providerId:$itemId"
}

data class UnifiedMediaItem(
    val key: MediaKey,
    val title: String,
    val type: String,
    val subtitle: String? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val durationMs: Long? = null,
    val progressMs: Long = 0,
    val played: Boolean = false,
    val favorite: Boolean = false,
    val externalIds: Map<String, String> = emptyMap(),
    val music: MusicMetadata? = null,
)

data class UnifiedMediaPage(
    val items: List<UnifiedMediaItem>,
    val nextPageToken: String? = null,
    val totalCount: Int? = null,
    val cached: Boolean = false,
)

data class MediaDetail(
    val item: UnifiedMediaItem,
    val children: List<UnifiedMediaItem> = emptyList(),
    val related: List<UnifiedMediaItem> = emptyList(),
    val genres: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val childrenNextPageToken: String? = null,
    val cached: Boolean = false,
)

data class UnifiedPlaybackRequest(
    val key: MediaKey,
    val startPositionMs: Long = 0,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
)

enum class FeatureId(val defaultEnabled: Boolean) {
    LIVE_CENTER(true),
    AGGREGATE_SEARCH(true),
    EXTERNAL_PROVIDERS(true),
    VIRTUAL_CHANNELS(true),
    MEDIA_REQUESTS(true),
    PLAYBACK_TELEMETRY(true),
    MEDIA_MOMENTS(true),
    SEMANTIC_SEARCH(false),
    WATCH_PARTY(false),
    LABS(false),
    FEED_PRELOAD(false),
}

enum class IntegrationKind {
    MOVIEPILOT,
    SEERR,
    TUNARR,
    DISPATCHARR,
}

data class IntegrationConnection(
    val id: String,
    val name: String,
    val kind: IntegrationKind,
    val baseUrl: String,
    val apiToken: String = "",
    val allowInsecureHttp: Boolean = false,
    val playlistUrl: String? = null,
    val epgUrl: String? = null,
) {
    override fun toString(): String =
        "IntegrationConnection(id=$id, name=$name, kind=$kind, baseUrl=$baseUrl, " +
            "apiToken=<redacted>, allowInsecureHttp=$allowInsecureHttp)"
}

enum class IntegrationHealth {
    ONLINE,
    AUTH_REQUIRED,
    OFFLINE,
}

data class IntegrationStatus(
    val connectionId: String,
    val health: IntegrationHealth,
    val latencyMs: Long? = null,
    val version: String? = null,
    val message: String,
    val checkedAtEpochMs: Long,
)

enum class AvailabilityState {
    IN_EMBY,
    EXTERNAL_PLAYABLE,
    REQUESTABLE,
    REQUESTED,
    ACQUIRING,
    IMPORTING,
    AVAILABLE,
    UNAVAILABLE,
}

enum class SegmentType {
    INTRO,
    RECAP,
    CREDITS,
    PREVIEW,
    HIGHLIGHT,
    CHAPTER,
}

enum class SegmentSource {
    EMBY_CHAPTER,
    USER,
    SERVER_ANALYSIS,
}

data class MediaSegment(
    val id: String,
    val providerId: String,
    val itemId: String,
    val type: SegmentType,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
    val source: SegmentSource,
)

data class PlaybackCandidate(
    val url: String,
    val method: PlayMethod,
    val requiredHeaders: Map<String, String>,
    val credentialOrigin: String? = null,
    val isDiscImage: Boolean = false,
    /** Candidate-specific source used when PlaybackInfo exposes multiple versions. */
    val mediaSourceId: String? = null,
)

data class PlaybackPlan(
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String,
    val candidates: List<PlaybackCandidate>,
    val container: String?,
    val videoType: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val runTimeTicks: Long?,
    val sourceCount: Int = 1,
    val supportsDirectPlay: Boolean = false,
    val supportsDirectStream: Boolean = false,
    val supportsTranscoding: Boolean = false,
) {
    init {
        require(candidates.isNotEmpty()) { "Playback plan requires at least one candidate" }
    }

    val primary: PlaybackCandidate get() = candidates.first()
}

enum class PlaybackEvent(val wireName: String?) {
    STARTED(null),
    TIME_UPDATE("TimeUpdate"),
    PAUSE("Pause"),
    UNPAUSE("Unpause"),
    STOPPED(null),
}

const val EMBY_TICKS_PER_MILLISECOND: Long = 10_000L

fun Long.millisecondsToEmbyTicks(): Long = this * EMBY_TICKS_PER_MILLISECOND

fun Long.embyTicksToMilliseconds(): Long = this / EMBY_TICKS_PER_MILLISECOND
