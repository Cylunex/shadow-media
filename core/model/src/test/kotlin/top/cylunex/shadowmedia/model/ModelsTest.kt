package top.cylunex.shadowmedia.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    @Test
    fun `converts between milliseconds and Emby ticks`() {
        assertEquals(123_450_000L, 12_345L.millisecondsToEmbyTicks())
        assertEquals(12_345L, 123_450_000L.embyTicksToMilliseconds())
    }
}
