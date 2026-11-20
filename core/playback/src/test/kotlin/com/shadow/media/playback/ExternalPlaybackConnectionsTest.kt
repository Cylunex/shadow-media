package com.shadow.media.playback

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExternalPlaybackConnectionsTest {
    @Test fun `closing player queues connection teardown off the caller thread`() {
        val executor = Executors.newSingleThreadExecutor()
        val releaseWorker = CountDownLatch(1)
        executor.execute { releaseWorker.await() }
        val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).build()
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("media"))
                client.newCall(Request.Builder().url(server.url("/media")).build())
                    .execute().use { assertEquals("media", it.body.string()) }
                assertEquals(1, client.connectionPool.idleConnectionCount())

                client.closeExternalPlaybackConnections()
                assertTrue(executor.isShutdown)
                assertEquals(1, client.connectionPool.idleConnectionCount())

                releaseWorker.countDown()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
                assertEquals(0, client.connectionPool.connectionCount())
            }
        } finally {
            releaseWorker.countDown()
            executor.shutdownNow()
        }
    }
}
