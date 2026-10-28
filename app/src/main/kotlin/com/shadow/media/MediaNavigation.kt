package com.shadow.media

/** Stable keys are persisted; display labels and menu lengths are deliberately independent. */
enum class MediaModule(val title: String, val destinations: List<MediaDestination>, val assetKinds: Set<String>) {
    VIDEO("影视", listOf(MediaDestination.HOME, MediaDestination.LIBRARIES, MediaDestination.VIDEO_SOURCES), setOf("MOVIE", "EPISODE")),
    NOVEL("小说", listOf(MediaDestination.SHELF, MediaDestination.CITY, MediaDestination.DISCOVERY, MediaDestination.BOOK_SOURCES), setOf("BOOK")),
    MUSIC("音乐", listOf(MediaDestination.MUSIC_LIBRARY, MediaDestination.PLAYLISTS), setOf("MUSIC")),
    COMIC("漫画", listOf(MediaDestination.SHELF, MediaDestination.COMIC_LIBRARY), setOf("COMIC")),
    AUDIOBOOK("听书", listOf(MediaDestination.SHELF, MediaDestination.AUDIO_LIBRARY), setOf("AUDIOBOOK")),
    LIVE("直播", listOf(MediaDestination.CHANNELS), emptySet());

    fun destination(saved: String?): MediaDestination = destinations.firstOrNull { it.name == saved } ?: destinations.first()
    val hasBottomNavigation get() = destinations.size > 1

    companion object {
        fun restore(saved: String?): MediaModule = entries.firstOrNull { it.name == saved } ?: VIDEO
    }
}

enum class MediaDestination(val title: String) {
    HOME("首页"), LIBRARIES("媒体库"), VIDEO_SOURCES("影视仓"),
    SHELF("书架"), CITY("书城"), DISCOVERY("发现"), BOOK_SOURCES("书源"),
    MUSIC_LIBRARY("音乐库"), PLAYLISTS("歌单"), COMIC_LIBRARY("漫画库"), AUDIO_LIBRARY("有声库"), CHANNELS("频道")
}
