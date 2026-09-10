package top.cylunex.shadowmedia.model

/** Catalog type, independent from transport, file format and consumption engine. */
enum class ContentKind { MOVIE, SERIES, EPISODE, LIVE_CHANNEL, BOOK, COMIC, AUDIOBOOK, MUSIC, PODCAST, FOLDER, UNKNOWN }
enum class ExperienceKind { VIDEO, LIVE, TEXT, COMIC, AUDIO, EXTERNAL }
enum class NodeKind { SEASON, EPISODE, VOLUME, CHAPTER, TRACK, PAGE, DISC }
enum class AudioMode { AUDIOBOOK, MUSIC, PODCAST }
fun ContentKind.isAudio() = this in setOf(ContentKind.AUDIOBOOK, ContentKind.MUSIC, ContentKind.PODCAST)

fun contentKind(type: String): ContentKind = when (type.lowercase()) {
    "movie", "video", "musicvideo", "strm" -> ContentKind.MOVIE
    "series", "boxset" -> ContentKind.SERIES
    "episode" -> ContentKind.EPISODE
    "livechannel", "livestream" -> ContentKind.LIVE_CHANNEL
    "book", "ebook", "novel", "epub", "txt", "pdf" -> ContentKind.BOOK
    "comic", "cbz", "imagedirectory" -> ContentKind.COMIC
    "audiobook", "audio", "m4b", "mp3" -> ContentKind.AUDIOBOOK
    "music", "song" -> ContentKind.MUSIC
    "podcast", "podcastepisode" -> ContentKind.PODCAST
    "folder", "season", "collectionfolder", "musicalbum", "musicartist", "playlist" -> ContentKind.FOLDER
    else -> ContentKind.UNKNOWN
}

fun contentKindForFile(name: String): ContentKind = when (name.substringAfterLast('.', "").lowercase()) {
    "epub", "txt", "pdf" -> ContentKind.BOOK
    "cbz", "zip" -> ContentKind.COMIC
    "m4b", "m4a", "mp3", "aac", "ogg", "opus", "flac", "wav" -> ContentKind.AUDIOBOOK
    "mp4", "mkv", "avi", "mov", "ts", "m2ts", "iso", "strm", "webm" -> ContentKind.MOVIE
    else -> ContentKind.UNKNOWN
}

data class ResourceKey(val providerId: String, val itemId: String, val revision: String? = null)
data class WorkId(val value: String)
data class RenditionId(val value: String)
data class ContentNode(val id: String, val title: String, val kind: NodeKind, val parentId: String? = null)

/** Coordinates belong to a specific edition/resource revision, not just a title. */
sealed interface ProgressLocator {
    data class Time(val trackId: String, val positionMs: Long, val durationMs: Long? = null) : ProgressLocator {
        init { require(positionMs >= 0); require(durationMs == null || durationMs >= 0) }
    }
    data class Text(val href: String, val locatorJson: String) : ProgressLocator
    data class Page(val chapterId: String, val pageIndex: Int, val offset: Float = 0f) : ProgressLocator {
        init { require(pageIndex >= 0); require(offset.isFinite() && offset in 0f..1f) }
    }
    data class Live(val channelId: String, val programStartEpochMs: Long? = null, val positionMs: Long = 0) : ProgressLocator
}

data class OpenRequest(
    val key: MediaKey,
    val kind: ContentKind,
    val locator: ProgressLocator? = null,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
)

/** Ephemeral plans must never be persisted: legacy candidates can contain scoped headers. */
sealed interface OpenPlan {
    val key: MediaKey
    val experience: ExperienceKind
    data class Video(override val key: MediaKey, val candidates: List<PlaybackCandidate>, val locator: ProgressLocator.Time?) : OpenPlan {
        override val experience = ExperienceKind.VIDEO
    }
    data class Live(override val key: MediaKey, val candidates: List<PlaybackCandidate>, val locator: ProgressLocator.Live?) : OpenPlan {
        override val experience = ExperienceKind.LIVE
    }
    data class Text(override val key: MediaKey, val resource: ResourceKey, val locator: ProgressLocator.Text?) : OpenPlan {
        override val experience = ExperienceKind.TEXT
    }
    data class Comic(override val key: MediaKey, val resource: ResourceKey, val locator: ProgressLocator.Page?) : OpenPlan {
        override val experience = ExperienceKind.COMIC
    }
    data class Audio(override val key: MediaKey, val candidates: List<PlaybackCandidate>, val locator: ProgressLocator.Time?) : OpenPlan {
        override val experience = ExperienceKind.AUDIO
    }
}

/** Length-prefixed fields avoid collisions without changing legacy MediaKey.stableId. */
fun scopedContentId(vararg parts: String): String = parts.joinToString("") { "${it.length}:$it" }

data class ProgressEnvelope(
    val profileId: String,
    val key: MediaKey,
    val renditionId: String,
    val resourceRevision: String?,
    val locator: ProgressLocator,
    val completed: Boolean,
    val sessionId: String,
    val sequence: Long,
) {
    val stableId: String get() = scopedContentId(profileId, key.providerId, key.itemId, renditionId)
}


data class MusicMetadata(val album: String = "", val albumId: String = "", val artist: String = "",
    val albumArtist: String = "", val disc: Int = 0, val track: Int = 0)
