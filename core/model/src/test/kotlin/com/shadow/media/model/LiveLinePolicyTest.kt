package com.shadow.media.model

import org.junit.Assert.*
import org.junit.Test
class LiveLinePolicyTest {
    private val one = ExternalMediaEntry("one", "source", "channel", "https://example.com/one")
    private val two = one.copy(id = "two", url = "https://example.com/two")
    @Test fun `failed line is never retried within the same switching session`() {
        assertEquals(two, chooseLiveLine(listOf(one, two), setOf(liveLineKey(one)), { LiveLineHealth() }, 1000))
        assertNull(chooseLiveLine(listOf(one), setOf(liveLineKey(one)), { LiveLineHealth() }, 1000))
    }
    @Test fun `recent failed line ranks after a fresh unknown line`() {
        assertEquals(two, chooseLiveLine(listOf(one, two), emptySet(), { if (it == one) LiveLineHealth(20, 1, 1, 900) else LiveLineHealth() }, 1000))
    }
}
