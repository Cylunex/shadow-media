package top.cylunex.shadowmedia.network

import org.junit.Assert.assertEquals
import org.junit.Test
import top.cylunex.shadowmedia.model.PlayMethod
import top.cylunex.shadowmedia.model.PlaybackEvent

class PlaybackOutboxReducerTest {
    @Test
    fun `coalesces progress for same server user item and play session`() {
        val oldProgress = record(positionTicks = 10)
        val newProgress = record(positionTicks = 20)

        val result = enqueuePlaybackRecord(listOf(oldProgress), newProgress, maxRecords = 500)

        assertEquals(listOf(20L), result.map { it.positionTicks })
    }

    @Test
    fun `keeps progress from another server isolated`() {
        val firstServer = record(serverUrl = "https://one.example.com/", positionTicks = 10)
        val secondServer = record(serverUrl = "https://two.example.com/", positionTicks = 20)

        val result = enqueuePlaybackRecord(listOf(firstServer), secondServer, maxRecords = 500)

        assertEquals(listOf(10L, 20L), result.map { it.positionTicks })
    }

    @Test
    fun `does not replace lifecycle events`() {
        val started = record(event = PlaybackEvent.STARTED.name, positionTicks = 0)
        val progress = record(positionTicks = 20)

        val result = enqueuePlaybackRecord(listOf(started), progress, maxRecords = 500)

        assertEquals(listOf(PlaybackEvent.STARTED.name, PlaybackEvent.TIME_UPDATE.name), result.map { it.event })
    }

    private fun record(
        serverUrl: String = "https://one.example.com/",
        event: String = PlaybackEvent.TIME_UPDATE.name,
        positionTicks: Long,
    ) = PlaybackOutboxRecordDto(
        serverUrl = serverUrl,
        serverId = "server",
        userId = "user",
        itemId = "item",
        mediaSourceId = "source",
        playSessionId = "play-session",
        positionTicks = positionTicks,
        isPaused = false,
        canSeek = true,
        event = event,
        playMethod = PlayMethod.DIRECT_PLAY.name,
        createdAtEpochMs = 1,
    )
}
