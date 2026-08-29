package top.cylunex.shadowmedia.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfoParserTest {
    @Test
    fun `parses common Kodi movie metadata and ids`() {
        val nfo = parseNfoMetadata(
            """
            <movie>
              <title>影子电影</title>
              <originaltitle>Shadow Movie</originaltitle>
              <plot>一段本地刮削简介。</plot>
              <year>2026</year>
              <rating>8.7</rating>
              <runtime>123 min</runtime>
              <genre>剧情</genre>
              <genre>科幻</genre>
              <actor><name>演员甲</name></actor>
              <uniqueid type="tmdb" default="true">12345</uniqueid>
              <imdbid>tt1234567</imdbid>
            </movie>
            """.trimIndent()
        )

        assertEquals("影子电影", nfo.title)
        assertEquals("Shadow Movie", nfo.originalTitle)
        assertEquals(2026, nfo.year)
        assertEquals(8.7, nfo.rating!!, 0.001)
        assertEquals(123, nfo.runtimeMinutes)
        assertEquals(listOf("剧情", "科幻"), nfo.genres)
        assertEquals(listOf("演员甲"), nfo.actors)
        assertEquals("12345", nfo.externalIds["Tmdb"])
        assertEquals("tt1234567", nfo.externalIds["Imdb"])
    }

    @Test
    fun `parses episode coordinates`() {
        val nfo = parseNfoMetadata("<episodedetails><title>第一集</title><season>2</season><episode>3</episode></episodedetails>")

        assertEquals("第一集", nfo.title)
        assertEquals(2, nfo.season)
        assertEquals(3, nfo.episode)
    }

    @Test
    fun `rejects doctype entities`() {
        val result = runCatching {
            parseNfoMetadata("<!DOCTYPE movie [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><movie><title>&xxe;</title></movie>")
        }

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("root:"))
    }
}
