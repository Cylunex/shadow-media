package top.cylunex.shadowmedia.model

import org.junit.Assert.*
import org.junit.Test

class FeedPreloadPolicyTest {
    @Test fun onlySettledAdjacentWindowCanPreload() {
        assertEquals(FeedPreloadTarget.NEXT_THREE_SECONDS, FeedPreloadPolicy.target(4, 3, true, true, true))
        assertEquals(FeedPreloadTarget.TRACKS, FeedPreloadPolicy.target(2, 3, true, true, true))
        for (index in listOf(0, 1, 3, 5, 8)) assertEquals(FeedPreloadTarget.NONE, FeedPreloadPolicy.target(index, 3, true, true, true))
        assertEquals(FeedPreloadTarget.NONE, FeedPreloadPolicy.target(4, 3, false, true, true))
        assertEquals(FeedPreloadTarget.NONE, FeedPreloadPolicy.target(4, 3, true, false, true))
        assertEquals(FeedPreloadTarget.NONE, FeedPreloadPolicy.target(4, 3, true, true, false))
    }
    @Test fun discAdaptiveTranscodeAndSlowResolutionAreExcluded() {
        val plan = PlaybackPlan("item", "source", "session", listOf(PlaybackCandidate("https://example.com/stream", PlayMethod.DIRECT_PLAY, emptyMap())), "mp4", null, null, null, 100000000)
        assertTrue(FeedPreloadPolicy.eligible(plan, 20))
        assertFalse(FeedPreloadPolicy.eligible(plan, 1600))
        assertFalse(FeedPreloadPolicy.eligible(plan.copy(container = "iso"), 20))
        assertFalse(FeedPreloadPolicy.eligible(plan.copy(candidates = listOf(plan.primary.copy(method = PlayMethod.TRANSCODE))), 20))
        assertFalse(FeedPreloadPolicy.eligible(plan.copy(candidates = listOf(plan.primary.copy(url = "https://example.com/live.m3u8?token=example"))), 20))
    }
}
