package top.cylunex.shadowmedia.model

data class EmbySession(
    val serverUrl: String,
    val serverId: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
    val allowInsecureHttp: Boolean,
) {
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
) {
    val hasMore: Boolean get() = startIndex + items.size < totalRecordCount
}

enum class MediaSectionKind {
    CONTINUE_WATCHING,
    RECENTLY_ADDED,
    FAVORITES,
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
    EMBY,
    JELLYFIN,
    PLEX,
    LIVE_PLAYLIST,
    XTREAM,
    STALKER,
    STREMIO,
    DECLARATIVE_HTTP,
    CATVOD_BRIDGE,
    WEBDAV,
    VIRTUAL_CHANNEL,
}

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
)

data class UnifiedMediaPage(
    val items: List<UnifiedMediaItem>,
    val nextPageToken: String? = null,
    val totalCount: Int? = null,
)

data class MediaDetail(
    val item: UnifiedMediaItem,
    val children: List<UnifiedMediaItem> = emptyList(),
    val related: List<UnifiedMediaItem> = emptyList(),
    val genres: List<String> = emptyList(),
    val people: List<String> = emptyList(),
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
}

data class PlaybackCandidate(
    val url: String,
    val method: PlayMethod,
    val requiredHeaders: Map<String, String>,
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
