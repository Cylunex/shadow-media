package top.cylunex.shadowmedia.reading

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse
import top.cylunex.shadowmedia.library.PublicationSanitizer
import top.cylunex.shadowmedia.library.TextPublication
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReadingFixtureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun fixture(name: String): File = File.createTempFile("reading-fixture-", ".${name.substringAfterLast('.')}", instrumentation.targetContext.cacheDir).also { file ->
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
    }
    @Test fun epub2Epub3RtlAndFixedLayoutOpenWithNavigationAndSearch() = runBlocking {
        val engine = ReadiumEngineAdapter(instrumentation.targetContext)
        for (name in listOf("epub2-cjk.epub", "epub3-cjk-font.epub", "epub3-rtl.epub", "epub3-fixed.epub")) {
            val original = fixture(name); val safe = File.createTempFile("safe-fixture-", ".epub", original.parentFile)
            try {
                PublicationSanitizer.sanitize(original, safe)
                val publication = engine.open(safe)
                try {
                    assertEquals(name, 2, publication.readingOrder.size)
                    assertEquals(name, 2, publication.tableOfContents.size)
                    val search = requireNotNull(publication.search("SHADOW_SEARCH_NEEDLE_1"))
                    val found = search.next().getOrElse { error("Fixture search failed: $name") }
                    assertTrue(name, found?.locators?.isNotEmpty() == true)
                    assertTrue(found!!.locators.first().href.toString().contains("chapter1"))
                } finally { publication.close() }
            } finally { original.delete(); safe.delete() }
        }
    }
    @Test fun oldLocatorRoundTripKeepsResourceAndReadingCoordinates() {
        val value = instrumentation.context.assets.open("legacy-locator.json").bufferedReader().use { JSONObject(it.readText()) }
        val first = requireNotNull(Locator.fromJSON(value))
        val second = requireNotNull(Locator.fromJSON(first.toJSON()))
        assertEquals(first.href, second.href); assertEquals(first.locations, second.locations); assertEquals(first.text, second.text)
    }
    @Test fun utf8AndGb18030ConvertWithoutReplacingOriginal() = runBlocking {
        for ((name, encoding) in listOf("utf8.txt" to "UTF-8", "gb18030.txt" to "GB18030")) {
            val source = fixture(name); val bytes = source.readBytes(); val converted = File.createTempFile("converted-", ".epub", source.parentFile)
            try {
                TextPublication.convert(source, converted, name, encoding)
                val publication = ReadiumEngineAdapter(instrumentation.targetContext).open(converted)
                try { assertTrue(publication.readingOrder.isNotEmpty()) } finally { publication.close() }
                assertArrayEquals(bytes, source.readBytes())
            } finally { source.delete(); converted.delete() }
        }
    }
    @Test @SdkSuppress(minSdkVersion = 35) fun pdfReflowKeepsEveryBlockOnItsOriginalPage() = runBlocking {
        val source = fixture("physical-pages.pdf")
        try {
            val first = extractPdfText(source, 0); val second = extractPdfText(source, 1)
            assertTrue(first.any { "SHADOW_PDF_PAGE_ONE" in it.text }); assertTrue(second.any { "SHADOW_PDF_PAGE_TWO" in it.text })
            assertTrue(first.all { it.page == 0 }); assertTrue(second.all { it.page == 1 })
        } finally { source.delete() }
    }
}
