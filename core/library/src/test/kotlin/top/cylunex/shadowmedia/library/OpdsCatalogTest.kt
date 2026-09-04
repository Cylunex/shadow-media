package top.cylunex.shadowmedia.library
import org.junit.Test
import org.junit.Assert.*

class OpdsCatalogTest {
    @Test fun xmlResolvesRelativePagesAndDoesNotTreatBuyAsDownload() {
        val page = OpdsCatalog.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><title>书库</title><link rel="next" href="?page=2"/><entry><id>one</id><title>小说</title><author><name>作者</name></author><link rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip" href="books/1.epub"/></entry><entry><id>two</id><title>购买</title><link rel="http://opds-spec.org/acquisition/buy" type="application/epub+zip" href="pay.epub"/></entry></feed>""".toByteArray(), "https://example.com/opds/")
        assertEquals(1, page.entries.size); assertEquals("https://example.com/opds/books/1.epub", page.entries.single().acquisition)
        assertEquals("https://example.com/opds/?page=2", page.next)
        assertEquals("one", OpdsCatalog.decodeLocator(page.entries.single().locator).second)
    }
    @Test fun opdsTwoNavigationAndDownload() {
        val page = OpdsCatalog.parse("""{"metadata":{"title":"Books"},"navigation":[{"title":"分类","href":"genres"}],"publications":[{"metadata":{"identifier":"b1","title":"书"},"links":[{"rel":["http://opds-spec.org/acquisition"],"type":"application/epub+zip","href":"a.epub"}]}]}""".toByteArray(), "https://example.com/opds/")
        assertEquals(2, page.entries.size); assertEquals("epub", page.entries[0].format); assertNotNull(page.entries[1].navigation)
    }
}
