package top.cylunex.shadowmedia.network

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import top.cylunex.shadowmedia.model.NetworkPolicy
import java.io.IOException

class OfflineModeInterceptorTest {
    @Test fun `enabling offline mode stops an already opened network body`() {
        val client = OkHttpClient.Builder().addInterceptor(OfflineModeInterceptor()).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("pending".toResponseBody()).build()
        }.build()
        try {
            NetworkPolicy.offlineOnly = false
            client.newCall(Request.Builder().url("https://example.com/media").build()).execute().use { response ->
                NetworkPolicy.offlineOnly = true
                try { response.body!!.string(); fail("opened transport must stop") } catch (_: IOException) { }
            }
        } finally { NetworkPolicy.offlineOnly = false }
    }
    @Test fun `offline gate prevents reaching transport and resumes without replacing client`() {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor(OfflineModeInterceptor()).addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("ok".toResponseBody()).build()
        }.build()
        try {
            NetworkPolicy.offlineOnly = true
            try { client.newCall(Request.Builder().url("https://example.com/media").build()).execute(); fail("network must be blocked") } catch (_: IOException) { }
            assertEquals(0, requests)
            NetworkPolicy.offlineOnly = false
            client.newCall(Request.Builder().url("https://example.com/media").build()).execute().close()
            assertEquals(1, requests)
        } finally { NetworkPolicy.offlineOnly = false }
    }
}
