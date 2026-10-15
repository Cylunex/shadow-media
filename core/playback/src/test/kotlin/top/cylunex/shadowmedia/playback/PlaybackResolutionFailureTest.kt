package top.cylunex.shadowmedia.playback

import org.junit.Test
import org.junit.Assert.*
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import top.cylunex.shadowmedia.network.EmbyApiException
class PlaybackResolutionFailureTest {
    @Test fun `initial resolution retries only transport and transient server failures`() {
        assertTrue(classifyPlaybackResolutionFailure(SocketTimeoutException("https://secret.example/?token=secret"), false).canAutoRetry)
        assertTrue(classifyPlaybackResolutionFailure(EmbyApiException("temporary", httpStatus = 503), false).canAutoRetry)
        assertFalse(classifyPlaybackResolutionFailure(EmbyApiException("auth", httpStatus = 403), false).canAutoRetry)
        assertFalse(classifyPlaybackResolutionFailure(SSLException("certificate"), false).canAutoRetry)
        assertFalse(classifyPlaybackResolutionFailure(EmbyApiException("Invalid playback info"), false).canAutoRetry)
        assertFalse(classifyPlaybackResolutionFailure(IllegalArgumentException("broken json"), false).canAutoRetry)
        assertEquals(PlaybackFailureKind.OFFLINE, classifyPlaybackResolutionFailure(SocketTimeoutException(), true).kind)
        assertFalse(classifyPlaybackResolutionFailure(SocketTimeoutException("secret"), false).message.contains("secret"))
    }
}
