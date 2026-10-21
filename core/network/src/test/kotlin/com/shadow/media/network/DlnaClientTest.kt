package com.shadow.media.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DlnaClientTest {
    private fun description(control: String = "/control") = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device><friendlyName>Living &amp; Room</friendlyName><UDN>uuid:renderer</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>$control</controlURL></service></serviceList></device></root>"""
    @Test fun `discovered renderer uses its exact origin and SOAP escapes metadata`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(description()))
            server.enqueue(MockResponse().setBody("<ok/>"))
            server.start()
            val client = DlnaClient()
            val device = client.describe(server.url("/device.xml").toString())
            assertEquals("Living & Room", device.name)
            assertEquals(server.url("/control").toString(), device.controlUrl)
            client.setMedia(device, "http://127.0.0.1/media?one=1&two=2", "A < B & C", "video/mp4")
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", request.getHeader("SOAPAction"))
            val root = DlnaClient.xml(request.body.readByteArray())
            assertEquals("http://127.0.0.1/media?one=1&two=2", root.getElementsByTagNameNS("*", "CurrentURI").item(0).textContent)
            assertTrue(root.getElementsByTagNameNS("*", "CurrentURIMetaData").item(0).textContent.contains("A &lt; B &amp; C"))
        }
    }
    @Test fun `renderer cannot redirect control to another origin`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(description("https://example.com/control"))); server.start()
            try { DlnaClient().describe(server.url("/device.xml").toString()); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `position and transport are read as a single remote snapshot`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(description()))
            server.enqueue(MockResponse().setBody("<position><RelTime>00:01:02.5</RelTime><TrackDuration>01:02:03</TrackDuration><TrackURI>http://127.0.0.1/opaque</TrackURI></position>"))
            server.enqueue(MockResponse().setBody("<transport><CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState></transport>"))
            server.start()
            val client = DlnaClient(); val device = client.describe(server.url("/device.xml").toString())
            val value = client.position(device)
            assertEquals(62_500L, value.positionMs); assertEquals(3_723_000L, value.durationMs)
            assertEquals("PAUSED_PLAYBACK", value.transport)
            assertEquals("http://127.0.0.1/opaque", value.trackUri)
        }
    }
    @Test fun `SOAP faults cannot masquerade as successful control responses`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(description()))
            server.enqueue(MockResponse().setBody("<s:Envelope xmlns:s='http://schemas.xmlsoap.org/soap/envelope/'><s:Body><s:Fault><faultcode>unsupported</faultcode></s:Fault></s:Body></s:Envelope>"))
            server.start(); val client = DlnaClient(); val device = client.describe(server.url("/device.xml").toString())
            try { client.play(device); fail() } catch (_: java.io.IOException) { }
        }
    }
    @Test fun `external XML entities are rejected before resolving them`() {
        try { DlnaClient.xml("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///private/example'>]><root>&x;</root>".toByteArray()); fail() } catch (_: Exception) { }
    }
    @Test fun `XML declarations cannot bypass guards using UTF16 or internal entities`() {
        val entity = "<!DOCTYPE root [<!ENTITY x 'expanded'>]><root>&x;</root>"
        for (bytes in listOf(entity.toByteArray(), entity.toByteArray(Charsets.UTF_16), "<root>\u0000</root>".toByteArray())) {
            assertTrue(runCatching { DlnaClient.xml(bytes) }.isFailure)
        }
        assertEquals("测试", DlnaClient.xml("\uFEFF<?xml version='1.0' encoding='UTF-8'?><root>测试</root>".toByteArray()).documentElement.textContent)
    }
    @Test fun `DLNA time handles hours fractions and invalid values`() {
        assertEquals("27:01:01", dlnaTime(97_261_000))
        assertEquals(97_261_000L, parseDlnaTime("27:01:01"))
        for (bad in listOf("NOT_IMPLEMENTED", "00:60:00", "-1:00:00", "00:00:NaN", "00:00:Infinity")) assertEquals(0L, parseDlnaTime(bad))
    }
}
