package top.cylunex.shadowmedia.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import top.cylunex.shadowmedia.model.NetworkPolicy

class OfflineModeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (NetworkPolicy.offlineOnly) throw IOException("仅离线模式已开启")
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        val guarded = object : ResponseBody() {
            private val stream = object : ForwardingSource(body.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    if (NetworkPolicy.offlineOnly) { close(); throw IOException("仅离线模式已开启") }
                    return super.read(sink, byteCount)
                }
            }.buffer()
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = stream
        }
        return response.newBuilder().body(guarded).build()
    }
}
