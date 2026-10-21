package com.shadow.media.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class PlaybackFailureTest {
    private val subtitles = setOf("https://example.com/movie.zh.srt?signature=one")
    private val subtitleIds = setOf("shadow-sidecar-1")

    @Test fun `video request failure does not discard sidecars`() {
        assertFalse(matchesExternalSubtitleFailure(subtitles, subtitleIds, listOf("https://example.com/movie.mp4"), null))
    }

    @Test fun `only exact subtitle request is attributed including signed query`() {
        assertTrue(matchesExternalSubtitleFailure(subtitles, subtitleIds, subtitles.toList(), null))
        assertFalse(matchesExternalSubtitleFailure(subtitles, subtitleIds, listOf("https://example.com/movie.zh.srt?signature=two"), null))
    }

    @Test fun `external text renderer is distinct from an embedded subtitle renderer`() {
        assertTrue(matchesExternalSubtitleFailure(subtitles, subtitleIds, emptyList(), "shadow-sidecar-1"))
        assertFalse(matchesExternalSubtitleFailure(subtitles, subtitleIds, emptyList(), "embedded-1"))
    }

    @Test fun `unknown failure or missing sidecars never triggers subtitle fallback`() {
        assertFalse(matchesExternalSubtitleFailure(subtitles, subtitleIds, emptyList(), null))
        assertFalse(matchesExternalSubtitleFailure(emptySet(), emptySet(), subtitles.toList(), "shadow-sidecar-1"))
    }

    @Test fun `connection errors and timeouts can recover`() {
        listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW).forEach {
            assertTrue(classifyPlaybackFailure(it).canAutoRetry)
        }
    }

    @Test fun `temporary http responses retry but auth missing and permanent server errors do not`() {
        listOf(408, 425, 429, 500, 502, 503, 504).forEach {
            assertTrue(classifyPlaybackFailure(2004, httpStatus = it).canAutoRetry)
        }
        listOf(400, 401, 403, 404, 410, 501, 505).forEach {
            assertFalse(classifyPlaybackFailure(2004, httpStatus = it).canAutoRetry)
        }
        assertEquals(PlaybackFailureKind.ACCESS, classifyPlaybackFailure(2004, httpStatus = 403).kind)
        assertEquals(PlaybackFailureKind.MISSING, classifyPlaybackFailure(2004, httpStatus = 404).kind)
    }

    @Test fun `offline policy and certificate failures override generic network codes`() {
        assertEquals(PlaybackFailureKind.OFFLINE, classifyPlaybackFailure(2001, offlineOnly = true, httpStatus = 503).kind)
        assertFalse(classifyPlaybackFailure(2001, secureConnectionFailure = true, timeout = true).canAutoRetry)
        assertEquals(PlaybackFailureKind.TIMEOUT, classifyPlaybackFailure(2000, timeout = true).kind)
    }

    @Test fun `format decoder and file errors require explicit action`() {
        val cases = mapOf(
            PlaybackException.ERROR_CODE_DECODING_FAILED to PlaybackFailureKind.DECODER,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED to PlaybackFailureKind.FORMAT,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION to PlaybackFailureKind.ACCESS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND to PlaybackFailureKind.MISSING,
            PlaybackException.ERROR_CODE_UNSPECIFIED to PlaybackFailureKind.UNKNOWN,
        )
        cases.forEach { (code, kind) ->
            val failure = classifyPlaybackFailure(code)
            assertEquals(kind, failure.kind)
            assertFalse(failure.canAutoRetry)
        }
    }
}
