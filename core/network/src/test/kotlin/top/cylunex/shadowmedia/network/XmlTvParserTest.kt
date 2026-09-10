package top.cylunex.shadowmedia.network

import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlTvParserTest {
    @Test
    fun `parses current programme metadata`() {
        val now = System.currentTimeMillis()
        val start = format(now - 60_000)
        val stop = format(now + 30 * 60_000)
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="$start" stop="$stop" channel="cctv1">
                <title lang="zh">新闻直播间</title>
                <desc>实时新闻</desc>
                <category>新闻</category>
                <icon src="https://example.com/program.png" />
              </programme>
            </tv>
        """.trimIndent()

        val programs = XmlTvParser.parse("source", ByteArrayInputStream(xml.encodeToByteArray()))

        assertEquals(1, programs.size)
        assertEquals("cctv1", programs.single().channelId)
        assertEquals("新闻直播间", programs.single().title)
        assertTrue(programs.single().endEpochMs > programs.single().startEpochMs)
    }

    private fun format(epochMs: Long): String = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(epochMs))
}

class CatchupUrlResolverTest {
    @Test fun `calendar placeholders distinguish month minute day and seconds`() {
        val start = java.time.Instant.parse("2026-09-10T13:27:45Z").toEpochMilli()
        val entry = top.cylunex.shadowmedia.model.ExternalMediaEntry("news", "source", "News", "https://example.com/live", catchupSource = "https://example.com/{Y}/{m}/{d}/{H}/{M}/{S}")
        val program = top.cylunex.shadowmedia.model.LiveProgram("source", "news", "News", startEpochMs = start, endEpochMs = start + 60000)
        assertEquals("https://example.com/2026/09/10/13/27/45", CatchupUrlResolver.resolve(entry, program)?.url)
    }
    @Test
    fun `resolves common timestamp template`() {
        val entry = top.cylunex.shadowmedia.model.ExternalMediaEntry(
            id = "news",
            sourceId = "source",
            title = "新闻",
            url = "https://live.example.com/news.m3u8",
            catchupSource = "https://replay.example.com/news?start={utc}&duration={duration}",
        )
        val program = top.cylunex.shadowmedia.model.LiveProgram(
            sourceId = "source",
            channelId = "news",
            title = "晚间新闻",
            startEpochMs = 1_700_000_000_000,
            endEpochMs = 1_700_001_800_000,
        )

        val resolved = CatchupUrlResolver.resolve(entry, program)

        requireNotNull(resolved)
        assertEquals(
            "https://replay.example.com/news?start=1700000000&duration=1800",
            resolved.url,
        )
    }
}
