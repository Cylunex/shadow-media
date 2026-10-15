package top.cylunex.shadowmedia.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRecoveryTest {
    private val networkFailure = PlaybackFailure(PlaybackFailureKind.NETWORK, "network", true)

    @Test fun `offline failure waits without spending attempts then reconnects once`() = runTest {
        var retries = 0
        val recovery = PlaybackRecovery(this, { true }) { retries++ }
        recovery.setNetworkAvailable(false)
        assertTrue(recovery.recover(networkFailure))
        advanceTimeBy(60_000)
        assertEquals(0, retries)
        assertEquals(PlaybackRecoveryPhase.WAITING_FOR_NETWORK, recovery.state.value.phase)
        recovery.setNetworkAvailable(true)
        recovery.setNetworkAvailable(true)
        advanceTimeBy(999); runCurrent()
        assertEquals(0, retries)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, retries)
        advanceTimeBy(60_000)
        assertEquals(1, retries)
    }

    @Test fun `pause cancels delayed retry and resuming schedules with current intent`() = runTest {
        var retries = 0
        val recovery = PlaybackRecovery(this, { true }) { retries++ }
        recovery.recover(networkFailure)
        advanceTimeBy(500)
        recovery.setPlayWhenReady(false)
        advanceTimeBy(10_000)
        assertEquals(0, retries)
        assertEquals(PlaybackRecoveryPhase.WAITING_FOR_PLAY, recovery.state.value.phase)
        recovery.setPlayWhenReady(true)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, retries)
    }

    @Test fun `background reconnect cannot start playback and foreground respects pause`() = runTest {
        var retries = 0
        val recovery = PlaybackRecovery(this, { true }) { retries++ }
        recovery.setNetworkAvailable(false)
        recovery.recover(networkFailure)
        recovery.setForeground(false)
        recovery.setPlayWhenReady(false)
        recovery.setNetworkAvailable(true)
        advanceTimeBy(10_000)
        assertEquals(0, retries)
        recovery.setForeground(true)
        advanceTimeBy(10_000)
        assertEquals(0, retries)
        recovery.setPlayWhenReady(true)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, retries)
    }

    @Test fun `two attempts exhaust budget even when ready is reported between errors`() = runTest {
        var retries = 0
        val recovery = PlaybackRecovery(this, { true }) { retries++ }
        assertTrue(recovery.recover(networkFailure))
        advanceTimeBy(1_000); runCurrent()
        recovery.ready()
        assertTrue(recovery.recover(networkFailure))
        advanceTimeBy(2_999); runCurrent()
        assertEquals(1, retries)
        advanceTimeBy(1); runCurrent()
        recovery.ready()
        assertFalse(recovery.recover(networkFailure))
        recovery.setNetworkAvailable(false); recovery.setNetworkAvailable(true)
        advanceTimeBy(60_000)
        assertEquals(2, retries)
        assertEquals(PlaybackRecoveryPhase.FAILED, recovery.state.value.phase)
        assertTrue(recovery.manualRetry())
        assertTrue(recovery.recover(networkFailure))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(3, retries)
    }

    @Test fun `permanent errors never queue automatic work`() = runTest {
        var retries = 0
        val recovery = PlaybackRecovery(this, { true }) { retries++ }
        assertFalse(recovery.recover(PlaybackFailure(PlaybackFailureKind.ACCESS, "access")))
        recovery.setNetworkAvailable(false); recovery.setNetworkAvailable(true)
        advanceTimeBy(60_000)
        assertEquals(0, retries)
    }

    @Test fun `close invalidates pending retry and late network callbacks`() = runTest {
        var retries = 0
        val recovery = PlaybackRecovery(this, { true }) { retries++ }
        recovery.recover(networkFailure)
        advanceTimeBy(500)
        recovery.close()
        recovery.setNetworkAvailable(false); recovery.setNetworkAvailable(true)
        recovery.setPlayWhenReady(true)
        assertFalse(recovery.manualRetry())
        assertFalse(recovery.recover(networkFailure))
        advanceTimeBy(60_000)
        assertEquals(0, retries)
    }

    @Test fun `offline only toggled during delay blocks retry before transport`() = runTest {
        var allowed = true
        var retries = 0
        val recovery = PlaybackRecovery(this, { allowed }) { retries++ }
        recovery.recover(networkFailure)
        advanceTimeBy(500)
        allowed = false
        advanceTimeBy(500); runCurrent()
        assertEquals(0, retries)
        assertEquals(PlaybackFailureKind.OFFLINE, recovery.state.value.failure?.kind)
        assertFalse(recovery.manualRetry())
        allowed = true
        assertTrue(recovery.manualRetry())
    }

    @Test fun `network flapping restarts delay without duplicate attempts`() = runTest {
        var retries = 0
        val recovery = PlaybackRecovery(this, { true }) { retries++ }
        recovery.recover(networkFailure)
        repeat(5) {
            advanceTimeBy(500)
            recovery.setNetworkAvailable(false)
            recovery.setNetworkAvailable(true)
        }
        assertEquals(0, retries)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, retries)
    }

    @Test fun `manual retry and ready cancel old delayed work`() = runTest {
        var retries = 0
        val recovery = PlaybackRecovery(this, { true }) { retries++ }
        recovery.recover(networkFailure)
        assertTrue(recovery.manualRetry())
        advanceTimeBy(10_000)
        assertEquals(0, retries)
        recovery.recover(networkFailure)
        recovery.ready()
        advanceTimeBy(10_000)
        assertEquals(0, retries)
        assertEquals(PlaybackRecoveryPhase.NONE, recovery.state.value.phase)
    }

    @Test fun `duplicate fatal errors replace pending work instead of spawning competing retries`() = runTest {
        var retries = 0
        val recovery = PlaybackRecovery(this, { true }) { retries++ }
        recovery.recover(networkFailure)
        advanceTimeBy(500)
        recovery.recover(networkFailure)
        advanceTimeBy(500); runCurrent()
        assertEquals(0, retries)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, retries)
    }
}
