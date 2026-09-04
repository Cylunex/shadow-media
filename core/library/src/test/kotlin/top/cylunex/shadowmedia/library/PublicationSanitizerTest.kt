package top.cylunex.shadowmedia.library

import org.junit.Assert.*
import org.junit.Test

class PublicationSanitizerTest {
    @Test fun removesNamespaceScriptsAndSvgMutation() {
        val result = PublicationSanitizer.sanitizeMarkup("<svg xmlns:x='http://www.w3.org/2000/svg'><x:script>evil()</x:script><set attributeName='href' to='javascript:evil()'/><text>保留图文</text></svg>")
        assertFalse(result.contains("evil")); assertFalse(result.contains("<set")); assertTrue(result.contains("保留图文"))
    }
    @Test fun removesPublicationCodeAndRemoteNavigation() {
        val result = PublicationSanitizer.sanitizeMarkup("<html><head><script src='evil.js'/><meta http-equiv='refresh' content='0;https://example.com'/></head><body onload='attack()'><iframe src='https://example.com'/><p>保留正文</p><a href='javascript:attack()'>链接</a></body></html>")
        assertFalse(result.contains("<script")); assertFalse(result.contains("onload")); assertFalse(result.contains("<iframe")); assertFalse(result.contains("javascript:"))
        assertTrue(result.contains("保留正文")); assertTrue(result.contains("Content-Security-Policy")); assertTrue(result.contains("connect-src 'none'"))
    }
}
