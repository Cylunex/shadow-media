package com.shadow.media.reading

import com.shadow.media.database.NovelPublicationEntity
import com.shadow.media.library.LibraryRepository

internal fun readerBookSettingsKey(assetId: String, publication: NovelPublicationEntity?) =
    publication?.let { LibraryRepository.digest(it.scope + ":" + it.workId) } ?: assetId

/** A new chapter/source is the same reading session, even if inherited defaults changed meanwhile. */
internal class ReaderSettingsBinding(private val loadStore: (String) -> ReaderSettingsStore) {
    private var identity: String? = null
    private var store: ReaderSettingsStore? = null
    fun bind(key: String, current: ReaderSettings): Pair<ReaderSettingsStore, ReaderSettings> {
        if (identity == key) return requireNotNull(store) to current
        val next = loadStore(key)
        identity = key; store = next
        return next to next.load()
    }
}
