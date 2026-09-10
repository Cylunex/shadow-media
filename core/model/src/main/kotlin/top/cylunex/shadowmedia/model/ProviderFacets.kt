package top.cylunex.shadowmedia.model

enum class MusicGrouping { SONGS, ALBUMS, ARTISTS, FOLDERS }
enum class CapabilitySupport { NONE, LOCAL, REMOTE, NEGOTIATED }
enum class PlaylistWrite { NONE, CREATE_COPY_AND_VERIFY }
enum class OfflineTransport { NONE, PROGRESSIVE, PUBLICATION }

data class CatalogFacet(val paged: Boolean = false, val searchable: Boolean = false, val musicGroups: Set<MusicGrouping> = emptySet())
data class PlaybackFacet(val canonicalResolve: Boolean = false, val transcode: CapabilitySupport = CapabilitySupport.NONE,
    val lyrics: CapabilitySupport = CapabilitySupport.NONE, val offline: OfflineTransport = OfflineTransport.NONE)
data class UserStateFacet(val favorites: CapabilitySupport = CapabilitySupport.LOCAL, val progress: CapabilitySupport = CapabilitySupport.LOCAL,
    val playlistWrite: PlaylistWrite = PlaylistWrite.NONE)
data class ProviderFacets(val catalog: CatalogFacet = CatalogFacet(), val playback: PlaybackFacet = PlaybackFacet(), val userState: UserStateFacet = UserStateFacet()) {
    companion object {
        /** Legacy flags remain a compatibility projection; new operations use typed facets. */
        fun from(kind: ProviderKind, flags: Set<ProviderCapability>): ProviderFacets {
            val music = kind in setOf(ProviderKind.EMBY, ProviderKind.JELLYFIN, ProviderKind.OPENSUBSONIC)
            val nativeMusic = kind in setOf(ProviderKind.JELLYFIN, ProviderKind.OPENSUBSONIC)
            return ProviderFacets(
                CatalogFacet(music && ProviderCapability.BROWSE in flags, ProviderCapability.SEARCH in flags,
                    if (music) setOf(MusicGrouping.SONGS, MusicGrouping.ALBUMS, MusicGrouping.ARTISTS) else emptySet()),
                PlaybackFacet(ProviderCapability.PLAYBACK in flags,
                    if (music) CapabilitySupport.REMOTE else CapabilitySupport.NONE,
                    if (nativeMusic) CapabilitySupport.NEGOTIATED else CapabilitySupport.NONE,
                    if (music || ProviderCapability.DOWNLOAD in flags) OfflineTransport.PROGRESSIVE else OfflineTransport.NONE),
                UserStateFacet(if (ProviderCapability.FAVORITE_SYNC in flags) CapabilitySupport.REMOTE else CapabilitySupport.LOCAL,
                    if (ProviderCapability.PROGRESS_SYNC in flags) CapabilitySupport.REMOTE else CapabilitySupport.LOCAL,
                    if (nativeMusic) PlaylistWrite.CREATE_COPY_AND_VERIFY else PlaylistWrite.NONE),
            )
        }
    }
}
