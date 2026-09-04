package top.cylunex.shadowmedia.provider

import top.cylunex.shadowmedia.model.*

interface OpenProvider {
    suspend fun open(request: OpenRequest): OpenPlan
}

/** Keeps URL ordering, headers and ISO markers from the proven legacy resolver. */
class LegacyOpenProvider(private val provider: MediaProvider) : OpenProvider {
    override suspend fun open(request: OpenRequest): OpenPlan {
        require(request.key.providerId == provider.descriptor.id) { "来源不匹配" }
        require(request.kind !in setOf(ContentKind.UNKNOWN, ContentKind.FOLDER, ContentKind.SERIES)) {
            "请先选择具体内容"
        }
        if (request.kind == ContentKind.BOOK) return OpenPlan.Text(
            request.key, ResourceKey(request.key.providerId, request.key.itemId), request.locator as? ProgressLocator.Text,
        )
        if (request.kind == ContentKind.COMIC) return OpenPlan.Comic(
            request.key, ResourceKey(request.key.providerId, request.key.itemId), request.locator as? ProgressLocator.Page,
        )
        val candidates = provider.resolve(UnifiedPlaybackRequest(
            request.key,
            (request.locator as? ProgressLocator.Time)?.positionMs ?: 0,
            request.preferredAudioLanguage,
            request.preferredSubtitleLanguage,
        ))
        require(candidates.isNotEmpty()) { "此来源没有提供可用资源" }
        return when (request.kind) {
            ContentKind.LIVE_CHANNEL -> OpenPlan.Live(request.key, candidates, request.locator as? ProgressLocator.Live)
            ContentKind.AUDIOBOOK -> OpenPlan.Audio(request.key, candidates, request.locator as? ProgressLocator.Time)
            else -> OpenPlan.Video(request.key, candidates, request.locator as? ProgressLocator.Time)
        }
    }
}
